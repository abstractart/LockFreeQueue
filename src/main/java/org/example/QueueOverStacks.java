package org.example;

import java.util.ArrayList;
import java.util.NoSuchElementException;

public class QueueOverStacks {
    ArrayList<Integer> first;
    ArrayList<Integer> second;

    QueueOverStacks() {
        first = new ArrayList<>();
        second = new ArrayList<>();
    }

    int pop() {
        if (isEmpty()) {
            throw new NoSuchElementException();
        }

        if (!second.isEmpty()) {
            return second.removeLast();
        }
        while(!first.isEmpty()) {
            second.add(first.removeLast());
        }

        return pop();
    }

    void push(int val) {
        first.add(val);
    }

    boolean isEmpty() {
        return first.isEmpty() && second.isEmpty();
    }
}
