package org.example;

import java.util.EmptyStackException;
import java.util.concurrent.atomic.AtomicReference;

public class LockFreeStack {
    AtomicReference<AtomicNode> head;

    LockFreeStack() {
        AtomicNode node = new AtomicNode(0);

        head = new AtomicReference<AtomicNode>(node);
    }

    void push(int val) {
        while (true) {
            AtomicNode dummyHead = head.get();
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
            AtomicNode dummyHead = head.get();
            AtomicNode realHead = dummyHead.next.get();

            if (realHead == null) {
                throw new EmptyStackException();
            }

           if (head.compareAndSet(dummyHead, realHead)) {
               return realHead.val;
           }
        }
    }
}
