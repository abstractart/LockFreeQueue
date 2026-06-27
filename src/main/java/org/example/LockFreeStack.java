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
            AtomicNode realHead = head.next;
            candidate.next = realHead;
            if (head.casNext(realHead, candidate)) {
                return;
            }
        }
    }

    int pop() {
        while (true) {
            AtomicNode realHead = head.next;
            if (realHead == null) {
                throw new EmptyStackException();
            }
            AtomicNode newHead = realHead.next;
            if (head.casNext(realHead, newHead)) {
                return realHead.val;
            }
        }
    }

    boolean isEmpty() {
        return head.next == null;
    }
}
