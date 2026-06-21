package org.example;

final class Stacks {
    private Stacks() {}

    static StackOps create(String name) {
        return switch (name) {
            case "Stack" -> new StackAdapter();
            case "LockedStack" -> new LockedStackAdapter();
            case "ReentrantLockStack" -> new ReentrantLockStackAdapter();
            case "LockFreeStack" -> new LockFreeStackAdapter();
            default -> throw new IllegalArgumentException("Unknown stack impl: " + name);
        };
    }

    private static final class StackAdapter implements StackOps {
        private final Stack s = new Stack();
        public void push(int v) { s.push(v); }
        public int pop() { return s.pop(); }
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
}
