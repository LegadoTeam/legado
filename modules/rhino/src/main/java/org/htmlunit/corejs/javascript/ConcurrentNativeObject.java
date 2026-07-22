package org.htmlunit.corejs.javascript;

import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Native object backed by an Android-compatible thread-safe slot map. */
public class ConcurrentNativeObject extends NativeObject {

    public ConcurrentNativeObject() {
        setMap(new ReentrantReadWriteLockHashSlotMap<>());
    }

    private static final class ReentrantReadWriteLockHashSlotMap<T extends PropHolder<T>>
            extends HashSlotMap<T> implements LockAwareSlotMap<T> {

        private static final long READ_LOCK = 1;
        private static final long WRITE_LOCK = 2;

        private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

        @Override
        public int size() {
            lock.readLock().lock();
            try {
                return super.size();
            } finally {
                lock.readLock().unlock();
            }
        }

        @Override
        public int dirtySize() {
            return super.size();
        }

        @Override
        public boolean isEmpty() {
            lock.readLock().lock();
            try {
                return super.isEmpty();
            } finally {
                lock.readLock().unlock();
            }
        }

        @Override
        public Slot<T> modify(SlotMapOwner<T> owner, Object key, int index, int attributes) {
            lock.writeLock().lock();
            try {
                return super.modify(owner, key, index, attributes);
            } finally {
                lock.writeLock().unlock();
            }
        }

        @Override
        public <S extends Slot<T>> S compute(
                SlotMapOwner<T> owner,
                CompoundOperationMap<T> mutableMap,
                Object key,
                int index,
                SlotComputer<S, T> computer) {
            lock.writeLock().lock();
            try {
                return super.compute(owner, mutableMap, key, index, computer);
            } finally {
                lock.writeLock().unlock();
            }
        }

        @Override
        public Slot<T> query(Object key, int index) {
            lock.readLock().lock();
            try {
                return super.query(key, index);
            } finally {
                lock.readLock().unlock();
            }
        }

        @Override
        public void add(SlotMapOwner<T> owner, Slot<T> newSlot) {
            lock.writeLock().lock();
            try {
                super.add(owner, newSlot);
            } finally {
                lock.writeLock().unlock();
            }
        }

        @Override
        public void addWithLock(SlotMapOwner<T> owner, Slot<T> newSlot) {
            super.add(owner, newSlot);
        }

        @Override
        public <S extends Slot<T>> S computeWithLock(
                SlotMapOwner<T> owner,
                CompoundOperationMap<T> mutableMap,
                Object key,
                int index,
                SlotComputer<S, T> computer) {
            return super.compute(owner, mutableMap, key, index, computer);
        }

        @Override
        public boolean isEmptyWithLock() {
            return super.isEmpty();
        }

        @Override
        public Slot<T> modifyWithLock(
                SlotMapOwner<T> owner, Object key, int index, int attributes) {
            return super.modify(owner, key, index, attributes);
        }

        @Override
        public Slot<T> queryWithLock(Object key, int index) {
            return super.query(key, index);
        }

        @Override
        public int sizeWithLock() {
            return super.size();
        }

        @Override
        public long getReadLock() {
            lock.readLock().lock();
            return READ_LOCK;
        }

        @Override
        public long getWriteLock() {
            lock.writeLock().lock();
            return WRITE_LOCK;
        }

        @Override
        public void releaseLock(long lockType) {
            if (lockType == READ_LOCK) {
                lock.readLock().unlock();
            } else if (lockType == WRITE_LOCK) {
                lock.writeLock().unlock();
            } else {
                throw new IllegalArgumentException("Unknown lock type: " + lockType);
            }
        }
    }
}
