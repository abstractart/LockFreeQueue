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

    public synchronized boolean isEmpty() {
        return head == null;
    }
}
