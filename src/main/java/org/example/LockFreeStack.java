package org.example;

import java.util.EmptyStackException;

public class LockFreeStack {
    AtomicNode head;

    LockFreeStack() {
        head = new AtomicNode(0);
    }

    void push(int val) {
        while (true) {
            AtomicNode dummyHead = head;
            AtomicNode realHead = dummyHead.next.get();

            AtomicNode candidate = new AtomicNode(val);
            candidate.next.set(realHead);

            if (dummyHead.next.compareAndSet(realHead, candidate)) {
                return;
            }
        }
    }

    int pop() {
        while(true) {
            AtomicNode dummyHead = head;
            AtomicNode realHead = dummyHead.next.get();
            if (realHead == null) {
                throw new EmptyStackException();
            }
            AtomicNode newHead = realHead.next.get();


            if (dummyHead.next.compareAndSet(realHead, newHead)) {
               return realHead.val;
           }
        }
    }
}
