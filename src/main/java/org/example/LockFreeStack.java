package org.example;

import java.util.EmptyStackException;

public class LockFreeStack {
    AtomicNode head;

    LockFreeStack() {
        head = new AtomicNode(0);
    }

    void push(int val) {
        AtomicNode candidate = new AtomicNode(val);
        while (true) {
            AtomicNode realHead = head.next.get();
            candidate.next.set(realHead);
            if (head.next.compareAndSet(realHead, candidate)) {
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

    boolean isEmpty() {
        return head.next.get() == null;
    }
}
