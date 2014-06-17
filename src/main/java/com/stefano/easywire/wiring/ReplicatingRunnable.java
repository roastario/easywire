package com.stefano.easywire.wiring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentMap;

/**
 * Created by franzs on 6/17/14.
 */
public class ReplicatingRunnable implements Runnable{

    private final Class implementedInterface;
    private final Class implementer;
    private ConcurrentMap<Class, Object> instantiatedObjects;
    private static final Logger LOGGER = LoggerFactory.getLogger(ReplicatingRunnable.class);

    public ReplicatingRunnable(Class implementedInterfaces, Class implementer, ConcurrentMap<Class, Object> instantiatedObjects) {
        this.instantiatedObjects = instantiatedObjects;
        this.implementedInterface = implementedInterfaces;
        this.implementer = implementer;
    }

    @Override
    public void run() {
           LOGGER.info("Replicating instantiation of: " + implementer.getCanonicalName() +" to " + implementedInterface);
           instantiatedObjects.put(implementedInterface, instantiatedObjects.get(implementer));
    }
}
