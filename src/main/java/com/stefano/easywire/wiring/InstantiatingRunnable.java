package com.stefano.easywire.wiring;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

/**
 * Created by franzs on 6/10/14.
 */
public class InstantiatingRunnable implements Runnable {

    private final Class classToInstantiate;
    private final ConcurrentMap<Class, Object> instantiatedObjects;
    private final ConcurrentMap<Class, ConcurrentMap<String, Object>> preInstantiatedProvides;

    public InstantiatingRunnable(Class classToInstantiate, ConcurrentMap<Class, Object> instantiatedObjects, ConcurrentMap<Class, ConcurrentMap<String, Object>> preInstantiatedProvides) {
        this.classToInstantiate = classToInstantiate;
        this.instantiatedObjects = instantiatedObjects;
        this.preInstantiatedProvides = preInstantiatedProvides;
    }

    private static <AT extends Annotation> AT getParameterAnnotation(Annotation[] parameterAnnotations, Class<AT> classToFind) {
        for (Annotation parameterAnnotation : parameterAnnotations) {
            if (parameterAnnotation.annotationType() == classToFind) {
                return (AT) parameterAnnotation;
            }
        }

        return null;
    }

    @Override
    public void run() {

        Constructor constructor = classToInstantiate.getConstructors()[0];
        final Annotation[][] constructorParameterAnnotations = constructor.getParameterAnnotations();
        final Class[] constructorParameterTypes = constructor.getParameterTypes();
        Object[] objectsToPassIn = new Object[constructorParameterTypes.length];


        for (int i = 0; i < constructorParameterTypes.length; i++) {
            Object objectToUse;
            final Class parameterType = constructorParameterTypes[i];
            final Annotation[] parameterAnnotations = constructorParameterAnnotations[i];
            Provided providedAnnotation = getParameterAnnotation(parameterAnnotations, Provided.class);
            if (providedAnnotation != null) {
                objectToUse = findObjectWithProvidedQualifier(parameterType, providedAnnotation);
            } else {
                objectToUse = findObjectWithoutProvidedQualifier(parameterType);
            }
            objectsToPassIn[i] = objectToUse;
        }

        try {
            Object constructedObject = constructor.newInstance(objectsToPassIn);
            instantiatedObjects.put(classToInstantiate, constructedObject);
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }

    }

    private Object findObjectWithoutProvidedQualifier(Class parameterType) {
        Object objectToUse;
        if ((objectToUse = instantiatedObjects.get(parameterType)) == null) {
            Map<String, Object> qualifiedObjects = preInstantiatedProvides.get(parameterType);
            if (qualifiedObjects != null) {
                objectToUse = qualifiedObjects.get(BeanProvider.DEFAULT);
            }
        }

        if (objectToUse == null) {
            throw new RuntimeException("Failed to find a provider for type: " + parameterType);
        }
        return objectToUse;
    }

    private Object findObjectWithProvidedQualifier(Class parameterType, Provided providedAnnotation) {
        final String qualifier = providedAnnotation.value();

        Map<String, Object> qualifiedObjects = preInstantiatedProvides.get(parameterType);
        if (qualifiedObjects == null) {
            return null;
        }
        Object preinstantiated = qualifiedObjects.get(qualifier);
        if (preinstantiated == null) {
            return null;
        }

        return preinstantiated;
    }


}
