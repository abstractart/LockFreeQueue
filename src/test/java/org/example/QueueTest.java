package org.example;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EmptyStackException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class QueueTest {

    @Test
    @DisplayName("pop() на новой очереди бросает EmptyStackException")
    void popOnEmptyQueueThrows() {
        Queue queue = new Queue();
        assertThrows(EmptyStackException.class, queue::pop);
    }

    @Test
    @DisplayName("push() + pop() возвращает положенное значение")
    void pushThenPopReturnsValue() {
        Queue queue = new Queue();
        queue.push(42);
        assertEquals(42, queue.pop());
    }

    @Test
    @DisplayName("FIFO: элементы извлекаются в порядке добавления")
    void fifoOrder() {
        Queue queue = new Queue();
        queue.push(1);
        queue.push(2);
        queue.push(3);

        assertEquals(1, queue.pop());
        assertEquals(2, queue.pop());
        assertEquals(3, queue.pop());
    }

    @Test
    @DisplayName("pop() после опустошения очереди бросает EmptyStackException")
    void popAfterDrainingThrows() {
        Queue queue = new Queue();
        queue.push(10);
        queue.pop();
        assertThrows(EmptyStackException.class, queue::pop);
    }

    @Test
    @DisplayName("Поддерживаются отрицательные и нулевые значения")
    void supportsZeroAndNegativeValues() {
        Queue queue = new Queue();
        queue.push(0);
        queue.push(-1);
        queue.push(Integer.MIN_VALUE);
        queue.push(Integer.MAX_VALUE);

        assertEquals(0, queue.pop());
        assertEquals(-1, queue.pop());
        assertEquals(Integer.MIN_VALUE, queue.pop());
        assertEquals(Integer.MAX_VALUE, queue.pop());
    }

    @Test
    @DisplayName("Допускаются дубликаты")
    void allowsDuplicates() {
        Queue queue = new Queue();
        queue.push(7);
        queue.push(7);
        queue.push(7);

        assertEquals(7, queue.pop());
        assertEquals(7, queue.pop());
        assertEquals(7, queue.pop());
        assertThrows(EmptyStackException.class, queue::pop);
    }

    @Test
    @DisplayName("Чередование push/pop сохраняет FIFO")
    void interleavedPushAndPop() {
        Queue queue = new Queue();
        queue.push(1);
        queue.push(2);
        assertEquals(1, queue.pop());

        queue.push(3);
        assertEquals(2, queue.pop());
        assertEquals(3, queue.pop());

        assertThrows(EmptyStackException.class, queue::pop);
    }

    @Test
    @DisplayName("push после полного опустошения очереди работает корректно")
    void pushAfterFullyDrainingWorks() {
        Queue queue = new Queue();
        queue.push(1);
        assertEquals(1, queue.pop());

        queue.push(2);
        assertEquals(2, queue.pop(), "После опустошения очередь должна снова принимать элементы (выявляет баг с устаревшим tail)");
    }

    @Test
    @DisplayName("Большое количество элементов сохраняет порядок")
    void largeNumberOfElementsPreservesOrder() {
        Queue queue = new Queue();
        int n = 10_000;
        for (int i = 0; i < n; i++) {
            queue.push(i);
        }
        for (int i = 0; i < n; i++) {
            assertEquals(i, queue.pop());
        }
        assertThrows(EmptyStackException.class, queue::pop);
    }

    @Test
    @DisplayName("Несколько циклов опустошение/наполнение сохраняют FIFO")
    void multipleDrainAndRefillCycles() {
        Queue queue = new Queue();

        for (int cycle = 0; cycle < 3; cycle++) {
            queue.push(cycle * 10 + 1);
            queue.push(cycle * 10 + 2);

            assertEquals(cycle * 10 + 1, queue.pop());
            assertEquals(cycle * 10 + 2, queue.pop());
            assertThrows(EmptyStackException.class, queue::pop);
        }
    }

    @Test
    @DisplayName("Состояние очереди не меняется при бросании исключения из pop()")
    void failedPopDoesNotCorruptState() {
        Queue queue = new Queue();
        assertThrows(EmptyStackException.class, queue::pop);

        queue.push(5);
        assertEquals(5, queue.pop());
    }
}
