package org.example;

import java.util.EmptyStackException;
import java.util.concurrent.locks.ReentrantLock;

public class ReentrantLockStack {
    private final ReentrantLock lock = new ReentrantLock();
    private Node head;

    public ReentrantLockStack() {
        head = null;
    }

    public void push(int val) {
        lock.lock();
        try {
            Node newHead = new Node(val);
            newHead.next = head;
            head = newHead;
        } finally {
            lock.unlock();
        }
    }

    public int pop() {
        lock.lock();
        try {
            if (head == null) {
                throw new EmptyStackException();
            }
            Node curr = head;
            head = curr.next;
            return curr.val;
        } finally {
            lock.unlock();
        }
    }

    public boolean isEmpty() {
        lock.lock();
        try {
            return head == null;
        } finally {
            lock.unlock();
        }
    }
}
