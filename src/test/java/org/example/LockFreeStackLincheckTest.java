package org.example;

import org.jetbrains.lincheck.datastructures.IntGen;
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions;
import org.jetbrains.lincheck.datastructures.Operation;
import org.jetbrains.lincheck.datastructures.Param;
import org.jetbrains.lincheck.datastructures.StressOptions;
import org.junit.jupiter.api.Test;

// Lincheck использует данный класс одновременно и как объект под тестом,
// и как последовательную спецификацию: операции выполняются конкурентно,
// затем проверяется существование линейной истории, объясняющей наблюдаемые результаты.
// Поскольку sequential-вызовы LockFreeStack корректно реализуют LIFO, такой self-test
// валиден для проверки линеаризуемости.
@Param(name = "value", gen = IntGen.class, conf = "1:5")
public class LockFreeStackLincheckTest {

    private final LockFreeStack stack = new LockFreeStack();

    @Operation
    public void push(@Param(name = "value") int v) {
        stack.push(v);
    }

    // EmptyStackException обрабатывается Lincheck автоматически:
    // тип исключения становится «результатом» операции, и верификатор сравнивает его так же,
    // как обычные возвращаемые значения.
    @Operation
    public int pop() {
        return stack.pop();
    }

    @Test
    public void stressTest() {
        new StressOptions()
                .iterations(50)
                .threads(3)
                .actorsPerThread(3)
                .invocationsPerIteration(1000)
                .check(this.getClass());
    }

    @Test
    public void modelCheckingTest() {
        new ModelCheckingOptions()
                .iterations(50)
                .threads(3)
                .actorsPerThread(3)
                .invocationsPerIteration(1000)
                .check(this.getClass());
    }
}
