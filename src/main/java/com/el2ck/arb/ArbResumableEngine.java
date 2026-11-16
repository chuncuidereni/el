package com.el2ck.arb;

/**
 * 【文件职责】实现 ARB 可恢复状态机，按 snapshot、backfill、service view、publish 和 cleanup 阶段持久化进度，从已持久化阶段和游标继续执行，并在发布阶段校验对象 UUID。
 */

import com.el2ck.ckpool.CkClient;
import com.el2ck.ckpool.CkConfig;
import com.el2ck.entity.arb.*;
import com.el2ck.service.ck.CkIdentityService;
import com.el2ck.service.connection.ConnectionService;
import com.el2ck.sync.SyncCheckpointStore;
import com.el2ck.sync.SyncRetry;
import com.el2ck.util.Sql;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/** 固定快照 → 分段回填 → 分段摊平 → UUID 核对后发布。正式表只在发布阶段改变。 */
@Component
public class ArbResumableEngine {
    // 状态链固定为 snapshot -> backfill -> service -> agg_publish -> view -> svc_publish -> restore_views -> complete。
    // 正式表只在发布阶段改变；发布前后用 UUID 验证对象身份，临时对象仅按本轮 token 和日志记录清理。
    private static final Logger LOG = LoggerFactory.getLogger(ArbResumableEngine.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ArbCheckpointStore store;
    private final ConnectionService connections;
    private final CkIdentityService identities;
    private final ThreadLocal<ArbCheckpointStore.Session> execution = new ThreadLocal<>();
    @Value("${app.arb-resume.batch-keys:100000}") private int batchKeys = 100000;
    @Value("${app.arb-resume.statement-timeout-seconds:1800}") private int timeoutSeconds = 1800;
    @Value("${app.arb-resume.max-retries:5}") private int maxRetries = 5;


    public ArbResumableEngine(ArbCheckpointStore store, ConnectionService connections, CkIdentityService identities) {
        this.store = store; this.connections = connections; this.identities = identities;
    }

    /** 先持有执行锁再创建运行记录；锁可随后台工作移交到工作线程。 */
    public Lease reserve(long workflowId) { return new Lease(store.open(workflowId), true); }
    private Lease borrow(long workflowId) {
        ArbCheckpointStore.Session current = execution.get();
        return current == null ? reserve(workflowId) : new Lease(current, false);
    }
    public final class Lease implements AutoCloseable {
        private final ArbCheckpointStore.Session session;
        private final boolean owned;
        private Lease(ArbCheckpointStore.Session session, boolean owned) { this.session = session; this.owned = owned; }
        public <T> T call(java.util.function.Supplier<T> action) {
            ArbCheckpointStore.Session previous = execution.get();
            execution.set(session);
            try { return action.get(); }
            finally { if (previous == null) execution.remove(); else execution.set(previous); }
        }
        @Override public void close() { if (owned) session.close(); }
    }


    public String pendingAction(long id) {
        ArbCheckpointStore.State s = store.read(id);
        return s == null ? "" : s.complete ? (s.refreshNeeded ? "flatten" : "") : s.action;
    }

/**
 * 查询指定工作流的数据库级执行锁是否被持有。
 *
 * <p>锁按工作流 ID 命名，因此可跨实例判断是否已有执行者；本方法只查询，不参与
 * 锁竞争，也不会改变断点状态。
 *
 * @param id 工作流 ID
 * @return 锁被任意数据库连接持有时返回 {@code true}
 * @throws IllegalStateException 锁状态查询失败时抛出
 */
    public boolean busy(long id) { return store.busy(id); }

/**
 * 返回尚未完成轮次的动作标识。
 *
 * <p>持久化断点不存在或已完成时返回空串；未完成时返回该轮次保存的
 * {@code apply}/{@code flatten} 动作，供恢复入口优先沿用。
 *
 * @param id 工作流 ID
 * @return 未完成动作或空串
 * @throws IllegalStateException 读取持久化断点失败时抛出
 */
    public String incompleteAction(long id) {
        ArbCheckpointStore.State s = store.read(id);
        return s == null || s.complete ? "" : s.action;
    }


/**
 * 格式化当前断点阶段和已确认进度。
 *
 * <p>返回值包含中文阶段名、已确认批次、当前阶段行数、自动重试次数和基于
 * {@code startedAt} 计算的本轮耗时；未知阶段保留其原始标识。没有断点时返回空串。
 * 该方法只读取状态，不推进状态机。
 *
 * @param id 工作流 ID
 * @return 可读进度摘要或空串
 * @throws IllegalStateException 读取持久化断点失败时抛出
 */
    public String progress(long id) {
        ArbCheckpointStore.State s = store.read(id);
        if (s == null) return "";
        String phase;
        switch (s.phase) {
            case "snapshot": phase = "固定数据快照"; break;
            case "backfill": phase = "回填标准源"; break;
            case "service": phase = "摊平服务表"; break;
            case "agg_publish": phase = "发布聚合表"; break;
            case "view": case "svc_publish": phase = "发布服务层"; break;
            case "restore_views": phase = "恢复物化视图"; break;
            case "complete": phase = "完成"; break;
            default: phase = s.phase;
        }
        return "断点阶段=" + phase + "，已确认批次=" + s.batch + "，当前阶段已确认行数=" + s.rows
                + "，自动重试=" + s.retries + "，本轮总耗时=" + (System.currentTimeMillis() - s.startedAt) / 1000 + "秒";
    }


/**
 * 持有工作流命名锁后修改或删除配置。
 *
 * <p>未完成断点只能在不改变配置指纹时继续修改，否则抛出异常要求先续传或清理；
 * 已完成断点会先清理旧临时对象和断点。{@code edit} 在锁内执行，{@code next == null}
 * 时回调结束后再次删除断点。方法不创建事务，回调异常会向调用方传播。
 *
 * @param old 修改前工作流，用于锁定并清理旧状态
 * @param next 修改后工作流；为 {@code null} 表示删除
 * @param edit 配置写入或删除回调
 * @throws ArbCheckpointStore.BusyException 工作流已有执行者时抛出
 * @throws IllegalStateException 断点配置不兼容、锁失效或清理失败时抛出
 * @throws RuntimeException {@code edit} 抛出的异常原样传播
 */
    public void editConfiguration(ArbWorkflow old, ArbWorkflow next, Runnable edit) {
        try (ArbCheckpointStore.Session j = store.open(old.getId())) {
            if (j.state != null && !j.state.complete) {
                if (next == null || !j.state.fingerprint.equals(fingerprint(next,
                        identities.workflowManagementConfig(next.getConnectionId()))))
                    throw new IllegalStateException("工作流还有未完成的断点，请续传完成或先清理已生成的数据；可以暂时停用工作流");
            }
            if (j.state != null && j.state.complete) {
                try (CkClient c = open(identities.workflowManagementConfig(old.getConnectionId()))) { cleanup(c, j, old); }
                j.delete();
            }
            edit.run();
            if (next == null) j.delete();
        }
    }


/**
 * 执行或恢复一个 ARB 工作流，并返回本轮统计。
 *
 * <p>方法先校验计划与连接可写性，再取得工作流级命名锁。没有断点时创建新轮次；
 * 存在未完成断点时只有配置指纹一致才继续，并从已持久化阶段和游标恢复。状态机按
 * snapshot、backfill、service、publish、view restore 和 cleanup 阶段保存断点。
 * 临时故障会在本轮 token 不变的前提下按配置重试；重试前重新读取持久化状态，
 * 避免把另一轮状态混入当前执行。非临时异常、中断或重试耗尽会恢复物化视图后抛出。
 * 数据完成后若临时对象清理失败，只记录警告并仍返回成功统计，待下次触发补清理。
 *
 * @param w 要执行的工作流定义，配置需能生成有效计划且连接不能为只读
 * @param flatten 新轮次为 {@code true} 时从摊平入口执行，否则从标准源应用入口执行
 * @param stopMvs 在 apply 恢复阶段停止相关物化视图的回调；其 SQL 执行器会校验断点锁
 * @return 包含行数、耗时和进度摘要的本轮统计
 * @throws ArbCheckpointStore.BusyException 同一工作流已由其他执行持有锁时抛出
 * @throws IllegalStateException 连接只读、计划无效、断点指纹冲突、锁失效或执行状态不一致时抛出
 * @throws RuntimeException ClickHouse 执行失败且不可重试、线程中断或重试耗尽时抛出
 */
    public ArbStats run(ArbWorkflow w, boolean flatten, BiConsumer<CkClient, java.util.function.Consumer<String>> stopMvs) {
        ArbPlanBuilder.buildPlan(w);
        if (connections.effectiveConfig(w.getConnectionId()).isReadOnly()) throw new IllegalStateException("当前连接为只读模式");
        CkConfig config = identities.workflowManagementConfig(w.getConnectionId());
        String fingerprint = fingerprint(w, config);
        try (Lease lease = borrow(w.getId())) {
            ArbCheckpointStore.Session journal = lease.session;
            if (journal.state == null || journal.state.complete) {
                if (journal.state != null) {
                    try (CkClient c = open(config)) { cleanup(c, journal, w); }
                }
                journal.state = new ArbCheckpointStore.State();
                journal.state.fingerprint = fingerprint;
                journal.state.action = flatten ? "flatten" : "apply";
                journal.state.database = w.getTargetDb();
                journal.state.aggregateTarget = w.aggTableName();
                journal.state.serviceTarget = w.svcTableName();
                journal.save();
            } else if (!fingerprint.equals(journal.state.fingerprint)) {
                throw new IllegalStateException("工作流配置或连接已变化，不能混用原断点；请还原配置续传，或清理已生成的数据后重建");
            }
            // 不管本次入口叫什么，未完成轮次的 action 始终优先。
            String token = journal.state.token;
            for (int attempt = 0; ; attempt++) {
                try (CkClient c = open(config)) {
                    journal.checkLock();
                    kill(c, token);
                    execute(c, journal, w, stopMvs);
                    ArbStats stats = new ArbStats();
                    stats.setRows(journal.state.rows);
                    stats.setElapsedMs(System.currentTimeMillis() - journal.state.startedAt);
                    stats.setProgress(progress(w.getId()));
                    // 数据已提交，清理失败不能把成功改成失败；下次触发先补清理。
                    try { cleanup(c, journal, w); }
                    catch (RuntimeException e) { LOG.warn("工作流 {} 已完成，临时表待清理: {}", w.getId(), e.getMessage()); }
                    return stats;
                } catch (RuntimeException e) {
                    try (CkClient recovery = open(config)) { restoreMvs(recovery, journal, w); }
                    catch (RuntimeException restore) { e.addSuppressed(restore); }
                    if (attempt >= Math.max(0, maxRetries) || !SyncRetry.transientFailure(e)
                            || Thread.currentThread().isInterrupted()) throw e;
                    // 只相信已持久化的游标；保存响应丢失时也不会多算一段或新开一轮。
                    journal.reload();
                    if (journal.state == null || !token.equals(journal.state.token)) throw new IllegalStateException("工作流断点轮次已变化", e);
                    journal.state.retries++;
                    journal.save();
                    LOG.warn("工作流 {} 连接中断，将从断点自动重试 {}/{}", w.getId(), attempt + 1, maxRetries);
                    SyncRetry.pause(Math.min(30000L, 2000L << Math.min(attempt, 4)));
                }
            }
        }
    }

    private CkClient open(CkConfig original) {
        CkConfig cfg = original.copy();
        cfg.setQueryTimeoutMs(Math.max(60, timeoutSeconds) * 1000L);
        cfg.setConnMaxLifetimeMs(Long.MAX_VALUE);
        cfg.getSettings().put("max_result_rows", 0);
        cfg.getSettings().put("result_overflow_mode", "throw");
        cfg.getSettings().put("timeout_overflow_mode", "throw");
        cfg.getSettings().put("group_by_overflow_mode", "throw");
        cfg.getSettings().put("async_insert", 0);
        return CkClient.open(cfg);
    }

    private void execute(CkClient c, ArbCheckpointStore.Session j, ArbWorkflow w,
                         BiConsumer<CkClient, java.util.function.Consumer<String>> stopMvs) {
        ArbCheckpointStore.State s = j.state;
        if (s.complete) return;
        boolean apply = "apply".equals(s.action);
        List<ArbSource> sources = w.getSources().stream().filter(ArbSource::isEnabled).collect(Collectors.toList());
        String agg = name(s, "agg"), svc = name(s, "svc"), scratch = name(s, "scratch");
        write(c, j, "CREATE DATABASE IF NOT EXISTS " + q(w.getTargetDb()));
        String dbEngine = scalar(c, "SELECT engine FROM system.databases WHERE name=" + lit(w.getTargetDb()));
        if (!"Atomic".equals(dbEngine)) throw new IllegalStateException("工作流续传要求目标库使用 Atomic 引擎");
        if (apply && Arrays.asList("snapshot", "backfill", "service", "agg_publish").contains(s.phase)) {
            j.checkLock(); stopMvs.accept(c, sql -> write(c, j, sql));
        }
        if ("snapshot".equals(s.phase)) {
            if (apply) {
                ensureTable(c, j, w, agg, ArbPlanBuilder.buildAggDdl(w, agg, true));
                for (int i = 0; i < sources.size(); i++) {
                    ArbSource src = sources.get(i);
                    snapshot(c, j, w, src.getDatabase(), src.getTable(), name(s, "source" + i), false);
                }
            } else {
                if (s.aggregateRevision == null) { s.aggregateRevision = revision(c, w, w.aggTableName()); j.save(); }
                snapshot(c, j, w, w.getTargetDb(), w.aggTableName(), name(s, "frozen_agg"), true);
            }
            s.snapshotsReady = true;
            s.phase = apply ? "backfill" : "service"; j.save();
        }
        if ("backfill".equals(s.phase)) {
            requireTable(c, j, w, agg);
            ensureTable(c, j, w, scratch, ArbPlanBuilder.buildAggDdl(w, scratch, true));
            while (s.sourceIndex < sources.size()) {
                ArbSource src = sources.get(s.sourceIndex);
                String frozen = name(s, "source" + s.sourceIndex);
                requireTable(c, j, w, frozen);
                List<Object> boundary = row(c, ArbPlanBuilder.sourceBoundary(src, w.getTargetDb(), frozen, s.lastKey, size()));
                long count = number(boundary.get(0));
                if (count == 0) { s.sourceIndex++; s.lastKey = null; j.save(); continue; }
                String upper = String.valueOf(boundary.get(1));
                String batch = "b" + s.batch;
                write(c, j, "TRUNCATE TABLE " + fq(w, scratch));
                write(c, j, ArbPlanBuilder.sourceBatch(w, src, frozen, scratch, s.lastKey, upper, batch));
                // 相同批次重复 REPLACE 而不是 INSERT；sum 状态不会重复累加。
                write(c, j, "ALTER TABLE " + fq(w, agg) + " REPLACE PARTITION " + lit(batch) + " FROM " + fq(w, scratch));
                s.lastKey = upper; s.batch++; s.rows += count; j.save();
            }
            s.phase = "service"; s.lastKey = null; s.rows = 0; j.save();
        }
        if ("service".equals(s.phase)) {
            String input = apply ? agg : name(s, "frozen_agg");
            requireTable(c, j, w, input);
            if (apply && s.aggregateRevision == null) { s.aggregateRevision = revision(c, w, input); j.save(); }
            if (ArbPlanBuilder.workflowHasSum(w)) {
                String ddl = ArbPlanBuilder.buildSvcDdl(w).replace(fq(w, w.svcTableName()), fq(w, svc));
                ensureTable(c, j, w, svc, ddl);
                if (s.pendingKey != null) {
                    write(c, j, "ALTER TABLE " + fq(w, svc) + " DELETE WHERE "
                            + ArbPlanBuilder.keyRange(q("mdn"), s.lastKey, s.pendingKey) + " SETTINGS mutations_sync=2");
                    s.pendingKey = null; j.save();
                }
                String columns = ArbPlanBuilder.buildSvcColumns(w).stream().map(ArbResumableEngine::q).collect(Collectors.joining(","));
                while (true) {
                    String range = s.lastKey == null ? "1" : q("mdn") + " > " + lit(s.lastKey);
                    List<Object> boundary = row(c, "SELECT uniqExact(k), max(k) FROM (SELECT mdn AS k FROM " + fq(w, input)
                            + " WHERE " + range + " ORDER BY k LIMIT " + size() + ")");
                    long count = number(boundary.get(0));
                    if (count == 0) break;
                    s.pendingKey = String.valueOf(boundary.get(1)); j.save();
                    String select = ArbPlanBuilder.buildSvcSelect(w, input,
                            ArbPlanBuilder.keyRange("`src`.`mdn`", s.lastKey, s.pendingKey));
                    write(c, j, "INSERT INTO " + fq(w, svc) + " (" + columns + ") " + select);
                    s.lastKey = s.pendingKey; s.pendingKey = null; s.rows += count; s.batch++; j.save();
                }
            }
            s.phase = apply ? "agg_publish" : "view"; j.save();
        }
        if ("agg_publish".equals(s.phase)) {
            publish(c, j, w, agg, w.aggTableName());
            s.phase = "view"; j.save();
        }
        if ("view".equals(s.phase)) {
            if (!ArbPlanBuilder.workflowHasSum(w)) {
                ensureTable(c, j, w, svc, ArbPlanBuilder.buildSvcViewDdl(w, svc));
            }
            s.phase = "svc_publish"; j.save();
        }
        if ("svc_publish".equals(s.phase)) {
            publish(c, j, w, svc, w.svcTableName());
            s.phase = "restore_views"; j.save();
        }
        if ("restore_views".equals(s.phase)) {
            if (apply) restoreMvs(c, j, w);
            // 无 sum 的服务层是实时视图；统计失败不能重新执行已经发布的数据。
            if (!ArbPlanBuilder.workflowHasSum(w)) {
                try { s.rows = number(scalar(c, "SELECT count() FROM " + fq(w, w.svcTableName()))); }
                catch (RuntimeException e) { LOG.warn("工作流 {} 已发布，未能读取行数", w.getId()); }
            }
            s.refreshNeeded = ArbPlanBuilder.workflowHasSum(w) && !Objects.equals(s.aggregateRevision, revision(c, w, w.aggTableName()));
            s.complete = true; s.phase = "complete"; j.save();
        }
    }

    private void snapshot(CkClient c, ArbCheckpointStore.Session j, ArbWorkflow w,
                          String db, String table, String snapshot, boolean aggregate) {
        if (j.state.tables.containsKey(snapshot) && !j.state.tables.get(snapshot).isEmpty()) { requireTable(c, j, w, snapshot); return; }
        List<Object> meta = row(c, "SELECT engine, create_table_query FROM system.tables WHERE database=" + lit(db) + " AND name=" + lit(table));
        String engine = String.valueOf(meta.get(0));
        if (!("MergeTree".equals(engine) || "ReplicatedMergeTree".equals(engine)
                || (aggregate && "AggregatingMergeTree".equals(engine)))) {
            throw new IllegalStateException("源表 " + db + "." + table + " 的引擎 " + engine + " 不支持固定快照续传");
        }
        if (String.valueOf(meta.get(1)).matches("(?is).*\\bTTL\\b.*")) {
            throw new IllegalStateException("源表包含 TTL，不能保证本轮快照不变，请使用无 TTL 的标准源表");
        }
        // 未确认的 ATTACH 可能只复制了一部分，先删掉本轮专用快照再复制；尚未消费它的任何数据。
        j.state.tables.put(snapshot, ""); j.save();
        write(c, j, "DROP TABLE IF EXISTS " + fq(w, snapshot));
        write(c, j, "CREATE TABLE " + fq(w, snapshot) + " AS " + q(db) + "." + q(table) + " ENGINE = MergeTree");
        write(c, j, "ALTER TABLE " + fq(w, snapshot) + " ATTACH PARTITION ALL FROM " + q(db) + "." + q(table));
        j.state.tables.put(snapshot, uuid(c, w.getTargetDb(), snapshot)); j.save();
    }

    private void ensureTable(CkClient c, ArbCheckpointStore.Session j, ArbWorkflow w, String name, String ddl) {
        if (j.state.tables.containsKey(name) && !j.state.tables.get(name).isEmpty()) { requireTable(c, j, w, name); return; }
        // 创建应答丢失的对象属于本轮随机命名空间；还没有任何已确认数据。
        j.state.tables.put(name, ""); j.save();
        write(c, j, "DROP TABLE IF EXISTS " + fq(w, name));
        write(c, j, ddl);
        j.state.tables.put(name, uuid(c, w.getTargetDb(), name)); j.save();
    }

    private void requireTable(CkClient c, ArbCheckpointStore.Session j, ArbWorkflow w, String name) {
        String expected = j.state.tables.get(name);
        if (expected == null || !expected.equals(uuid(c, w.getTargetDb(), name)))
            throw new IllegalStateException("断点依赖的临时表丢失或已被替换，请清理本轮产物后重建: " + name);
    }

    private void publish(CkClient c, ArbCheckpointStore.Session j, ArbWorkflow w, String stage, String target) {
        String expected = j.state.tables.get(stage), current = uuid(c, w.getTargetDb(), target);
        if (expected == null) throw new IllegalStateException("缺少发布表身份，停止换表");
        if (expected.equals(current)) return; // EXCHANGE/RENAME 已提交但应答/保存断点失败。
        requireTable(c, j, w, stage);
        if (!j.state.oldTargets.containsKey(target)) { j.state.oldTargets.put(target, current); j.save(); }
        if (!Objects.equals(current, j.state.oldTargets.get(target))) throw new IllegalStateException("目标表已被其他操作替换，停止发布");
        write(c, j, current.isEmpty() ? "RENAME TABLE " + fq(w, stage) + " TO " + fq(w, target)
                : "EXCHANGE TABLES " + fq(w, target) + " AND " + fq(w, stage));
        if (!expected.equals(uuid(c, w.getTargetDb(), target))) throw new IllegalStateException("发布后的表身份未确认");
    }

    private void restoreMvs(CkClient c, ArbCheckpointStore.Session j, ArbWorkflow w) {
        if (!"apply".equals(j.state.action) || uuid(c, w.getTargetDb(), w.aggTableName()).isEmpty()) return;
        for (ArbSource source : w.getSources()) if (source.isEnabled()) {
            write(c, j, ArbPlanBuilder.buildMvSql(w, source).replace("CREATE MATERIALIZED VIEW ", "CREATE MATERIALIZED VIEW IF NOT EXISTS "));
        }
    }

    /** 只删除日志中属于本轮的临时对象，绝不按通配前缀删除。 */
    private void cleanup(CkClient c, ArbCheckpointStore.Session j, ArbWorkflow w) {
        for (Map.Entry<String, String> table : j.state.tables.entrySet()) {
            String actual = uuid(c, j.state.database, table.getKey());
            if (actual.isEmpty()) continue;
            if (!table.getKey().startsWith("__elarb_" + j.state.token + "_")) throw new IllegalStateException("断点临时表名称非法");
            boolean ours = table.getValue().isEmpty() || actual.equals(table.getValue());
            if (table.getKey().equals(name(j.state, "agg"))) ours |= actual.equals(j.state.oldTargets.get(j.state.aggregateTarget));
            if (table.getKey().equals(name(j.state, "svc"))) ours |= actual.equals(j.state.oldTargets.get(j.state.serviceTarget));
            if (!ours) throw new IllegalStateException("临时表身份已变化，拒绝清理: " + table.getKey());
            write(c, j, "DROP TABLE " + q(j.state.database) + "." + q(table.getKey()));
        }
    }


    public void clear(ArbWorkflow w, Runnable dropObjects) {
        if (connections.effectiveConfig(w.getConnectionId()).isReadOnly()) throw new IllegalStateException("当前连接为只读模式");
        try (ArbCheckpointStore.Session j = store.open(w.getId())) {
            if (j.state != null) {
                try (CkClient c = open(identities.workflowManagementConfig(w.getConnectionId()))) {
                    kill(c, j.state.token); cleanup(c, j, w);
                }
            }
            dropObjects.run();
            j.delete();
        }
    }

    private static void kill(CkClient c, String token) {
        c.exec("KILL QUERY WHERE startsWith(query," + lit("/*elarb_" + token + "*/") + ") AND query_id != currentQueryID() SYNC");
    }
    private static void write(CkClient c, ArbCheckpointStore.Session j, String sql) {
        j.checkLock(); c.exec("/*elarb_" + j.state.token + "*/ " + sql); j.checkLock();
    }
    private int size() { return Math.max(1000, Math.min(1000000, batchKeys)); }
    private static String name(ArbCheckpointStore.State s, String suffix) { return "__elarb_" + s.token + "_" + suffix; }
    private static String q(String name) { return Sql.quoteCol(name); }
    private static String lit(String text) { return Sql.quoteString(text); }
    private static String fq(ArbWorkflow w, String table) { return q(w.getTargetDb()) + "." + q(table); }
    private static long number(Object value) { return Long.parseLong(String.valueOf(value)); }
    private static List<Object> row(CkClient c, String sql) {
        List<List<Object>> rows = c.query(sql).getRows();
        if (rows.isEmpty()) throw new IllegalStateException("未找到工作流依赖的表或查询结果");
        return rows.get(0);
    }
    private static String scalar(CkClient c, String sql) {
        List<List<Object>> rows = c.query(sql).getRows();
        return rows.isEmpty() ? "" : String.valueOf(rows.get(0).get(0));
    }
    private static String uuid(CkClient c, String db, String table) {
        return scalar(c, "SELECT toString(uuid) FROM system.tables WHERE database=" + lit(db) + " AND name=" + lit(table));
    }
    private static String revision(CkClient c, ArbWorkflow w, String table) {
        return scalar(c, "SELECT toString(groupBitXor(cityHash64(name,toString(rows),toString(bytes_on_disk)))) FROM system.parts"
                + " WHERE active AND database=" + lit(w.getTargetDb()) + " AND table=" + lit(table));
    }
    private static String fingerprint(ArbWorkflow w, CkConfig c) {
        try {
            return SyncCheckpointStore.hash("arb-resume-v1|" + c.addr() + "|" + c.getProtocol() + "|" + c.getUsername()
                    + "|" + w.getConnectionId() + "|" + w.getTargetDb() + "|" + w.getTargetTable() + "|"
                    + JSON.writeValueAsString(w.getSources()) + "|" + JSON.writeValueAsString(w.getFields()) + "|" + JSON.writeValueAsString(w.getColumns()));
        } catch (Exception e) { throw new IllegalStateException("计算工作流配置指纹失败", e); }
    }
}
