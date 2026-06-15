package org.example;

import java.util.EmptyStackException;

public class Stack {
    Node head;

    Stack() {
        head = null;
    }

    void push(int val) {
        Node newHead = new Node(val);
        newHead.next = head;
        head = newHead;
    }

    int pop() {
        if (isEmpty()) {
            throw new EmptyStackException();
        }

        Node currHead = head;
        head = currHead.next;

        return currHead.val;
    }

    boolean isEmpty() {
        return head == null;
    }
}
