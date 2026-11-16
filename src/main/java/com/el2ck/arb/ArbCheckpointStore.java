package com.el2ck.arb;

/**
 * 【文件职责】持久化 ARB 工作流断点，并用 MySQL 命名锁协调同一工作流的恢复与执行，避免多个实例同时推进同一状态机。
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.el2ck.repository.metadata.MysqlNamedLock;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

/** 每个工作流一份持久日志；锁覆盖一次执行的所有重试，手动和定时共用。 */
@Component
public class ArbCheckpointStore {
    // 每个 ARB 工作流使用持久日志和执行锁；状态与 token 必须先落库，发布后的清理失败不能反向改写已提交状态。
    private static final ObjectMapper JSON = new ObjectMapper();
    private final DataSource database;

    public ArbCheckpointStore(DataSource database) { this.database = database; }

    public static class State {
        public String token = UUID.randomUUID().toString().replace("-", "");
        public String fingerprint, action, database, aggregateTarget, serviceTarget, aggregateRevision, phase = "snapshot", lastKey, pendingKey;
        public int sourceIndex, batch, retries;
        public long rows, startedAt = System.currentTimeMillis();
        public boolean complete, snapshotsReady, refreshNeeded;
        public Map<String, String> tables = new LinkedHashMap<>();
        public Map<String, String> oldTargets = new LinkedHashMap<>();
    }


    public static String lockName(long id) { return "el_arb_workflow_" + id; }


/**
 * 查询指定工作流当前是否被其他执行会话占用。
 *
 * <p>该方法只读取 MySQL 命名锁状态，不会尝试获取锁；锁名由工作流 ID 生成，
 * 因而不同实例看到的是同一把数据库级锁。查询使用独立连接，并在返回前关闭。
 *
 * @param id 工作流 ID，用于生成对应的命名锁
 * @return 命名锁已被任意连接持有时返回 {@code true}，否则返回 {@code false}
 * @throws IllegalStateException 数据库连接或锁状态查询失败时抛出
 */
    public boolean busy(long id) {
        try (Connection c = database.getConnection(); PreparedStatement p = c.prepareStatement("SELECT IS_USED_LOCK(?)")) {
            p.setString(1, lockName(id));
            try (ResultSet r = p.executeQuery()) { return r.next() && r.getObject(1) != null; }
        } catch (SQLException e) { throw new IllegalStateException("查询工作流执行锁失败", e); }
    }

/** 按工作流 ID 读取已持久化状态；记录不存在时返回 {@code null}。 */
    public State read(long id) {
        try (Connection c = database.getConnection()) { return read(c, id); }
        catch (Exception e) { throw new IllegalStateException("读取工作流断点失败", e); }
    }

    private State read(Connection c, long id) throws Exception {
        try (PreparedStatement p = c.prepareStatement("SELECT state_json FROM el_arb_checkpoints WHERE workflow_id=?")) {
            p.setLong(1, id);
            try (ResultSet r = p.executeQuery()) { return r.next() ? JSON.readValue(r.getString(1), State.class) : null; }
        }
    }


    public Session open(long id) {
        Connection c = null;
        try {
            c = database.getConnection();
            if (!MysqlNamedLock.tryAcquire(c, lockName(id))) throw new BusyException();
            return new Session(c, id, read(c, id));
        } catch (Exception | Error e) {
            // 获取锁的响应丢失时也可能已持锁，不能只根据本地 acquired 标记决定是否释放。
            MysqlNamedLock.releaseAndClose(c, lockName(id));
            if (e instanceof Error) throw (Error)e;
            if (e instanceof BusyException) throw (BusyException)e;
            throw new IllegalStateException("打开工作流断点失败", e);
        }
    }

    public static class BusyException extends IllegalStateException {
        public BusyException() { super("该工作流正在执行，本次重复触发已跳过"); }
    }

    public final class Session implements AutoCloseable {
        private final Connection connection;
        private final long id;
        private boolean exists;
        private volatile boolean closed;
        public State state;
        private Session(Connection c, long id, State state) {
            this.connection = c; this.id = id; this.state = state; this.exists = state != null;
        }
/**
 * 校验 {@code checkLock} 所涉及的前置条件，失败时阻止后续读写以避免产生不一致状态。
 */
        public void checkLock() {
            if (closed) throw new IllegalStateException("工作流断点会话已关闭");
            if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("工作流已中断，保留断点");
            try (PreparedStatement p = connection.prepareStatement("SELECT IS_USED_LOCK(?)=CONNECTION_ID()")) {
                p.setQueryTimeout(5);
                p.setString(1, lockName(id));
                try (ResultSet r = p.executeQuery()) {
                    if (!r.next() || r.getInt(1) != 1) throw new IllegalStateException("工作流执行锁失效，停止写入");
                }
            } catch (SQLException e) { throw new IllegalStateException("工作流执行锁断开，保留断点", e); }
        }

        public void reload() {
            checkLock();
            try { state = read(connection, id); exists = state != null; }
            catch (Exception e) { throw new IllegalStateException("重新读取工作流断点失败", e); }
        }

        public void save() {
            checkLock();
            String sql = exists ? "UPDATE el_arb_checkpoints SET state_json=?,updated_at=UTC_TIMESTAMP() WHERE workflow_id=?"
                    : "INSERT INTO el_arb_checkpoints(state_json,workflow_id) VALUES(?,?)";
            try (PreparedStatement p = connection.prepareStatement(sql)) {
                p.setString(1, JSON.writeValueAsString(state)); p.setLong(2, id);
                p.executeUpdate(); exists = true;
            } catch (Exception e) { throw new IllegalStateException("保存工作流断点失败，停止后续批次", e); }
        }

/**
 * 在仍持有当前会话命名锁时删除本工作流的持久化断点。
 *
 * <p>删除前通过 {@link #checkLock()} 验证锁仍属于当前连接，避免失去执行权后误删
 * 其他执行轮次的断点。删除成功后清空本地 {@code state} 和 {@code exists}；记录原本
 * 不存在时 DELETE 不影响行数，也会完成本地状态清理。Session 的连接和锁仍需由
 * {@link #close()} 单独释放。
 *
 * @throws IllegalStateException 线程已中断、命名锁已失效或数据库删除失败时抛出
 */
        public void delete() {
            checkLock();
            try (PreparedStatement p = connection.prepareStatement("DELETE FROM el_arb_checkpoints WHERE workflow_id=?")) {
                p.setLong(1, id); p.executeUpdate(); state = null; exists = false;
            } catch (SQLException e) { throw new IllegalStateException("清理工作流断点失败", e); }
        }
/**
 * 释放当前工作流的 MySQL 命名锁并关闭会话连接。
 *
 * <p>重复关闭不会再次操作已归还连接池的连接。释放失败时终止物理会话并记录错误；
 * 此方法只结束执行会话，不会删除已保存的断点。
 */
        @Override public synchronized void close() {
            if (closed) return;
            closed = true;
            MysqlNamedLock.releaseAndClose(connection, lockName(id));
        }
    }
}
