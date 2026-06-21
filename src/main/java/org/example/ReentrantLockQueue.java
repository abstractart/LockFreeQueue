package org.example;

import java.util.EmptyStackException;
import java.util.concurrent.locks.ReentrantLock;

public class ReentrantLockQueue {
    private final ReentrantLock lock = new ReentrantLock();
    private Node head;
    private Node tail;

    public ReentrantLockQueue() {
        head = new Node(0);
        tail = head;
    }

    public int pop() {
        lock.lock();
        try {
            if (head.next == null) {
                throw new EmptyStackException();
            }
            int result = head.next.val;
            head = head.next;
            return result;
        } finally {
            lock.unlock();
        }
    }

    public void push(int val) {
        lock.lock();
        try {
            tail.next = new Node(val);
            tail = tail.next;
        } finally {
            lock.unlock();
        }
    }

    public boolean isEmpty() {
        lock.lock();
        try {
            return head.next == null;
        } finally {
            lock.unlock();
        }
    }
}
