package org.example;

final class Queues {
    private Queues() {}

    static QueueOps create(String name) {
        return switch (name) {
            case "Queue" -> new QueueAdapter();
            case "LockedQueue" -> new LockedQueueAdapter();
            case "ReentrantLockQueue" -> new ReentrantLockQueueAdapter();
            case "LockFreeQueue" -> new LockFreeQueueAdapter();
            default -> throw new IllegalArgumentException("Unknown queue impl: " + name);
        };
    }

    private static final class QueueAdapter implements QueueOps {
        private final Queue q = new Queue();
        public void push(int v) { q.push(v); }
        public int pop() { return q.pop(); }
    }

    private static final class LockedQueueAdapter implements QueueOps {
        private final LockedQueue q = new LockedQueue();
        public void push(int v) { q.push(v); }
        public int pop() { return q.pop(); }
    }

    private static final class ReentrantLockQueueAdapter implements QueueOps {
        private final ReentrantLockQueue q = new ReentrantLockQueue();
        public void push(int v) { q.push(v); }
        public int pop() { return q.pop(); }
    }

    private static final class LockFreeQueueAdapter implements QueueOps {
        private final LockFreeQueue q = new LockFreeQueue();
        public void push(int v) { q.push(v); }
        public int pop() { return q.pop(); }
    }
}
