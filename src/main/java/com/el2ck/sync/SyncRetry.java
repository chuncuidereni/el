package com.el2ck.sync;

/**
 * 【文件职责】定义同步读取和写入的可重试异常判断及退避策略。
 */

/** 仅识别可恢复的传输故障，不重试鉴权、SQL、数据校验或主动取消。 */
public final class SyncRetry {
    // 自动重试只适用于可识别的传输类瞬时失败；调用方必须继续同一 checkpoint token，不能把它当新轮次。
    /** 工具类禁止实例化，所有判断均通过静态方法完成。 */
    private SyncRetry() { }

/**
 * 沿异常 cause 链识别瞬时传输故障。鉴权、授权、SQL 语法/约束、数据校验和主动中断
 * 明确不重试；只要链上存在可恢复连接类异常，则允许调用方在保持同一 checkpoint token 的前提下重试。
 *
 * @param error 原始异常
 * @return true 表示可安全重试传输；false 表示应终止或交由上层处理
 */
    public static boolean transientFailure(Throwable error) {
        java.util.Set<Throwable> seen=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable,Boolean>());
        boolean transport=false;
        for(Throwable cause=error;cause!=null && seen.add(cause);cause=cause.getCause()) {
            if(cause instanceof InterruptedException) return false;
            if(cause instanceof org.apache.kafka.common.errors.AuthenticationException
                    || cause instanceof org.apache.kafka.common.errors.AuthorizationException
                    || cause instanceof org.apache.kafka.common.errors.InterruptException) return false;
            if(cause instanceof org.apache.kafka.common.errors.RetriableException) transport=true;
            if(cause instanceof com.el2ck.util.RemoteSourceFile.SftpConnectionException) transport=true;
            if(cause instanceof SftpResumeCache.BusyException) transport=true;
            if(cause instanceof com.mongodb.MongoSecurityException) return false;
            if(cause instanceof com.mongodb.MongoSocketException || cause instanceof com.mongodb.MongoTimeoutException) transport=true;
            if(cause instanceof java.sql.SQLException) {
                String state=((java.sql.SQLException)cause).getSQLState();
                if(state!=null && (state.startsWith("28") || state.startsWith("42") || state.startsWith("22"))) return false;
                if(state!=null && state.startsWith("08")) transport=true;
            }
            if(cause instanceof java.net.SocketException || cause instanceof java.net.SocketTimeoutException
                    || cause instanceof java.io.EOFException || cause instanceof java.sql.SQLTransientConnectionException
                    || cause instanceof java.sql.SQLRecoverableException || cause instanceof java.sql.SQLTimeoutException) transport=true;
        }
        return transport;
    }

/**
 * 在重试前暂停指定时间，并在睡眠前后保留线程中断语义。
 *
 * @param milliseconds 退避毫秒数
 * @throws IllegalStateException 线程已中断或等待期间被中断时抛出，同时恢复中断标志
 */
    public static void pause(long milliseconds) {
        if(Thread.currentThread().isInterrupted()) throw new IllegalStateException("同步已超时或被中断");
        try { Thread.sleep(milliseconds); }
        catch(InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("同步已超时或被中断",e);
        }
    }
}
