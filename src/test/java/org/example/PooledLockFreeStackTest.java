package org.example;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EmptyStackException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PooledLockFreeStackTest {

    @Test
    @DisplayName("default constructor produces a usable stack")
    void defaultConstructor() {
        PooledLockFreeStack stack = new PooledLockFreeStack();
        assertTrue(stack.isEmpty());
        stack.push(7);
        assertFalse(stack.isEmpty());
        assertEquals(7, stack.pop());
        assertTrue(stack.isEmpty());
    }

    @Test
    @DisplayName("push/pop is LIFO")
    void lifoOrder() {
        PooledLockFreeStack stack = new PooledLockFreeStack(8);
        stack.push(1);
        stack.push(2);
        stack.push(3);
        assertEquals(3, stack.pop());
        assertEquals(2, stack.pop());
        assertEquals(1, stack.pop());
        assertTrue(stack.isEmpty());
    }

    @Test
    @DisplayName("pop() on empty stack throws EmptyStackException")
    void popEmptyThrows() {
        PooledLockFreeStack stack = new PooledLockFreeStack(4);
        assertThrows(EmptyStackException.class, stack::pop);
    }

    @Test
    @DisplayName("push() past capacity throws (pool exhausted)")
    void pushFullThrows() {
        PooledLockFreeStack stack = new PooledLockFreeStack(2);
        stack.push(1);
        stack.push(2);
        assertThrows(IllegalStateException.class, () -> stack.push(3));
        // After freeing one slot, a push succeeds again.
        assertEquals(2, stack.pop());
        stack.push(42);
        assertEquals(42, stack.pop());
    }

    @Test
    @DisplayName("slots are reused across push/pop cycles — far more ops than capacity, no overflow")
    void slotsAreReused() {
        PooledLockFreeStack stack = new PooledLockFreeStack(4);
        for (int i = 0; i < 10_000; i++) {
            stack.push(i);
            assertEquals(i, stack.pop());
        }
        assertTrue(stack.isEmpty());
    }

    @Test
    @DisplayName("isEmpty() tracks state through interleaved push/pop")
    void isEmptyTracksState() {
        PooledLockFreeStack stack = new PooledLockFreeStack(4);
        assertTrue(stack.isEmpty());
        stack.push(1);
        assertFalse(stack.isEmpty());
        stack.push(2);
        stack.pop();
        assertFalse(stack.isEmpty());
        stack.pop();
        assertTrue(stack.isEmpty());
    }
}
