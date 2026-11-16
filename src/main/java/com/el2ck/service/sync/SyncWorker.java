package com.el2ck.service.sync;

import java.util.function.Consumer;

/**
 * 一次同步的本地线程与取消状态。取消请求和进入收尾共用监视器：
 * 先收到取消则记住原因并中断执行；先进入收尾则拒绝取消，保护结果写库和会话锁释放。
 * 该对象不代替 MySQL 任务锁，也不代表跨实例取消。
 */
final class SyncWorker {
    private final Thread thread;
    private boolean finishing;
    private String stopReason;

    SyncWorker(String name, Consumer<SyncWorker> execution) {
        thread = new Thread(() -> execution.accept(this), name);
        thread.setDaemon(true);
    }

    void start() { thread.start(); }
    Thread thread() { return thread; }

    /** 首次原因优先；重复请求返回已接单，但不重复中断驱动的清理过程。 */
    synchronized boolean requestStop(String reason) {
        if (finishing) return false;
        if (stopReason == null) {
            stopReason = reason;
            thread.interrupt();
        }
        return true;
    }

    /** 某些驱动会消费中断标记，故还要检查持久于本轮运行的取消原因。 */
    synchronized void checkRunning() {
        if (stopReason != null) throw new IllegalStateException(stopReason);
        if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("已取消");
    }

    /** 仅由本工作线程调用；原子关闭取消入口后清除中断，后续 MySQL 收尾不再被取消打断。 */
    synchronized String beginFinish() {
        if (stopReason == null && thread.isInterrupted()) stopReason = "已取消";
        finishing = true;
        Thread.interrupted();
        return stopReason;
    }
}
