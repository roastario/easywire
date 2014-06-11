package com.stefano.easywire.wiring;

import com.google.common.base.Predicate;
import com.google.common.collect.Collections2;
import com.stefano.easywire.task.TaskExecutor;
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

    private static Set<MethodQualifierPair> buildProviderInfo(Collection<Method> beanProviderMethods) {
        Set<MethodQualifierPair> pairs = new HashSet<>();
        for (Method beanProviderMethod : beanProviderMethods) {
            Class<?> returnedType = beanProviderMethod.getReturnType();
            BeanProvider annotation = getMethodAnnotation(beanProviderMethod, BeanProvider.class);
            String qualifier = annotation.value();
            MethodQualifierPair methodQualifierPair = new MethodQualifierPair(beanProviderMethod, qualifier, returnedType);
            if (pairs.contains(methodQualifierPair)) {
                throw new RuntimeException("Duplicated qualifier / type combo: " + methodQualifierPair);
            }
            pairs.add(methodQualifierPair);
        }
        return pairs;
    }

    private static <K1, V1, K> void dealWithConcurrentMapShit(ConcurrentMap<K, ConcurrentMap<K1, V1>> map, K key, K1 subKey, V1 subValue) {
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
        Set<MethodQualifierPair> providers = buildProviderInfo(beanProviderMethods);
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


    private void buildExecutorGraphs(Set<Class<?>> classes, ConcurrentMap<Class, Object> instantiatedObjects, final ConcurrentMap<Class, ConcurrentMap<String, Object>> preInstantiatedProvides) {
        for (Class<?> clazz : classes) {
            Constructor[] constructors = clazz.getConstructors();
            if (constructors.length != 1) {
                throw new IllegalStateException("The bean class: " + clazz.getCanonicalName() + " has multiple constructors");
            }
            Constructor constructor = constructors[0];
            final Class[] parameterTypes = constructor.getParameterTypes();
            Collection filteredList = Collections2.filter(asList(parameterTypes), new Predicate<Class>() {
                @Override
                public boolean apply(@Nullable Class input) {
                    return !preInstantiatedProvides.containsKey(input);
                }
            });
            executor.buildTaskFromDependencyInformation(clazz, filteredList, new InstantiatingRunnable(clazz, instantiatedObjects, preInstantiatedProvides));
        }
    }


    private ConcurrentMap<Class, ConcurrentMap<String, Object>> concurrentlyInstantiateProvidedObjects(Set<MethodQualifierPair> pairs) {
        final ConcurrentMap<Class, ConcurrentMap<String, Object>> instantiatedObjects = new ConcurrentHashMap<>();

        List<Future> waitables = new ArrayList<>();
        for (final MethodQualifierPair pair : pairs) {

            Runnable r = new Runnable() {

                Class toInstantiate = pair.providedClass;
                Class providerClass = pair.getMethod().getDeclaringClass();
                String qualifier = pair.getQualifier();

                @Override
                public void run() {
                    try {
                        Object instantiatedProviderClass = providerClass.newInstance();
                        Object providedObject = pair.getMethod().invoke(instantiatedProviderClass);
                        dealWithConcurrentMapShit(instantiatedObjects, toInstantiate, qualifier, providedObject);
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

    private static class MethodQualifierPair {
        private final String qualifier;
        private final Method method;
        private final Class providedClass;

        private MethodQualifierPair(Method method, String qualifier, Class providedClass) {
            this.qualifier = qualifier;
            this.method = method;
            this.providedClass = providedClass;
        }

        @Override
        public String toString() {

            return "MethodQualifierPair{" +
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

            MethodQualifierPair that = (MethodQualifierPair) o;

            if (!providedClass.equals(that.providedClass)) return false;
            if (!qualifier.equals(that.qualifier)) return false;
//            if (!method.equals(that.method)) return false;

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
