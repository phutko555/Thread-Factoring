package com.epam.rd.autotasks;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

public interface ThreadUnion extends ThreadFactory {
    int totalSize();
    int activeSize();

    void shutdown();
    boolean isShutdown();
    void awaitTermination();
    boolean isFinished();

    List<FinishedThreadResult> results();

    static ThreadUnion newInstance(String name) {
        return new ThreadUnion() {
            private final String unionName = name;
            private final List<Thread> threads = Collections.synchronizedList(new ArrayList<>());
            private final List<FinishedThreadResult> results = Collections.synchronizedList(new ArrayList<>());
            private final AtomicInteger threadCounter = new AtomicInteger(0);
            private final ReentrantLock lock = new ReentrantLock();
            private volatile boolean shutdown = false;

            @Override
            public Thread newThread(Runnable runnable) {
                lock.lock();
                try {
                    if (shutdown) {
                        throw new IllegalStateException("ThreadUnion is shutdown.");
                    }

                    int threadNumber = threadCounter.getAndIncrement();
                    String threadName = String.format("%s-worker-%d", unionName, threadNumber);
                    Thread thread = new Thread(() -> {
                        try {
                            runnable.run();
                            results.add(new FinishedThreadResult(threadName));
                        } catch (Throwable throwable) {
                            results.add(new FinishedThreadResult(threadName, throwable));
                            throw throwable;
                        }
                    }, threadName);
                    threads.add(thread);
                    return thread;
                } finally {
                    lock.unlock();
                }
            }

            @Override
            public int totalSize() {
                return threadCounter.get();
            }

            @Override
            public int activeSize() {
                lock.lock();
                try {
                    return (int) threads.stream().filter(Thread::isAlive).count();
                } finally {
                    lock.unlock();
                }
            }

            @Override
            public void shutdown() {
                lock.lock();
                try {
                    if (!shutdown) {
                        shutdown = true;
                        for (Thread thread : threads) {
                            thread.interrupt();
                        }
                    }
                } finally {
                    lock.unlock();
                }
            }

            @Override
            public boolean isShutdown() {
                return shutdown;
            }

            @Override
            public void awaitTermination() {
                for (Thread thread : threads) {
                    try {
                        thread.join();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                }
            }

            @Override
            public boolean isFinished() {
                lock.lock();
                try {
                    if (!shutdown) {
                        return false;
                    }
                    return threads.stream().noneMatch(Thread::isAlive);
                } finally {
                    lock.unlock();
                }
            }

            @Override
            public List<FinishedThreadResult> results() {
                lock.lock();
                try {
                    return new ArrayList<>(results);
                } finally {
                    lock.unlock();
                }
            }
        };
    }
}

