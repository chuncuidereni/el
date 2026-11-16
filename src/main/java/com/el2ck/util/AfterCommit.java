package com.el2ck.util;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 数据库提交与内存缓存失效的边界：回滚时丢弃副作用，无事务时直接执行。 */
public final class AfterCommit {
    private AfterCommit() { }

    /** 缓存清理必须看到已提交数据，不能在数据库事务尚未结束时让其他请求重建旧配置。 */
    public static void run(Runnable action) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { action.run(); }
            });
        } else {
            action.run();
        }
    }
}
