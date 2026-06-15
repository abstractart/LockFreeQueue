package org.example;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StackOverQueuesTest {

    @Test
    @DisplayName("pop() на новом стеке бросает NoSuchElementException")
    void popOnEmptyStackThrows() {
        StackOverQueues stack = new StackOverQueues();
        assertThrows(NoSuchElementException.class, stack::pop);
    }

    @Test
    @DisplayName("push() + pop() возвращает положенное значение")
    void pushThenPopReturnsValue() {
        StackOverQueues stack = new StackOverQueues();
        stack.push(42);
        assertEquals(42, stack.pop());
    }

    @Test
    @DisplayName("LIFO: элементы извлекаются в обратном порядке добавления")
    void lifoOrder() {
        StackOverQueues stack = new StackOverQueues();
        stack.push(1);
        stack.push(2);
        stack.push(3);

        assertEquals(3, stack.pop());
        assertEquals(2, stack.pop());
        assertEquals(1, stack.pop());
    }

    @Test
    @DisplayName("pop() после опустошения стека бросает NoSuchElementException")
    void popAfterDrainingThrows() {
        StackOverQueues stack = new StackOverQueues();
        stack.push(10);
        stack.pop();
        assertThrows(NoSuchElementException.class, stack::pop);
    }

    @Test
    @DisplayName("Поддерживаются отрицательные и нулевые значения")
    void supportsZeroAndNegativeValues() {
        StackOverQueues stack = new StackOverQueues();
        stack.push(0);
        stack.push(-1);
        stack.push(Integer.MIN_VALUE);
        stack.push(Integer.MAX_VALUE);

        assertEquals(Integer.MAX_VALUE, stack.pop());
        assertEquals(Integer.MIN_VALUE, stack.pop());
        assertEquals(-1, stack.pop());
        assertEquals(0, stack.pop());
    }

    @Test
    @DisplayName("Допускаются дубликаты")
    void allowsDuplicates() {
        StackOverQueues stack = new StackOverQueues();
        stack.push(7);
        stack.push(7);
        stack.push(7);

        assertEquals(7, stack.pop());
        assertEquals(7, stack.pop());
        assertEquals(7, stack.pop());
        assertThrows(NoSuchElementException.class, stack::pop);
    }

    @Test
    @DisplayName("Чередование push/pop сохраняет LIFO")
    void interleavedPushAndPop() {
        StackOverQueues stack = new StackOverQueues();
        stack.push(1);
        stack.push(2);
        assertEquals(2, stack.pop());

        stack.push(3);
        assertEquals(3, stack.pop());
        assertEquals(1, stack.pop());

        assertThrows(NoSuchElementException.class, stack::pop);
    }

    @Test
    @DisplayName("push после полного опустошения стека работает корректно")
    void pushAfterFullyDrainingWorks() {
        StackOverQueues stack = new StackOverQueues();
        stack.push(1);
        assertEquals(1, stack.pop());

        stack.push(2);
        assertEquals(2, stack.pop());
    }

    @Test
    @DisplayName("Большое количество элементов сохраняет LIFO-порядок")
    void largeNumberOfElementsPreservesOrder() {
        StackOverQueues stack = new StackOverQueues();
        int n = 10_000;
        for (int i = 0; i < n; i++) {
            stack.push(i);
        }
        for (int i = n - 1; i >= 0; i--) {
            assertEquals(i, stack.pop());
        }
        assertThrows(NoSuchElementException.class, stack::pop);
    }

    @Test
    @DisplayName("Несколько циклов опустошение/наполнение сохраняют LIFO")
    void multipleDrainAndRefillCycles() {
        StackOverQueues stack = new StackOverQueues();

        for (int cycle = 0; cycle < 3; cycle++) {
            stack.push(cycle * 10 + 1);
            stack.push(cycle * 10 + 2);

            assertEquals(cycle * 10 + 2, stack.pop());
            assertEquals(cycle * 10 + 1, stack.pop());
            assertThrows(NoSuchElementException.class, stack::pop);
        }
    }

    @Test
    @DisplayName("Состояние стека не меняется при бросании исключения из pop()")
    void failedPopDoesNotCorruptState() {
        StackOverQueues stack = new StackOverQueues();
        assertThrows(NoSuchElementException.class, stack::pop);

        stack.push(5);
        assertEquals(5, stack.pop());
    }
}
