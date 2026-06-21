package org.example;

import java.util.NoSuchElementException;

public class StackOverQueues {
    Queue q1;
    Queue q2;

    StackOverQueues() {
        q1 = new Queue();
        q2 = new Queue();
    }

    void push(int val) {
        q2.push(val);

        while (!q1.isEmpty()) {
            q2.push(q1.pop());
        }

        Queue tmp = q1;
        q1 = q2;
        q2 = tmp;
    }

    int pop() {
        if (q1.isEmpty()) {
            throw new NoSuchElementException();
        }
        return q1.pop();
    }
}
