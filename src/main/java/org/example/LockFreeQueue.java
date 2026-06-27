package org.example;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.EmptyStackException;
import java.util.concurrent.atomic.AtomicReference;

class AtomicNode {
    int val;
    volatile AtomicNode next;

    private static final VarHandle NEXT;
    static {
        try {
            NEXT = MethodHandles.lookup()
                    .findVarHandle(AtomicNode.class, "next", AtomicNode.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    AtomicNode(int val) {
        this.val = val;
    }

    boolean casNext(AtomicNode expected, AtomicNode update) {
        return NEXT.compareAndSet(this, expected, update);
    }
}

class LockFreeQueue {
    AtomicReference<AtomicNode> head;
    AtomicReference<AtomicNode> tail;

    LockFreeQueue() {
        AtomicNode node = new AtomicNode(0);
        head = new AtomicReference<AtomicNode>(node);
        tail = new AtomicReference<AtomicNode>(node);
    }

    int pop() {
        while (true) {
            AtomicNode currHead = head.get();
            AtomicNode result = currHead.next;

            if (result == null) {
                throw new EmptyStackException();
            }
            if (head.compareAndSet(currHead, result)) {
                return result.val;
            }
        }
    }

    void push(int val) {
        while (true) {
            AtomicNode currTail = tail.get();
            AtomicNode dirtyTail = currTail.next;
            AtomicNode candidate = new AtomicNode(val);

            if (dirtyTail != null) {
                tail.compareAndSet(currTail, dirtyTail);
                continue;
            }

            if (currTail.casNext(null, candidate)) {
                tail.compareAndSet(currTail, candidate);
                return;
            }
        }
    }
}
