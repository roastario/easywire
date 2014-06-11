package com.stefano.easywire.wiring;

import java.util.Comparator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListSet;

/**
 * Created by franzs on 6/10/14.
 */
public class DependencyGraph <T> {

    private final ConcurrentMap<T, Set<T>> graph = new ConcurrentHashMap<>();
    private final Comparator<T> comparator;

    public DependencyGraph(Comparator<T> comparator) {
        this.comparator = comparator;
    }



    public void registerNode(T node){
        Set<T> downSteams = getSetForNode(node);
    }


    public void registerDependency(T dependee, T dependency){
        Set<T> downstreamNodes = getSetForNode(dependency);
        downstreamNodes.add(dependee);
    }

    private Set<T> getSetForNode(T node){
        Set<T> setOfProvides = graph.get(node);

        if (setOfProvides == null){
            Set<T> newSet = new ConcurrentSkipListSet<>(comparator);
            setOfProvides = graph.putIfAbsent(node, newSet);

            if (setOfProvides == null){
                setOfProvides = newSet;
            }
        }
        return setOfProvides;
    }

}
