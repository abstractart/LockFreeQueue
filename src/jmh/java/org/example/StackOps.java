package org.example;

// Однородный фасад над всеми реализациями стека для JMH.
// Виртуальный вызов добавляет небольшой инлайн-барьер, но он одинаков
// для всех имплементаций, поэтому сравнение остаётся честным.
interface StackOps {
    // Sentinel returned by poll() on an empty stack. Chosen so it never collides
    // with values the benchmarks push (prefill 0..N and the constant 42).
    int EMPTY = Integer.MIN_VALUE;

    void push(int v);
    int pop();

    // Non-throwing pop used by the benchmarks: returns EMPTY on an empty stack
    // rather than throwing EmptyStackException, so a momentarily-empty stack does
    // not add scheduling-dependent exception allocation (~500 B/throw) to the
    // measured path — a source of GC pressure and latency-tail noise.
    int poll();
}
