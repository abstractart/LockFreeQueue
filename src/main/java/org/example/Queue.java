package org.example;

import java.util.EmptyStackException;

class Node {
    int val;
    Node next;

    Node(int val) {
        this.val = val;
    }
}

class Queue {
    Node head;
    Node tail;

    Queue() {
        head = new Node(0);
        tail = head;
    } 

    int pop() {
        if (head.next == null) {
            throw new EmptyStackException();
        }

        int result = head.next.val;
        head = head.next;

        return result;
    }

    void push(int val) {
        tail.next = new Node(val);
        tail = tail.next;
    }
}