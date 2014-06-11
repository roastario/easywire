package com.stefano.easywire

import com.stefano.easywire.task.TaskExecutor
import spock.lang.Specification
import spock.lang.Subject

/**
 * Created by franzs on 6/10/14.
 */
class TaskExecutorTest extends Specification {


    @Subject
    TaskExecutor<Integer> executor = new TaskExecutor<>();


    def 'the executor should correctly build up a dependency map for known tasks'(){
        given:
            def stack = new Stack<Integer>();
            for (int i = 0 ; i < 10 ; i++){
                stack.push(i);
            }

            def checkedList = []
            while (!stack.empty()){
                final Integer i = stack.pop();
                Collection<Integer> dependencies = stack;

                Runnable r = new StackingRunnable(i, 0, checkedList);
                executor.buildTaskFromDependencyInformation(i, dependencies, r)
            }

        when:
            executor.execute();
        then:
            checkedList.equals([0,1,2,3,4,5,6,7,8,9])
    }



    def 'the executor should be reusable without having to rebuild the whole dependency tree'(){

        given:
            def theList = []
            executor.buildTaskFromDependencyInformation(4, [2],new StackingRunnable(4,0,theList));
            executor.buildTaskFromDependencyInformation(2, [1],new StackingRunnable(2,100,theList));
            executor.buildTaskFromDependencyInformation(3, [1],new StackingRunnable(3, 0, theList));
            executor.buildTaskFromDependencyInformation(1, [],new StackingRunnable(1, 0, theList));
        when:
            executor.execute();
        then:
            theList.equals([1,3,2,4])

        when:
            executor.execute()
        then:
            theList.equals([1,3,2,4,1,3,2,4])


    }


    def 'the executor should only schedule each task once'(){

        given:
            def theList = []
            executor.buildTaskFromDependencyInformation(4, [2],new StackingRunnable(4,0,theList));
            executor.buildTaskFromDependencyInformation(2, [1],new StackingRunnable(2,100,theList));
            executor.buildTaskFromDependencyInformation(3, [1],new StackingRunnable(3, 0, theList));
            executor.buildTaskFromDependencyInformation(1, [],new StackingRunnable(1, 0, theList));
        when:
            executor.execute();
        then:
            theList.equals([1,3,2,4])

    }

    private static class StackingRunnable implements Runnable{

        private final Object printMe;
        private final long delay;
        private final List list;

        StackingRunnable(Object printMe, long delay, List list) {
            this.printMe = printMe
            this.delay = delay
            this.list = list;
        }

        @Override
        void run() {
            Thread.sleep(delay)
            this.list.add(printMe);
        }
    }

}
