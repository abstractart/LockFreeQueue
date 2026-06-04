package org.example;

import java.util.EmptyStackException;

class Node {
    int val;
    Node next;

    Node(int val) {
        this.val = val;
    }

    Node(int val, Node next) {
       this(val);
       this.next = next;
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
        Node currHead = head.next;

        if (currHead == null) {
            throw new EmptyStackException();
        }
        head.next = currHead.next;

        if (tail == currHead) {
            tail = head;
        }
        return currHead.val;
    }

    void push(int val) {
        tail.next = new Node(val);
        tail = tail.next;
    }
}