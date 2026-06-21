package org.example;

import java.util.EmptyStackException;
import java.util.concurrent.atomic.AtomicReference;

class AtomicNode {
    int val;
    AtomicReference<AtomicNode> next;

    AtomicNode(int val) {
        this.val = val;
        next = new AtomicReference<>(null);
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
            AtomicNode result = currHead.next.get();

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
            AtomicNode dirtyTail = currTail.next.get();
            AtomicNode candidate = new AtomicNode(val);

            if (dirtyTail != null) {
                tail.compareAndSet(currTail, dirtyTail);
                continue;
            }

            if (currTail.next.compareAndSet(null, candidate)) {
                tail.compareAndSet(currTail, candidate);
                return;
            }
        }
    }
}