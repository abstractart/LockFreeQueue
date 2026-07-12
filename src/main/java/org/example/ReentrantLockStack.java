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

    // Non-throwing pop: returns Integer.MIN_VALUE when empty instead of allocating
    // an EmptyStackException (see LockedStack.poll).
    public int poll() {
        lock.lock();
        try {
            if (head == null) {
                return Integer.MIN_VALUE;
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
