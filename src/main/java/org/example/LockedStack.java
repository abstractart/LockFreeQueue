package org.example;

import java.util.EmptyStackException;

public class LockedStack {
    private Node head;

    public LockedStack() {
        head = null;
    }

    public synchronized void push(int val) {
        Node newHead = new Node(val);
        newHead.next = head;
        head = newHead;
    }

    public synchronized int pop() {
        if (head == null) {
            throw new EmptyStackException();
        }
        Node curr = head;
        head = curr.next;
        return curr.val;
    }

    // Non-throwing pop: returns Integer.MIN_VALUE when empty instead of allocating
    // an EmptyStackException. Used by the benchmarks so a momentarily-empty stack
    // adds no scheduling-dependent exception allocation to the measured path.
    public synchronized int poll() {
        if (head == null) {
            return Integer.MIN_VALUE;
        }
        Node curr = head;
        head = curr.next;
        return curr.val;
    }

    public synchronized boolean isEmpty() {
        return head == null;
    }
}
