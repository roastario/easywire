package com.stefano.easywire.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class TaskExecutor<T> {

    private final static ExecutorService EXECUTOR_SERVICE = Executors.newCachedThreadPool();
    private final Map<T, Collection<T>> downstreamMap = new HashMap<>();
    private final Map<T, Collection<T>> upstreamMap = new HashMap<>();
    private final Map<T, Task> taskMap = new HashMap<>();
    private final static Logger LOGGER = LoggerFactory.getLogger(TaskExecutor.class);
    private final Lock globalLock = new ReentrantLock();

    private Condition waitingCondition;

    private Set<T> targets = new HashSet<>();
    private Set<T> completedTargets = new HashSet<>();
    private Set<T> uncompletedTargets = new HashSet<>();
    private Set<T> currentlyExecutingTargets = new HashSet<>();

    public synchronized void buildTaskFromDependencyInformation(T taskTarget, Collection<T> dependsOn, Runnable task) {

        LOGGER.info("Registering: " + taskTarget + " with dependsOn: " + dependsOn);
        populateDownstreamInfo(taskTarget, dependsOn);
        populateUpstreamInfo(taskTarget, dependsOn);
        taskMap.put(taskTarget, new Task(taskTarget, task));
        targets.add(taskTarget);

    }

    private void populateUpstreamInfo(T taskTarget, Collection<T> dependsOn) {
        Collection<T> upstreamDependencies = upstreamMap.get(taskTarget);
        if (upstreamDependencies == null) {
            upstreamDependencies = new HashSet<>();
            upstreamMap.put(taskTarget, upstreamDependencies);
        }
        upstreamDependencies.addAll(dependsOn);
    }

    private void populateDownstreamInfo(T taskTarget, Collection<T> dependsOn) {
        for (T provider : dependsOn) {
            Collection<T> downstreamProvidees;
            if ((downstreamProvidees = downstreamMap.get(provider)) == null) {
                downstreamProvidees = new HashSet<>();
                downstreamMap.put(provider, downstreamProvidees);
            }
            downstreamProvidees.add(taskTarget);
        }
    }

    public void execute() throws InterruptedException {

        setupGlobalState();
        if (uncompletedTargets.size() == 0){
            return;
        }
        discoverAndScheduleRootTasks();
        waitForCompletion();
        resetGlobalState();
    }

    private void setupGlobalState() {
        uncompletedTargets.addAll(targets);
        waitingCondition = globalLock.newCondition();
    }

    private void resetGlobalState() {
        completedTargets = new HashSet<>();
        uncompletedTargets = new HashSet<>();
        currentlyExecutingTargets = new HashSet<>();
    }

    private void discoverAndScheduleRootTasks() {
        Collection<T> targetsWithNoUpstreams = determineTargetsWithNoUpstream();
        LOGGER.info("Starting targets: " + targetsWithNoUpstreams);
        for (T targetWithNoUpStream : targetsWithNoUpstreams){
            scheduleTask(targetWithNoUpStream);
        }
    }

    private void waitForCompletion() throws InterruptedException {
        globalLock.lock();

        try{
            waitingCondition.await();
        }finally {
            globalLock.unlock();
        }
    }

    private Collection<T> determineTargetsWithNoUpstream() {
        Collection<T> returned = new HashSet<>();
        for (Map.Entry<T, Collection<T>> entry : upstreamMap.entrySet()){
            if (entry.getValue().isEmpty()){
                    returned.add(entry.getKey());
            }
        }

        return returned;
    }

    private synchronized void handleCompletionForTask(Task completedTask) {
        T id = completedTask.id;
        LOGGER.info("Completed: " + id);
        updateTargetState(id);
        checkForCompletion();
        discoverAndScheduleDownstreamDependencies(id);
    }

    private void discoverAndScheduleDownstreamDependencies(T id) {
        for (T downStreamTarget : downstreamMap.get(id)) {
            if (isEligibleForScheduling(downStreamTarget)) {
                scheduleTask(downStreamTarget);
            }
        }
    }

    private void checkForCompletion() {
        globalLock.lock();
        try{
            if (uncompletedTargets.isEmpty()){
                waitingCondition.signalAll();
            }
        }finally{
            globalLock.unlock();
        }
    }

    private void updateTargetState(T id) {
        completedTargets.add(id);
        uncompletedTargets.remove(id);
        currentlyExecutingTargets.remove(id);
    }

    private void scheduleTask(T target) {
        LOGGER.info("Scheduling: " + target);
        Task task = taskMap.get(target);
        currentlyExecutingTargets.add(target);
        EXECUTOR_SERVICE.submit(task);
    }

    private boolean isEligibleForScheduling(T target) {

        if (currentlyExecutingTargets.contains(target)){
            return false;
        }
        for (T upstreamDependency : upstreamMap.get(target)) {
            if (!completedTargets.contains(upstreamDependency)) {
                LOGGER.info("Failed to schedule: " + target + " because: " + upstreamDependency + " has not been executed");
                return false;
            }
        }
        return true;
    }


    public class Task implements Runnable {
        private final T id;
        private final Runnable theRunnable;

        public Task(T id, Runnable theRunnable) {
            this.id = id;
            this.theRunnable = theRunnable;
        }

        @Override
        public void run() {
            LOGGER.info(id + " executing in thread: " + Thread.currentThread().getName());
            theRunnable.run();
            handleCompletionForTask(this);
        }
    }


}
