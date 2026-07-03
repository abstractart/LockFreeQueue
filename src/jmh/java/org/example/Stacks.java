package org.example;

final class Stacks {
    private Stacks() {}

    static StackOps create(String name) {
        return switch (name) {
            case "LockedStack" -> new LockedStackAdapter();
            case "ReentrantLockStack" -> new ReentrantLockStackAdapter();
            case "LockFreeStack" -> new LockFreeStackAdapter();
            case "EliminationStack" -> new EliminationStackAdapter();
            default -> throw new IllegalArgumentException("Unknown stack impl: " + name);
        };
    }

    private static final class LockedStackAdapter implements StackOps {
        private final LockedStack s = new LockedStack();
        public void push(int v) { s.push(v); }
        public int pop() { return s.pop(); }
    }

    private static final class ReentrantLockStackAdapter implements StackOps {
        private final ReentrantLockStack s = new ReentrantLockStack();
        public void push(int v) { s.push(v); }
        public int pop() { return s.pop(); }
    }

    private static final class LockFreeStackAdapter implements StackOps {
        private final LockFreeStack s = new LockFreeStack();
        public void push(int v) { s.push(v); }
        public int pop() { return s.pop(); }
    }

    private static final class EliminationStackAdapter implements StackOps {
        private final EliminationStack s = new EliminationStack();
        public void push(int v) { s.push(v); }
        public int pop() { return s.pop(); }
    }
}
