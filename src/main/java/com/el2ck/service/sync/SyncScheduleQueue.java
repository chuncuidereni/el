package com.el2ck.service.sync;

import com.el2ck.entity.sync.SyncRunProgress;
import com.el2ck.repository.metadata.MysqlNamedLock;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;
import java.sql.*;
import java.util.List;

/**
 * 应用内 Cron 的持久等待队列，复用 el_sync_runs 与 progress_json，无需新增表结构。
 * 同一任务最多保留一个 queued 请求；同一触发时刻跨实例幂等，后续到点在等待期间合并。
 * 排队不占同步执行名额，也不持有整轮任务锁。真正启动时仍由 SyncExecutionGuard 保护。
 */
@Component
public class SyncScheduleQueue {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final DataSource database;
    private final JdbcTemplate jdbc;
    private final com.el2ck.repository.metadata.TableChangeGate tableChangeGate;
    public SyncScheduleQueue(DataSource database, com.el2ck.repository.metadata.TableChangeGate tableChangeGate) {
        this.database=database; this.jdbc=new JdbcTemplate(database); this.jdbc.setQueryTimeout(5);
        this.tableChangeGate=tableChangeGate;
    }

    static final class Entry {
        final long runId,taskId;
        final String cron;
        Entry(long runId,long taskId,String cron) { this.runId=runId; this.taskId=taskId; this.cron=cron; }
    }

    /**
     * 排程回调只有在本方法确认持久化后才能前进到下一次 Cron。
     * 任务行锁使删除/修改与排队提交串行化；独立命名锁保证多实例不会建出两个等待请求。
     */
    public void enqueue(long taskId,String cron,long scheduledAtMs) {
        String lock="el_sync_queue_"+taskId;
        Connection c=null;
        try {
            c=database.getConnection();
            if(!MysqlNamedLock.tryAcquire(c,lock)) throw new IllegalStateException("定时请求正在入队，稍后重试");
            c.setAutoCommit(false);
            try(PreparedStatement task=c.prepareStatement("SELECT enabled,schedule_type,cron_expr FROM el_sync_tasks WHERE id=? FOR UPDATE")) {
                task.setQueryTimeout(5);task.setLong(1,taskId);
                try(ResultSet r=task.executeQuery()) {
                    if(!r.next() || !r.getBoolean(1) || !"scheduled".equals(r.getString(2))
                            || !cron.equals(SyncService.normalizeCronExpression(r.getString(3)))) { c.rollback();return; }
                }
            }
            // 旧配置的等待请求保留为失败历史，不能阻碍新的 Cron 入队。
            try(PreparedStatement stale=c.prepareStatement("UPDATE el_sync_runs SET status='failed',finished_at=UTC_TIMESTAMP(),"
                    +"error='调度配置已变更，已取消旧排队请求' WHERE task_id=? AND status='queued' "
                    +"AND NOT (JSON_UNQUOTE(JSON_EXTRACT(progress_json,'$.scheduledCron')) <=> ?)")) {
                stale.setQueryTimeout(5);stale.setLong(1,taskId);stale.setString(2,cron);stale.executeUpdate();
            }
            try(PreparedStatement existing=c.prepareStatement("SELECT id,status FROM el_sync_runs WHERE task_id=? AND "
                    +"(status='queued' OR (JSON_UNQUOTE(JSON_EXTRACT(progress_json,'$.scheduledCron'))=? "
                    +"AND CAST(JSON_UNQUOTE(JSON_EXTRACT(progress_json,'$.scheduledThroughMs')) AS UNSIGNED)>=?)) ORDER BY id DESC LIMIT 1 FOR UPDATE")) {
                existing.setQueryTimeout(5);existing.setLong(1,taskId);existing.setString(2,cron);existing.setLong(3,scheduledAtMs);
                try(ResultSet r=existing.executeQuery()) {
                    if(r.next()) {
                        if("queued".equals(r.getString(2))) {
                            try(PreparedStatement merge=c.prepareStatement("UPDATE el_sync_runs SET progress_json=JSON_SET(progress_json,"
                                    +"'$.scheduledThroughMs',GREATEST(CAST(JSON_UNQUOTE(JSON_EXTRACT(progress_json,'$.scheduledThroughMs')) AS UNSIGNED),?)) WHERE id=? AND status='queued'")) {
                                merge.setQueryTimeout(5);merge.setLong(1,scheduledAtMs);merge.setLong(2,r.getLong(1));merge.executeUpdate();
                            }
                        }
                        c.commit();return;
                    }
                }
            }
            SyncRunProgress progress=new SyncRunProgress();
            progress.setPhase("等待执行"); progress.setScheduledCron(cron);progress.setScheduledAtMs(scheduledAtMs);
            progress.setScheduledThroughMs(scheduledAtMs);
            progress.setQueuedAtMs(System.currentTimeMillis());progress.setUpdatedAtMs(System.currentTimeMillis());
            try(PreparedStatement insert=c.prepareStatement("INSERT INTO el_sync_runs(task_id,status,note,progress_json) VALUES(?,'queued',?,?)")) {
                insert.setQueryTimeout(5);insert.setLong(1,taskId);insert.setString(2,"定时任务已排队；同任务等待期间的触发合并，空位释放后自动执行");
                insert.setString(3,JSON.writeValueAsString(progress));insert.executeUpdate();
            }
            c.commit();
        } catch(Exception e) {
            if(c!=null) try { c.rollback(); } catch(SQLException rollback) { e.addSuppressed(rollback); }
            throw new IllegalStateException("保存定时排队请求失败",e);
        } finally {
            if(c!=null) try { c.setAutoCommit(true); } catch(SQLException ignored) { }
            MysqlNamedLock.releaseAndClose(c,lock);
        }
    }

    /** 固定本次扫描上界；持续新增请求不能让扫描永远不回到前面暂时忙碌的任务。 */
    long lastQueuedId() {
        return jdbc.queryForObject("SELECT COALESCE(MAX(id),0) FROM el_sync_runs WHERE status='queued'",Long.class);
    }

    /** 按运行号分批遍历本次范围，避免前 256 条一直失败时挡住后续任务；不使用 OFFSET。 */
    List<Entry> pending(long afterId,long throughId) {
        return jdbc.query("SELECT id,task_id,JSON_UNQUOTE(JSON_EXTRACT(progress_json,'$.scheduledCron')) FROM el_sync_runs "
                +"WHERE status='queued' AND id>? AND id<=? ORDER BY id LIMIT 256",
                (r,n)->new Entry(r.getLong(1),r.getLong(2),r.getString(3)),afterId,throughId);
    }

    boolean hasQueued(long taskId) {
        return !jdbc.queryForList("SELECT id FROM el_sync_runs WHERE task_id=? AND status='queued' LIMIT 1",Long.class,taskId).isEmpty();
    }

    /** 调用方已取得任务锁和执行名额；状态条件是重复派发的最后一道守卫。 */
    boolean claim(long runId,long taskId) {
        try (com.el2ck.repository.metadata.TableChangeGate.Lease gate = tableChangeGate.tryAcquire()) {
            if (gate == null) return false;
            gate.check();
            return jdbc.update("UPDATE el_sync_runs SET status='running',started_at=UTC_TIMESTAMP(),note='定时排队任务开始执行',"
                    +"progress_json=JSON_SET(progress_json,'$.phase','准备同步') WHERE id=? AND task_id=? AND status='queued'",runId,taskId)==1;
        }
    }

    void fail(long runId,String reason) {
        jdbc.update("UPDATE el_sync_runs SET status='failed',finished_at=UTC_TIMESTAMP(),error=?,"
                +"progress_json=JSON_SET(COALESCE(progress_json,'{}'),'$.phase','排队已结束') WHERE id=? AND status='queued'",reason,runId);
    }

    boolean cancel(long taskId,String reason) {
        return jdbc.update("UPDATE el_sync_runs SET status='failed',finished_at=UTC_TIMESTAMP(),error=?,"
                +"progress_json=JSON_SET(COALESCE(progress_json,'{}'),'$.phase','排队已取消') WHERE task_id=? AND status='queued'",reason,taskId)>0;
    }
}
