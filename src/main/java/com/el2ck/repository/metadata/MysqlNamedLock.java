package com.el2ck.repository.metadata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * MySQL 会话锁的 JDBC 边界：明确区分锁被占用与数据库故障，并安全归还池化连接。
 * 调用方独占连接直到任务结束；即使 GET_LOCK 的响应丢失，也必须执行释放流程。
 */
public final class MysqlNamedLock {
    private static final Logger LOG = LoggerFactory.getLogger(MysqlNamedLock.class);

    private MysqlNamedLock() { }

    /** 非等待获取锁。只有明确的 0 表示被占用，NULL、缺失结果或驱动异常均向上传播。 */
    public static boolean tryAcquire(Connection connection, String name) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT GET_LOCK(?,0)")) {
            query.setQueryTimeout(5);
            query.setString(1, name);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) throw new SQLException("Missing GET_LOCK result");
                int result = rows.getInt(1);
                if (rows.wasNull()) throw new SQLException("GET_LOCK returned NULL");
                if (result != 0 && result != 1) throw new SQLException("Unexpected GET_LOCK result");
                return result == 1;
            }
        }
    }

    /**
     * 释放锁后归还连接；释放失败时先终止物理会话，防止带锁连接回到 Hikari 池。
     * Connection.close() 在池化场景只归还连接，并不保证结束 MySQL 会话。
     * 清理失败记录日志，不覆盖调用方原本的业务异常；每个会话只能调用一次。
     */
    public static void releaseAndClose(Connection connection, String... names) {
        if (connection == null) return;
        try {
            // 同一连接可能同时持有兼容旧版的地址锁和新版物理目标锁；全部释放后才能归还池。
            for (String name : new java.util.LinkedHashSet<>(java.util.Arrays.asList(names))) {
                if (name == null) continue;
                try (PreparedStatement query = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
                    query.setQueryTimeout(5);
                    query.setString(1, name);
                    query.execute();
                }
            }
        } catch (SQLException | RuntimeException e) {
            LOG.error("event=metadata.lock_release_failed locks={}", java.util.Arrays.toString(names), e);
            try { connection.abort(Runnable::run); }
            catch (SQLException | RuntimeException abort) {
                LOG.error("event=metadata.lock_abort_failed locks={}", java.util.Arrays.toString(names), abort);
            }
        } finally {
            try { connection.close(); }
            catch (SQLException | RuntimeException close) {
                LOG.warn("event=metadata.lock_connection_close_failed locks={}", java.util.Arrays.toString(names), close);
            }
        }
    }
}
