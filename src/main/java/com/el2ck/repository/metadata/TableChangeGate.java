package com.el2ck.repository.metadata;

import com.el2ck.exception.ApiException;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 协调手动表修改与任务进入 running 状态的短临界区。
 * 手动操作持锁到修改完成；任务仅在登记 running 时持锁，执行过程仍可多任务并行。
 * 配合运行状态检查，避免“检查为空闲后任务恰好启动”的竞态；MySQL 会话锁覆盖多应用实例。
 */
@Component
public class TableChangeGate {
    private static final String NAME = "el_table_change_gate";
    private final DataSource database;

    public TableChangeGate(DataSource database) { this.database = database; }

    public Lease acquire() {
        Lease lease = acquire(5);
        if (lease == null) throw new ApiException(409, "正在修改表或登记任务执行，请稍后重试");
        return lease;
    }

    /** 定时派发遇到表修改应保留排队记录，不能误报为执行失败。 */
    public Lease tryAcquire() {
        return acquire(0);
    }

    private Lease acquire(int seconds) {
        Connection connection = null;
        try {
            connection = database.getConnection();
            boolean acquired;
            try (PreparedStatement query = connection.prepareStatement("SELECT GET_LOCK(?,?)")) {
                query.setQueryTimeout(10);
                query.setString(1, NAME);
                query.setInt(2, seconds);
                try (ResultSet rows = query.executeQuery()) {
                    if (!rows.next()) throw new SQLException("Missing table change gate result");
                    int result = rows.getInt(1);
                    if (rows.wasNull() || (result != 0 && result != 1)) throw new SQLException("Invalid table change gate result");
                    acquired = result == 1;
                }
            }
            if (!acquired) {
                MysqlNamedLock.releaseAndClose(connection, NAME);
                return null;
            }
            return new Lease(connection);
        } catch (SQLException | RuntimeException e) {
            MysqlNamedLock.releaseAndClose(connection, NAME);
            throw new ApiException(503, "无法确认表操作执行权，请稍后重试", e);
        }
    }

    public static final class Lease implements AutoCloseable {
        private Connection connection;

        private Lease(Connection connection) { this.connection = connection; }

        /** 修改前核对会话仍持锁；连接中断时禁止继续向 CK 发出新的修改。 */
        public void check() {
            if (connection == null) throw new ApiException(409, "表操作执行权已释放");
            try (PreparedStatement query = connection.prepareStatement("SELECT IS_USED_LOCK(?)=CONNECTION_ID()")) {
                query.setQueryTimeout(5);
                query.setString(1, NAME);
                try (ResultSet rows = query.executeQuery()) {
                    if (!rows.next() || rows.getInt(1) != 1) throw new SQLException("Table change gate lost");
                }
            } catch (SQLException e) {
                throw new ApiException(503, "表操作执行权已失效，已停止后续修改", e);
            }
        }

        @Override public void close() {
            Connection held = connection;
            connection = null;
            MysqlNamedLock.releaseAndClose(held, NAME);
        }
    }
}
