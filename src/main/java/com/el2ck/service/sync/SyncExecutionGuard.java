package com.el2ck.service.sync;

import com.el2ck.exception.ApiException;
import com.el2ck.repository.metadata.MysqlNamedLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;
import java.sql.*;
import java.util.List;

/**
 * 【文件职责】用独占 MySQL 会话锁保护同步任务的完整生命周期。
 * 锁先于 running 记录创建，晚于执行/收尾释放；另一实例只能在获得同一把锁后恢复遗留状态。
 * 不使用客户端时钟判断失联，也不在启动时无条件重置其他实例的运行记录。
 */
@Component
public class SyncExecutionGuard {
    private static final Logger LOG = LoggerFactory.getLogger(SyncExecutionGuard.class);
    private final DataSource database;
    private final JdbcTemplate jdbc;

    public SyncExecutionGuard(DataSource database) {
        this.database = database;
        this.jdbc = new JdbcTemplate(database);
        // 状态扫描同样需要超时，避免一次慢查询长期占用 Spring 的定时调度线程。
        this.jdbc.setQueryTimeout(5);
    }

    public Lease reserve(long taskId) {
        Lease lease = tryReserveForRun(taskId);
        if (lease == null) throw new ApiException(409, "任务正在运行中，请等待完成");
        return lease;
    }

    /** 排队派发遇到活动任务只延后，不把等待记录误记为失败。 */
    Lease tryReserveForRun(long taskId) {
        Lease lease=tryReserve(taskId);
        if(lease==null) return null;
        try { recover(lease); return lease; }
        catch (RuntimeException | Error e) { lease.close(); throw e; }
    }

    /** 清理只尝试占锁，不等待、不修改运行历史；运行中的任务整轮跳过。 */
    public Lease tryReserveForMaintenance(long taskId) {
        return tryReserve(taskId);
    }

    /** GET_LOCK 返回 NULL 或数据库故障时拒绝执行；只有明确的 0 表示正在被其他实例占用。 */
    Lease tryReserve(long taskId) {
        Connection connection = null;
        try {
            connection = database.getConnection();
            if (!MysqlNamedLock.tryAcquire(connection, "el_sync_task_" + taskId)) {
                connection.close();
                return null;
            }
            return new Lease(connection, taskId);
        } catch (SQLException | RuntimeException | Error e) {
            // GET_LOCK 可能已成功但响应/关闭结果集失败；归还池前仍须尝试释放同名锁。
            if (connection != null) new Lease(connection, taskId).close();
            if (e instanceof Error) throw (Error)e;
            throw new IllegalStateException("无法取得同步任务执行锁", e);
        }
    }

    /** 在锁内做条件收尾：获得锁意味着旧执行会话已经退出。保留断点和已上报行数。 */
    private void recover(Lease lease) {
        lease.check();
        try (PreparedStatement update = lease.connection.prepareStatement("UPDATE el_sync_runs SET status='failed',"
                + "finished_at=UTC_TIMESTAMP(),error='执行会话已结束或运行结果未能确认，请检查执行历史后继续同步'"
                + " WHERE task_id=? AND status='running'")) {
            update.setQueryTimeout(5); update.setLong(1, lease.taskId);
            int count = update.executeUpdate();
            if (count > 0) LOG.warn("event=sync.orphan_recovered task_id={} runs={}", lease.taskId, count);
        } catch (SQLException e) { throw new IllegalStateException("恢复同步运行状态失败", e); }
    }

    /** 定时补偿收尾写库失败及异常退出；数据库不可用时保留记录并报警，下次继续。 */
    @Scheduled(fixedDelay = 30000, initialDelay = 30000)
    public void recoverInterrupted() {
        final List<Long> tasks;
        try {
            tasks = jdbc.queryForList("SELECT DISTINCT task_id FROM el_sync_runs WHERE status='running'", Long.class);
        } catch (RuntimeException e) {
            LOG.error("event=sync.orphan_scan_failed", e);
            return;
        }
        for (Long task : tasks) {
            // 单个任务锁/状态更新失败不应阻断后续任务；失败任务留待下一轮重试。
            try (Lease lease = tryReserve(task)) {
                if (lease != null) recover(lease);
            } catch (RuntimeException e) {
                LOG.error("event=sync.orphan_recovery_failed task_id={}", task, e);
            }
        }
    }

    /** 会话只供一轮任务使用；check 在读写边界调用，失锁后禁止继续提交批次。 */
    public static class Lease implements AutoCloseable {
        private final Connection connection;
        private final long taskId;
        private boolean closed;
        Lease(Connection connection, long taskId) { this.connection = connection; this.taskId = taskId; }
        public synchronized void check() {
            if (closed) throw new IllegalStateException("同步执行锁已关闭");
            try (PreparedStatement query = connection.prepareStatement("SELECT IS_USED_LOCK(?)=CONNECTION_ID()")) {
                query.setQueryTimeout(5); query.setString(1, "el_sync_task_" + taskId);
                try (ResultSet rows = query.executeQuery()) {
                    if (!rows.next() || rows.getInt(1) != 1) throw new SQLException("Execution lock lost");
                }
            } catch (SQLException e) { throw new IllegalStateException("同步执行锁失效，已停止后续批次", e); }
        }
        @Override public synchronized void close() {
            if (closed) return;
            closed = true;
            MysqlNamedLock.releaseAndClose(connection, "el_sync_task_" + taskId);
        }
    }
}
