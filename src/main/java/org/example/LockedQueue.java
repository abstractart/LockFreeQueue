package org.example;

import java.util.EmptyStackException;

public class LockedQueue {
    private Node head;
    private Node tail;

    public LockedQueue() {
        head = new Node(0);
        tail = head;
    }

    public synchronized int pop() {
        if (head.next == null) {
            throw new EmptyStackException();
        }
        int result = head.next.val;
        head = head.next;
        return result;
    }

    public synchronized void push(int val) {
        tail.next = new Node(val);
        tail = tail.next;
    }

    public synchronized boolean isEmpty() {
        return head.next == null;
    }
}
