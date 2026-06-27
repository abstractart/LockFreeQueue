package org.example;

import java.util.EmptyStackException;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

public class LockFreeStack {
    volatile AtomicNode head;

    private static final AtomicReferenceFieldUpdater<LockFreeStack, AtomicNode> HEAD =
            AtomicReferenceFieldUpdater.newUpdater(LockFreeStack.class, AtomicNode.class, "head");

    void push(int val) {
        AtomicNode candidate = new AtomicNode(val);
        while (true) {
            AtomicNode currentTop = head;
            candidate.next = currentTop;
            if (HEAD.compareAndSet(this, currentTop, candidate)) {
                return;
            }
        }
    }

    int pop() {
        while (true) {
            AtomicNode currentTop = head;
            if (currentTop == null) {
                throw new EmptyStackException();
            }
            AtomicNode newTop = currentTop.next;
            if (HEAD.compareAndSet(this, currentTop, newTop)) {
                return currentTop.val;
            }
        }
    }

    boolean isEmpty() {
        return head == null;
    }
}
