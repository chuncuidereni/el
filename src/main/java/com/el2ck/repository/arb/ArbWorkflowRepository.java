package com.el2ck.repository.arb;

/**
 * 【文件职责】持久化 ARB 工作流定义、运行记录和运行结果，并封装工作流查询与状态更新 SQL。
 */

import com.el2ck.repository.queryworkflow.QueryWorkflowRepository;

import com.el2ck.entity.arb.ArbWorkflow;
import com.el2ck.entity.arb.ArbWorkflowConfig;
import com.el2ck.entity.arb.ArbWorkflowRun;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 多源仲裁工作流与运行历史的持久层(arb_workflows + arb_workflow_runs),
 * 对应 Go {@code internal/store/arb_workflow_store.go}。
 *
 * <p>只写 SQL、不做业务校验(名称唯一、目标表唯一、配置能否生成 SQL 都在
 * {@link com.el2ck.service.arb.ArbWorkflowService});涉及多条语句的操作手写事务,
 * 与 {@link QueryWorkflowRepository} 的约定一致。
 *
 * <p>配置(sources/fields/columns)整体序列化成 config_json 一列 —— 这三块是【一个整体】,
 * 拆成多表反而要处理「半保存」的中间状态。
 */
@Repository
public class ArbWorkflowRepository {
    @org.springframework.beans.factory.annotation.Autowired
    private com.el2ck.repository.metadata.TableChangeGate tableChangeGate;
    @org.springframework.beans.factory.annotation.Autowired
    private com.el2ck.service.auth.ResourceAccessService access;

    private static final Logger LOG = LoggerFactory.getLogger(ArbWorkflowRepository.class);

    /**
     * 配置列的 JSON 编解码器。
     *
     * <p>⚠️ 必须关闭 FAIL_ON_UNKNOWN_PROPERTIES:配置来自历史版本或 Go 端,多出一个未知字段
     * 就让整条记录读不出来(表现为列表里凭空少一条),比忽略它危险得多。
     */
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * 列表上限(前端无分页控件)。
     *
     * <p>加 LIMIT 的理由:配置行会随使用无限增长,而在没有 LIMIT 的 {@code ORDER BY id DESC}
     * 上,历史越久查询越慢。与 Go 的 maxArbWorkflowList 一致。
     */
    private static final int MAX_LIST = 500;

    /** 运行历史的默认条数(接口不传 limit 时)。 */
    private static final int DEFAULT_RUN_LIMIT = 20;

    /** 运行历史的条数上限。上限必须卡住:记录里存着整段 SQL,放开等于让一个请求把大字段全捞出来。 */
    private static final int MAX_RUN_LIMIT = 200;

    /**
     * 判定「卡在 running 的陈旧记录」的时间阈值。
     *
     * <p>为什么是 6 小时:一次执行的 HTTP 超时是 30 分钟(Go 侧 longAPI),正常执行最长也就 30 分钟;
     * 6 小时留了 12 倍余量,绝不误伤「真的还在跑」的那一次 —— 误判的代价是把用户正在跑的 run
     * 提前标失败(界面显示假的失败)。而阈值偏长的代价只是「卡死的记录多留一会儿」,
     * 进程重启时 {@link #resetStaleRuns()} 也会清掉它。
     */
    private static final long STALE_RUN_SECONDS = 6 * 3600L;

    /** 自动重置陈旧 running 记录时写的错误文案(用户要能在运行记录里看懂)。 */
    private static final String STALE_RUN_MSG = "上次执行未正常结束,已自动重置";

    /** 进程重启时重置遗留 running 记录写的错误文案。 */
    private static final String RESET_RUN_MSG = "进程重启导致运行中断";

    /** 数据库 datetime('now') 的格式(UTC,秒精度),字符串比较与时间先后一致。 */
    private static final DateTimeFormatter SQLITE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    /** 列清单:与 {@link #read(ResultSet)} 的读取顺序一致,写成常量避免两处各写一遍。 */
    private static final String COLS = "id, name, description, enabled, connection_id, target_db, target_table,"
            + " config_json, coalesce(last_run_at,'') AS last_run_at, coalesce(last_status,'') AS last_status,"
            + " coalesce(last_rows,0) AS last_rows, coalesce(last_error,'') AS last_error,"
            + " created_at, updated_at";

    /** 工作流行的列映射(时间列是 TEXT,故用 getString 而非 getTimestamp)。 */
    private static final RowMapper<ArbWorkflow> MAPPER = (rs, row) -> read(rs);

    /**
     * 「先查再插」的临界区锁。
     *
     * <p>检查与插入必须在同一个临界区里 —— 否则连点两次执行的请求会各自插进一条 running,
     * 两个执行各自建表/回填,互相把对方的产物换掉。
     *
     * <p>⚠️ 只管本进程;另一个进程抢在前面插入时由部分唯一索引
     * {@code idx_arb_runs_one_running} 挡下,那里再翻译成同一个语义。
     */
    private final Object runGate = new Object();

    /** 原生 JDBC 数据源:需要手写事务时不再走 JdbcTemplate,直接用 DataSource 取连接。 */
    private final DataSource dataSource;

    /** 常规单语句读写走 JdbcTemplate(自动获取/释放连接)。 */
    private final JdbcTemplate jdbc;


    public ArbWorkflowRepository(DataSource dataSource) {
        this.dataSource = dataSource;
        this.jdbc = new JdbcTemplate(dataSource);
    }

    // =====================================================================
    // 工作流配置
    // =====================================================================

    /**
     * 列出全部工作流(最新在前)。
     *
     * <p>⚠️ 单行解析失败只跳过并记日志,不让整个列表 500 —— 否则连坏记录的 id 都拿不到,
     * 用户就没法删掉它(与 Go 同口径)。
     */
    public List<ArbWorkflow> list() {
        List<ArbWorkflow> out = new ArrayList<ArbWorkflow>();
        jdbc.query("SELECT " + COLS + " FROM arb_workflows w WHERE EXISTS(SELECT 1 FROM el_resource_owners o WHERE o.resource_type='arb-workflow' AND o.resource_id=w.id AND o.owner_user_id=?) ORDER BY id DESC LIMIT " + MAX_LIST, rs -> {
            try {
                out.add(read(rs));
            } catch (Exception error) {
                // RowCallbackHandler 逐行回调:单行解析失败只跳过该行,不影响其余记录
                LOG.warn("跳过无法解析的工作流记录 id={}: {}", rs.getLong("id"), error.getMessage());
            }
        },com.el2ck.config.AuthContext.userId());
        return out;
    }

    /** 后台调度候选:启用且已登记所有者的工作流 ID,不依赖当前请求身份。 */
    public List<Long> enabledOwnedIds() {
        return jdbc.queryForList("SELECT w.id FROM arb_workflows w"
                + " JOIN el_resource_owners o ON o.resource_type='arb-workflow' AND o.resource_id=w.id"
                + " WHERE w.enabled=1 ORDER BY w.id", Long.class);
    }

    /** 按 ID 取工作流;不存在返回 null(由服务层映射 404)。 */
    public ArbWorkflow get(long id) {
        access.requireOwner("arb-workflow",id);
        List<ArbWorkflow> found = jdbc.query("SELECT " + COLS + " FROM arb_workflows WHERE id=?", MAPPER, id);
        return found.isEmpty() ? null : found.get(0);
    }

    /** 新建工作流,返回新 ID。 */
    @org.springframework.transaction.annotation.Transactional
    public long create(ArbWorkflow workflow) {
        // 配置序列化失败必须在这里就抛,不能存一个 '{}' 蒙混过去 —— 那样回读出来的配置是空的
        final String configJson = writeConfig(workflow);
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO arb_workflows"
                            + " (name, description, enabled, connection_id, target_db, target_table, config_json)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, workflow.getName());
            statement.setString(2, workflow.getDescription());
            statement.setInt(3, workflow.isEnabled() ? 1 : 0);
            statement.setLong(4, workflow.getConnectionId());
            statement.setString(5, workflow.getTargetDb());
            statement.setString(6, workflow.getTargetTable());
            statement.setString(7, configJson);
            return statement;
        }, keys);
        if (keys.getKey() == null) {
            throw new IllegalStateException("无法获取新建工作流 ID");
        }
        long id=keys.getKey().longValue();
        access.register("arb-workflow",id);
        return id;
    }

    /**
     * 更新配置(不动运行状态字段,那些由 {@link #finishRun} 维护),顺带刷新 updated_at。
     *
     * <p>返回影响行数,0 表示记录不存在。
     */
    public int update(ArbWorkflow workflow) {
        access.requireOwner("arb-workflow",workflow.getId());
        String configJson = writeConfig(workflow);
        return jdbc.update("UPDATE arb_workflows SET name=?, description=?, enabled=?, connection_id=?,"
                        + " target_db=?, target_table=?, config_json=?, updated_at=datetime('now') WHERE id=?",
                workflow.getName(), workflow.getDescription(), workflow.isEnabled() ? 1 : 0,
                workflow.getConnectionId(), workflow.getTargetDb(), workflow.getTargetTable(),
                configJson, workflow.getId());
    }

    /** Physical targets must remain unique across owners; only return a boolean, never another user's config. */
    public boolean targetUsedByOther(ArbWorkflow workflow) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM arb_workflows WHERE id<>? AND connection_id=? AND LOWER(TRIM(target_db))=LOWER(TRIM(?)) AND LOWER(TRIM(target_table))=LOWER(TRIM(?))",Long.class,
                workflow.getId(),workflow.getConnectionId(),workflow.getTargetDb(),workflow.getTargetTable())>0;
    }

    /**
     * 删除工作流,并级联删除它的运行记录;返回是否真的删掉了主记录(0 行 = 不存在)。
     *
     * <p>⚠️ 只删配置,不删 ClickHouse 里的聚合表与物化视图 —— 那是不可逆的数据操作,
     * 必须由用户显式点「删除产物」触发(见 {@code ArbWorkflowService#dropObjects})。
     *
     * <p>两步必须同生共死:先删历史再删主记录,否则会留下没有归属的运行记录。
     */
    @org.springframework.transaction.annotation.Transactional
    public boolean delete(long id) {
        access.requireOwner("arb-workflow",id);
        jdbc.update("DELETE FROM arb_workflow_runs WHERE workflow_id=?",id);
        int deleted=jdbc.update("DELETE FROM arb_workflows WHERE id=?",id);
        if(deleted>0)access.remove("arb-workflow",id);
        return deleted>0;
    }

    /**
     * 把除 keepId 外所有工作流的「宽表」标记清掉。
     *
     * <p>为什么需要原子:服务层先保存 keepId(标记为宽表),再清掉其它宽表标记。
     * 如果「查出来 + 逐个更新」不持锁,两个并发请求互相清,结果可能两个标记都没了。
     * 本锁只管本进程;多进程场景由 SQLite 的写串行化兜底(一条 UPDATE 是原子的)。
     *
     * <p>配置损坏的行【不碰】:解析不出 isWide 就去改它,等于把一份读不懂的配置覆盖成空的。
     */
    public void clearOtherWideFlag(long keepId) {
        access.requireOwner("arb-workflow",keepId);
        synchronized (runGate) {
            List<long[]> ids = new ArrayList<>();
            List<String> configs = new ArrayList<>();
            jdbc.query("SELECT id, config_json FROM arb_workflows w WHERE id != ? AND EXISTS(SELECT 1 FROM el_resource_owners o WHERE o.resource_type='arb-workflow' AND o.resource_id=w.id AND o.owner_user_id=?)", rs -> {
                ids.add(new long[]{rs.getLong("id")});
                configs.add(rs.getString("config_json"));
            }, keepId,com.el2ck.config.AuthContext.userId());

            for (int i = 0; i < ids.size(); i++) {
                String raw = configs.get(i);
                ArbWorkflowConfig cfg;
                try {
                    cfg = raw == null || raw.trim().isEmpty() ? new ArbWorkflowConfig() : JSON.readValue(raw, ArbWorkflowConfig.class);
                } catch (Exception e) {
                    LOG.warn("跳过配置无法解析的工作流 id={}: {}", ids.get(i)[0], e.getMessage());
                    continue;
                }
                if (!cfg.isWide()) {
                    continue;
                }
                cfg.setWide(false);
                try {
                    jdbc.update("UPDATE arb_workflows SET config_json=?, updated_at=CURRENT_TIMESTAMP WHERE id=?",
                            JSON.writeValueAsString(cfg), ids.get(i)[0]);
                } catch (Exception e) {
                    throw new IllegalStateException("清理重复宽表标记失败: " + e.getMessage(), e);
                }
            }
        }
    }

    // =====================================================================
    // 运行记录
    // =====================================================================

    /**
     * 开始时写一条 running 记录,返回运行 ID。
     *
     * <p>检查与插入在同一个临界区里做原子防重入:该工作流已有 running 记录时抛
     * {@link AlreadyRunningException}。这不是「多此一举」—— 检查还承担着【陈旧记录自愈】:
     * 收尾写库失败(磁盘满/库锁)或进程在收尾前被杀时,run 行会停在 running,
     * 部分唯一索引挡着,这条工作流到下次进程重启前【再也跑不了】。
     * 所以超过 {@link #STALE_RUN_SECONDS} 的那条要先被标失败再放行。
     */
    public long createRun(long workflowId, String action) {
        return createRun(workflowId, action, false);
    }


    public long createRun(long workflowId, String action, boolean exclusivelyLocked) {
        access.requireOwner("arb-workflow",workflowId);
        try (com.el2ck.repository.metadata.TableChangeGate.Lease gate = tableChangeGate.acquire()) {
            gate.check();
            synchronized (runGate) {
                List<long[]> existingIds = new ArrayList<>();
                List<String> existingStarted = new ArrayList<>();
                // started_at 由 datetime('now') 写入(UTC 的 "YYYY-MM-DD HH:MM:SS"),
                // 同格式下字符串比较与时间先后一致,不必再解析成时间类型
                jdbc.query("SELECT id, coalesce(started_at,'') AS started_at FROM arb_workflow_runs"
                                + " WHERE workflow_id=? AND status=? ORDER BY id DESC LIMIT 1",
                        rs -> {
                            existingIds.add(new long[]{rs.getLong("id")});
                            existingStarted.add(rs.getString("started_at"));
                        }, workflowId, ArbWorkflowRun.STATUS_RUNNING);

                if (!existingIds.isEmpty()) {
                    Long owner = jdbc.queryForObject("SELECT IS_USED_LOCK(?)", Long.class, "el_arb_workflow_" + workflowId);
                    if (!exclusivelyLocked && owner != null) throw new AlreadyRunningException();
                    long staleId = existingIds.get(0)[0];
                    String startedAt = existingStarted.get(0);
                    String cutoff = SQLITE_TIME.format(Instant.now().minusSeconds(STALE_RUN_SECONDS));
                    if (!exclusivelyLocked && startedAt.compareTo(cutoff) >= 0) {
                        throw new AlreadyRunningException();
                    }
                    // 重置不掉就只能让本次失败:否则两处都留着 running,唯一索引照样挡着
                    jdbc.update("UPDATE arb_workflow_runs SET status=?, error=?, finished_at=datetime('now') WHERE id=?",
                            ArbWorkflowRun.STATUS_FAILED, STALE_RUN_MSG, staleId);
                    LOG.warn("工作流 {} 的上一次执行(run {},开始于 {})超过 {} 秒仍未结束,已自动标记为失败",
                            workflowId, staleId, startedAt, STALE_RUN_SECONDS);
                }

                KeyHolder keys = new GeneratedKeyHolder();
                try {
                    jdbc.update(connection -> {
                        PreparedStatement statement = connection.prepareStatement(
                                "INSERT INTO arb_workflow_runs (workflow_id, action, status) VALUES (?, ?, ?)",
                                Statement.RETURN_GENERATED_KEYS);
                        statement.setLong(1, workflowId);
                        statement.setString(2, action);
                        statement.setString(3, ArbWorkflowRun.STATUS_RUNNING);
                        return statement;
                    }, keys);
                } catch (DataAccessException e) {
                    // 本锁只管本进程;另一个进程抢在前面插入时由唯一索引挡下,
                    // 这里翻译成同一个语义,别把原始 SQL 报错抛给用户。
                    if (isUniqueViolation(e)) {
                        throw new AlreadyRunningException(e);
                    }
                    throw e;
                }
                if (keys.getKey() == null) {
                    throw new IllegalStateException("无法获取新建运行记录 ID");
                }
                return keys.getKey().longValue();
            }
        }
    }

    /**
     * 结束时落最终状态,并同步工作流上的「最近一次执行」快照。
     *
     * <p>⚠️ 调用方必须【无条件】执行这一步(含异常路径)。收尾写库失败(磁盘满/库锁)时,
     * run 行会永久停在 running:唯一索引挡着,这条工作流到下次进程重启前都跑不了。
     * 所以这里尽力再标一次 failed —— 少写几列也要让它脱离 running;这次再失败就只记日志,
     * 原始错误照旧抛给调用方。
     */
    public void finishRun(long runId, long workflowId, String status, long rows, String sql, String error) {
        finishRun(runId,workflowId,status,rows,sql,error,"");
    }

    /** 进度与 SQL 分列保存，兼容旧调用方未提供进度的情况。 */
    public void finishRun(long runId, long workflowId, String status, long rows, String sql, String error, String progress) {
        String sqlText = sql == null ? "" : sql;
        String errText = error == null ? "" : error;
        try {
            jdbc.update("UPDATE arb_workflow_runs SET status=?, rows=?, sql=?, error=?,progress=?,"
                    + " finished_at=datetime('now') WHERE id=? AND status='running'", status, rows, sqlText, errText, progress == null ? "" : progress, runId);
        } catch (DataAccessException e) {
            String mark = errText.trim().isEmpty() ? "运行记录收尾写入失败,状态未知" : errText;
            try {
                jdbc.update("UPDATE arb_workflow_runs SET status=?, error=?, finished_at=datetime('now') WHERE id=?",
                        ArbWorkflowRun.STATUS_FAILED, mark, runId);
            } catch (DataAccessException uerr) {
                LOG.error("工作流 {} 的运行记录 {} 收尾失败,且无法标记为失败"
                        + "(该工作流在下一次进程重启前可能无法再执行): {}", workflowId, runId, uerr.getMessage());
            }
            throw e;
        }
        jdbc.update("UPDATE arb_workflows SET last_run_at=datetime('now'), last_status=?, last_rows=?,"
                + " last_error=?, updated_at=datetime('now') WHERE id=?", status, rows, errText, workflowId);
    }

    /** 取某工作流的运行记录(最新在前)。limit &lt;= 0 或超上限按默认/上限收口。 */
    public List<ArbWorkflowRun> listRuns(long workflowId, int limit) {
        access.requireOwner("arb-workflow",workflowId);
        int n = limit <= 0 || limit > MAX_RUN_LIMIT ? DEFAULT_RUN_LIMIT : limit;
        return jdbc.query("SELECT id, workflow_id, action, status, coalesce(rows,0) AS rows, started_at,"
                        + " coalesce(finished_at,'') AS finished_at, coalesce(error,'') AS error,"
                        + " coalesce(sql,'') AS sql, coalesce(progress,'') AS progress FROM arb_workflow_runs WHERE workflow_id=?"
                        + " ORDER BY id DESC LIMIT " + n,
                (rs, row) -> {
                    ArbWorkflowRun run = new ArbWorkflowRun();
                    run.setId(rs.getLong("id"));
                    run.setWorkflowId(rs.getLong("workflow_id"));
                    run.setAction(rs.getString("action"));
                    run.setStatus(rs.getString("status"));
                    run.setRows(rs.getLong("rows"));
                    run.setStartedAt(rs.getString("started_at"));
                    run.setFinishedAt(rs.getString("finished_at"));
                    run.setError(rs.getString("error"));
                    run.setSql(rs.getString("sql"));
                    run.setProgress(rs.getString("progress"));
                    return run;
                }, workflowId);
    }

    /**
     * 清掉工作流上的「最近一次执行」快照(产物被删后调用)。
     *
     * <p>设置 last_error 而不是留一个好看的数字:列表上要能看出「表已经不在了」——
     * 否则列表上还挂着「成功 · 900 万行」,而聚合表已经不存在了。
     */
    public void clearRunState(long workflowId, String reason) {
        jdbc.update("UPDATE arb_workflows SET last_status=?, last_rows=0, last_error=?,"
                        + " updated_at=datetime('now') WHERE id=?",
                ArbWorkflowRun.STATUS_FAILED, reason == null ? "" : reason, workflowId);
    }

    /** 把「进程重启导致卡在 running」的记录标记为失败(启动时调用)。 */
    public int resetStaleRuns() {
        return jdbc.update("UPDATE arb_workflow_runs SET status=?, error=?, finished_at=datetime('now')"
                + " WHERE status=? AND IS_USED_LOCK(CONCAT('el_arb_workflow_',workflow_id)) IS NULL",
                ArbWorkflowRun.STATUS_FAILED, RESET_RUN_MSG, ArbWorkflowRun.STATUS_RUNNING);
    }

/** 判断工作流是否存在处于 {@code running} 状态的运行记录。 */
    public boolean isRunning(long workflowId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM arb_workflow_runs WHERE workflow_id=? AND status=?",
                Integer.class, workflowId, ArbWorkflowRun.STATUS_RUNNING);
        return n != null && n > 0;
    }

/** 读取工作流的指定运行记录；查询前校验工作流所有权。 */
    public ArbWorkflowRun getRun(long workflowId, long runId) {
        access.requireOwner("arb-workflow", workflowId);
        List<ArbWorkflowRun> rows = jdbc.query("SELECT * FROM arb_workflow_runs WHERE workflow_id=? AND id=?", (rs, n) -> {
            ArbWorkflowRun run = new ArbWorkflowRun();
            run.setId(rs.getLong("id")); run.setWorkflowId(rs.getLong("workflow_id"));
            run.setAction(rs.getString("action")); run.setStatus(rs.getString("status"));
            run.setRows(rs.getLong("rows")); run.setSql(rs.getString("sql")); run.setError(rs.getString("error"));
            run.setProgress(rs.getString("progress"));
            run.setStartedAt(rs.getString("started_at")); run.setFinishedAt(rs.getString("finished_at"));
            return run;
        }, workflowId, runId);
        return rows.isEmpty() ? null : rows.get(0);
    }


/**
 * 仅当指定运行记录仍处于 running 状态时将其标记为失败。
 *
 * <p>第一条 UPDATE 同时匹配运行 ID、工作流 ID 和 {@code status='running'}，因此
 * 已完成、已失败或其他工作流的记录不会被覆盖；只有该条件更新命中后，才同步更新
 * 工作流的最后状态、错误和运行时间。两条 UPDATE 未在本方法内显式事务化，第二步
 * 失败时第一步已经写入的失败状态不会由本方法回滚。
 *
 * @param runId 运行记录 ID
 * @param workflowId 运行记录所属工作流 ID
 * @param error 写入运行记录和工作流摘要的失败信息，可为 {@code null}
 * @throws org.springframework.dao.DataAccessException 任一数据库更新失败时抛出
 */
    public void failIfRunning(long runId, long workflowId, String error) {
        int updated = jdbc.update("UPDATE arb_workflow_runs SET status='failed',error=?,finished_at=datetime('now')"
                + " WHERE id=? AND workflow_id=? AND status='running'", error, runId, workflowId);
        if (updated > 0) jdbc.update("UPDATE arb_workflows SET last_status='failed',last_error=?,last_run_at=datetime('now') WHERE id=?", error, workflowId);
    }

    // =====================================================================
    // 行映射与工具
    // =====================================================================

    /**
     * 逐行把结果集映射成实体(解析配置 JSON;失败时抛异常交给调用方决定跳过还是报错)。
     *
     * <p>⚠️ 方法签名只能声明 {@link SQLException}({@link RowMapper} 的契约就这么宽),
     * 所以配置 JSON 解析失败包成 IllegalStateException 抛出 ——
     * 列表接口逐行捕获后跳过该行,单条查询则向上冒泡。
     */
    private static ArbWorkflow read(ResultSet rs) throws SQLException {
        ArbWorkflow workflow = new ArbWorkflow();
        workflow.setId(rs.getLong("id"));
        workflow.setName(rs.getString("name"));
        workflow.setDescription(rs.getString("description"));
        workflow.setEnabled(rs.getInt("enabled") != 0);
        workflow.setConnectionId(rs.getLong("connection_id"));
        workflow.setTargetDb(rs.getString("target_db"));
        workflow.setTargetTable(rs.getString("target_table"));
        workflow.setLastRunAt(rs.getString("last_run_at"));
        workflow.setLastStatus(rs.getString("last_status"));
        workflow.setLastRows(rs.getLong("last_rows"));
        workflow.setLastError(rs.getString("last_error"));
        String configJson = rs.getString("config_json");
        if (configJson != null && !configJson.trim().isEmpty()) {
            try {
                ArbWorkflowConfig cfg = JSON.readValue(configJson, ArbWorkflowConfig.class);
                workflow.setSources(cfg.getSources() == null ? new ArrayList<>() : cfg.getSources());
                workflow.setFields(cfg.getFields() == null ? new ArrayList<>() : cfg.getFields());
                workflow.setColumns(cfg.getColumns() == null ? new ArrayList<>() : cfg.getColumns());
                workflow.setWide(cfg.isWide());
            } catch (Exception e) {
                throw new IllegalStateException(
                        "解析工作流 \"" + workflow.getName() + "\" 的配置失败: " + e.getMessage(), e);
            }
        }
        workflow.setCreatedAt(rs.getString("created_at"));
        workflow.setUpdatedAt(rs.getString("updated_at"));
        return workflow;
    }

    /** 把三块配置 + 宽表标记序列化成 config_json 的内容;序列化失败直接抛(存一个空配置比报错危险)。 */
    private static String writeConfig(ArbWorkflow workflow) {
        ArbWorkflowConfig cfg = new ArbWorkflowConfig();
        cfg.setSources(workflow.getSources() == null ? new ArrayList<>() : workflow.getSources());
        cfg.setFields(workflow.getFields() == null ? new ArrayList<>() : workflow.getFields());
        cfg.setColumns(workflow.getColumns() == null ? new ArrayList<>() : workflow.getColumns());
        cfg.setWide(workflow.isWide());
        try {
            return JSON.writeValueAsString(cfg);
        } catch (Exception e) {
            throw new IllegalStateException("序列化工作流配置失败: " + e.getMessage(), e);
        }
    }

    /**
     * 判定是否撞了「同一工作流同时只能有一条 running」的唯一约束。
     *
     * <p>怎么认:Spring 会把 SQLite 的约束冲突翻成 {@link DataAccessException} 家族,具体子类
     * 取决于 SQLState 映射(不同驱动/Spring 版本可能落到 DuplicateKeyException 或
     * DataIntegrityViolationException),故不锁死类型 —— 统一沿 cause 链找 SQLException,
     * 认 SQLState 的 23 类(完整性约束)且消息里有 UNIQUE。
     */
    private static boolean isUniqueViolation(DataAccessException e) {
        Throwable cur = e;
        while (cur != null) {
            if (cur instanceof SQLException) {
                SQLException sql = (SQLException) cur;
                String state = sql.getSQLState();
                String msg = sql.getMessage() == null ? "" : sql.getMessage().toUpperCase();
                boolean constraintState = state != null && state.startsWith("23");
                if (msg.matches("(?s).*(UNIQUE|DUPLICATE).*") && (constraintState || state == null)) {
                    return true;
                }
            }
            cur = cur.getCause();
        }
        return false;
    }

    /**
     * 「该工作流已有一次执行在进行中」。
     *
     * <p>刻意做成一个独立异常而不是在仓储里抛业务异常:仓储不依赖 service/controller,
     * 状态码与文案由服务层决定(见 ArchitectureLayerTest)。
     */
    public static class AlreadyRunningException extends RuntimeException {
        /** 无 cause 的重载:「先查再插」自己发现并发。 */
        public AlreadyRunningException() {
            super("已有一次执行在进行中");
        }

        /** 携带原始约束冲突(跨进程抢跑时由唯一索引挡下),便于日志里看到底是哪条索引。 */
        public AlreadyRunningException(Throwable cause) {
            super("已有一次执行在进行中", cause);
        }
    }
}
