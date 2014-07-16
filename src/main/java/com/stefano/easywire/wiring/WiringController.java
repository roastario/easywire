package com.stefano.easywire.wiring;

import com.google.common.base.Predicate;
import com.google.common.collect.Collections2;
import org.reflections.Reflections;
import org.reflections.scanners.MethodAnnotationsScanner;
import org.reflections.scanners.SubTypesScanner;
import org.reflections.scanners.TypeAnnotationsScanner;
import org.reflections.util.ClasspathHelper;
import org.reflections.util.ConfigurationBuilder;

import javax.annotation.Nullable;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.*;

import uk.stefano.executor.TaskExecutor;

import static java.util.Arrays.asList;

public class WiringController {
    private final ExecutorService cachedExecutorService = Executors.newCachedThreadPool();
    private final TaskExecutor<Class> executor = new TaskExecutor<>();

    private final Comparator<Class> classComparator = new Comparator<Class>() {
        @Override
        public int compare(Class o1, Class o2) {
            return o1.getCanonicalName().compareTo(o2.getCanonicalName());
        }
    };

    private static Set<ClassAndQualifierMethodKey> buildProviderInfo(Collection<Method> beanProviderMethods) {
        Set<ClassAndQualifierMethodKey> pairs = new HashSet<>();
        for (Method beanProviderMethod : beanProviderMethods) {
            Class<?> returnedType = beanProviderMethod.getReturnType();
            List<Class<?>> matchingClasses = new ArrayList<>(asList(returnedType.getInterfaces()));
            matchingClasses.add(returnedType);
            for (Class matchingClass : matchingClasses) {
                BeanProvider annotation = getMethodAnnotation(beanProviderMethod, BeanProvider.class);
                String qualifier = annotation.value();
                ClassAndQualifierMethodKey qualifierKey = new ClassAndQualifierMethodKey(beanProviderMethod, qualifier, matchingClass);
                System.err.println("qualifier: " + qualifier + " class: " + matchingClass);
                if (pairs.contains(qualifierKey)) {
                    throw new RuntimeException("Duplicated qualifier / type combo: " + qualifierKey);
                }
                pairs.add(qualifierKey);
            }
        }
        return pairs;
    }

    private static <K1, V1, K> void concurrentMapHelper(ConcurrentMap<K, ConcurrentMap<K1, V1>> map, K key, K1 subKey, V1 subValue) {
        ConcurrentMap<K1, V1> mapToAddTo = map.get(key);
        if (mapToAddTo == null) {
            ConcurrentMap<K1, V1> newMap = new ConcurrentHashMap<>();
            mapToAddTo = map.putIfAbsent(key, newMap);
            if (mapToAddTo == null) {
                mapToAddTo = newMap;
            }
        }
        mapToAddTo.putIfAbsent(subKey, subValue);
    }

    private static <AT extends Annotation> AT getMethodAnnotation(Method method, Class<AT> annotationType) {
        Annotation[] annotations = method.getDeclaredAnnotations();
        for (Annotation annotation : annotations) {
            if (annotation.annotationType().equals(annotationType)) {
                return (AT) annotation;
            }
        }
        return null;
    }

    public Map<Class, Object> wire(String packageToSearch) {
        Reflections reflections = new Reflections(new ConfigurationBuilder().setUrls(ClasspathHelper.forPackage(packageToSearch))
                .setScanners(new SubTypesScanner(), new MethodAnnotationsScanner(), new TypeAnnotationsScanner()));

        Set<Class<?>> classes = reflections.getTypesAnnotatedWith(Bean.class);
        Set<Method> beanProviderMethods = reflections.getMethodsAnnotatedWith(BeanProvider.class);
        Set<ClassAndQualifierMethodKey> providers = buildProviderInfo(beanProviderMethods);
        ConcurrentMap<Class, ConcurrentMap<String, Object>> providedBeans = concurrentlyInstantiateProvidedObjects(providers);
        ConcurrentMap<Class, Object> instantiatedObjects = new ConcurrentHashMap<>();

        buildExecutorGraphs(classes, instantiatedObjects, providedBeans);
        try {

            executor.execute();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        return instantiatedObjects;
    }

    private Provided getProvidedAnnotationFromParameterAnnotations(Annotation[] parameterAnnotations) {
        for (Annotation parameterAnnotation : parameterAnnotations) {
            if (parameterAnnotation.annotationType() == Provided.class) {
                return (Provided) parameterAnnotation;
            }
        }
        return null;
    }


    private void buildExecutorGraphs(Set<Class<?>> classes, ConcurrentMap<Class, Object> instantiatedObjects, final ConcurrentMap<Class, ConcurrentMap<String, Object>> preInstantiatedProvides) {
        for (Class<?> clazz : classes) {
            Constructor[] constructors = clazz.getConstructors();
            if (constructors.length != 1) {
                throw new IllegalStateException("The bean class: " + clazz.getCanonicalName() + " has multiple constructors");
            }
            Constructor constructor = constructors[0];
            final Class[] parameterTypes = constructor.getParameterTypes();
            final Annotation[][] constructorParameterAnnotations = constructor.getParameterAnnotations();

            Collection<Class> filteredList = new ArrayList<>();

            //KEEP ALL CLASSES THAT CANNOT BE FOUND IN THE PRE_INSTANTIATED
            for (int parameterIdx = 0; parameterIdx < parameterTypes.length; parameterIdx++) {
                Class type = parameterTypes[parameterIdx];
                Provided providedAnnotation = getProvidedAnnotationFromParameterAnnotations(constructorParameterAnnotations[parameterIdx]);

                if (!preInstantiatedProvides.containsKey(type)) {
                    filteredList.add(type);
                } else {
                    if (providedAnnotation == null) {
                        if (preInstantiatedProvides.containsKey(type)) {
                            filteredList.add(type);
                        }
                    } else {
                        if (!preInstantiatedProvides.get(type).containsKey(providedAnnotation.value())) {
                            filteredList.add(type);
                        }
                    }
                }


            }

            executor.buildTaskFromDependencyInformation(clazz, filteredList, new InstantiatingRunnable(clazz, instantiatedObjects, preInstantiatedProvides));
            Class[] implementedInterfaces = clazz.getInterfaces();
            for (Class implementedInterface : implementedInterfaces) {
                executor.buildTaskFromDependencyInformation(implementedInterface, Collections.<Class>singleton(clazz), new ReplicatingRunnable(implementedInterface, clazz, instantiatedObjects));
            }

        }
    }


    private ConcurrentMap<Class, ConcurrentMap<String, Object>> concurrentlyInstantiateProvidedObjects(Set<ClassAndQualifierMethodKey> pairs) {
        final ConcurrentMap<Class, ConcurrentMap<String, Object>> instantiatedObjects = new ConcurrentHashMap<>();

        List<Future> waitables = new ArrayList<>();
        for (final ClassAndQualifierMethodKey pair : pairs) {

            Runnable r = new Runnable() {

                Class toInstantiate = pair.providedClass;
                Class providerClass = pair.getMethod().getDeclaringClass();
                String qualifier = pair.getQualifier();

                @Override
                public void run() {
                    try {
                        Object instantiatedProviderClass = providerClass.newInstance();
                        Object providedObject = pair.getMethod().invoke(instantiatedProviderClass);
                        concurrentMapHelper(instantiatedObjects, toInstantiate, qualifier, providedObject);
                    } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
                        e.printStackTrace();
                    }
                }
            };
            waitables.add(cachedExecutorService.submit(r));
        }

        for (Future waitable : waitables) {
            try {
                waitable.get();
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException(e);
            }
        }
        return instantiatedObjects;
    }

    private static class ClassAndQualifierMethodKey {
        private final String qualifier;
        private final Method method;
        private final Class providedClass;

        private ClassAndQualifierMethodKey(Method method, String qualifier, Class providedClass) {
            this.qualifier = qualifier;
            this.method = method;
            this.providedClass = providedClass;
        }

        @Override
        public String toString() {

            return "ClassAndQualifierMethodKey{" +
                    "qualifier='" + qualifier + '\'' +
                    ", method=" + method +
                    ", providedClass=" + providedClass +
                    '}';
        }

        public String getQualifier() {
            return qualifier;
        }

        public Method getMethod() {
            return method;
        }


        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            ClassAndQualifierMethodKey that = (ClassAndQualifierMethodKey) o;

            if (!providedClass.equals(that.providedClass)) return false;
            if (!qualifier.equals(that.qualifier)) return false;

            return true;
        }

        @Override
        public int hashCode() {
            int result = qualifier.hashCode();
            result = 31 * result + providedClass.hashCode();
            return result;
        }
    }

}
