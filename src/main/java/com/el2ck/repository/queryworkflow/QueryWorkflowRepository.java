package com.el2ck.repository.queryworkflow;

/**
 * 【文件职责】持久化 SQL 查询工作流定义和运行记录，为多步骤查询编排提供存储能力。
 */

import com.el2ck.repository.query.SqlQueryRepository;

import com.el2ck.entity.queryworkflow.QueryWorkflow;
import com.el2ck.entity.queryworkflow.QueryWorkflowConfig;
import com.el2ck.entity.queryworkflow.QueryWorkflowRun;
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
import java.util.ArrayList;
import java.util.List;

/**
 * 查询工作流与运行历史的持久层(query_workflows + query_workflow_runs),
 * 对应 Go internal/store/query_workflow_store.go。
 *
 * <p>只写 SQL、不做业务校验(名称合法性、配置能否生成 SQL 都在
 * {@link com.el2ck.service.queryworkflow.QueryWorkflowService});涉及多条语句的操作(删除时一并删运行历史)
 * 手写事务,与 {@link SqlQueryRepository} 的约定一致。
 */
@Repository
public class QueryWorkflowRepository {
    @org.springframework.beans.factory.annotation.Autowired
    private com.el2ck.repository.metadata.TableChangeGate tableChangeGate;
    @org.springframework.beans.factory.annotation.Autowired
    private com.el2ck.service.auth.ResourceAccessService access;
    private static final Logger LOG = LoggerFactory.getLogger(QueryWorkflowRepository.class);

    /**
     * 配置列的 JSON 编解码器。
     *
     * <p>⚠️ 必须关闭 FAIL_ON_UNKNOWN_PROPERTIES:配置来自历史版本或 Go 端,
     * 多出一个未知字段就让整条记录读不出来(表现为列表里凭空少一条),比忽略它危险得多。
     */
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * 列表接口一次最多返回多少条(前端没有分页控件,先给个足够大的上限)。
     *
     * <p>加 LIMIT 的理由:配置行会随使用无限增长,而在没有 LIMIT 的
     * {@code ORDER BY id DESC} 上,历史越久查询越慢。
     */
    private static final int MAX_LIST = 1000;

    /** 运行历史列表的默认条数。 */
    private static final int DEFAULT_RUN_LIMIT = 20;

    /** 运行历史的条数上限(接口传入值的封顶)。 */
    private static final int MAX_RUN_LIMIT = 200;

    /** 列清单:与 {@link #MAPPER} 的读取顺序一致,写成常量避免两处各写一遍。 */
    private static final String COLS = "id, name, description, enabled, config_json, created_at, updated_at";

    /** 工作流行的列映射(时间列是 TEXT,故用 getString 而非 getTimestamp)。 */
    private static final RowMapper<QueryWorkflow> MAPPER = (rs, row) -> read(rs);

    /** 原生 JDBC 数据源:需要手写事务时不再走 JdbcTemplate,直接用 DataSource 取连接。 */
    private final DataSource dataSource;

    /** 常规单语句读写走 JdbcTemplate(自动获取/释放连接)。 */
    private final JdbcTemplate jdbc;

/** 创建使用元数据数据源的工作流仓库。 */
    public QueryWorkflowRepository(DataSource dataSource) {
        this.dataSource = dataSource;
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /**
     * 列出全部查询工作流(最新在前)。
     *
     * <p>⚠️ 单行解析失败不再让整个列表 500 —— 那样连坏记录的 id 都拿不到,用户无法删除它。
     * 改为跳过并记日志(与 Go 同口径)。
     */
    public List<QueryWorkflow> list() {
        List<QueryWorkflow> out = new ArrayList<QueryWorkflow>();
        jdbc.query("SELECT " + COLS + " FROM query_workflows w WHERE EXISTS(SELECT 1 FROM el_resource_owners o WHERE o.resource_type='query-workflow' AND o.resource_id=w.id AND o.owner_user_id=?) ORDER BY id DESC LIMIT " + MAX_LIST, rs -> {
            try {
                out.add(read(rs));
            } catch (Exception error) {
                // RowCallbackHandler 逐行回调:单行解析失败只跳过该行,不影响其余记录
                LOG.warn("跳过无法解析的查询工作流记录 id={}: {}", rs.getLong("id"), error.getMessage());
            }
        },com.el2ck.config.AuthContext.userId());
        return out;
    }

    /** 按 ID 取查询工作流;不存在返回 null(由服务层映射 404)。 */
    public QueryWorkflow get(long id) {
        access.requireOwner("query-workflow",id);
        // 用 query + 判空而非 queryForObject:后者查不到会抛 EmptyResultDataAccessException,这里约定返回 null
        List<QueryWorkflow> found = jdbc.query("SELECT " + COLS + " FROM query_workflows WHERE id=?", MAPPER, id);
        return found.isEmpty() ? null : found.get(0);
    }

    /** 新建查询工作流,返回新 ID。 */
    @org.springframework.transaction.annotation.Transactional
    public long create(QueryWorkflow workflow) {
        // 配置序列化失败必须在这里就抛,不能存一个 '{}' 蒙混过去 —— 那样回读出来的配置是空的
        final String configJson = writeConfig(workflow.getConfig());
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO query_workflows (name, description, enabled, config_json) VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, workflow.getName());
            statement.setString(2, workflow.getDescription());
            statement.setInt(3, workflow.isEnabled() ? 1 : 0);
            statement.setString(4, configJson);
            return statement;
        }, keys);
        // SQLite 未返回主键时视为异常状态,及早抛出而不是返回 0 让上层误用
        if (keys.getKey() == null) {
            throw new IllegalStateException("无法获取新建查询工作流 ID");
        }
        long id=keys.getKey().longValue();
        access.register("query-workflow",id);
        return id;
    }

    /** 更新查询工作流,顺带刷新 updated_at;返回影响行数,0 表示记录不存在。 */
    public int update(QueryWorkflow workflow) {
        access.requireOwner("query-workflow",workflow.getId());
        String configJson = writeConfig(workflow.getConfig());
        return jdbc.update("UPDATE query_workflows SET name=?, description=?, enabled=?, config_json=?,"
                        + " updated_at=datetime('now') WHERE id=?",
                workflow.getName(), workflow.getDescription(), workflow.isEnabled() ? 1 : 0,
                configJson, workflow.getId());
    }

    /**
     * 删除查询工作流,运行历史一并删除;返回是否真的删掉了主记录(0 行 = 不存在)。
     *
     * <p>两步必须同生共死:先删历史再删主记录,否则会留下没有归属的运行记录。
     */
    @org.springframework.transaction.annotation.Transactional
    public boolean delete(long id) {
        access.requireOwner("query-workflow",id);
        jdbc.update("DELETE FROM query_workflow_runs WHERE workflow_id=?",id);
        int deleted=jdbc.update("DELETE FROM query_workflows WHERE id=?",id);
        if(deleted>0)access.remove("query-workflow",id);
        return deleted>0;
    }

    // ---- 运行历史 ----

    /**
     * 新建一条 running 运行记录,返回 runId。
     *
     * <p>⚠️ 这一步同时承担【并发去重】:query_workflow_runs 上有部分唯一索引
     * {@code idx_qw_runs_one_running(workflow_id) WHERE status='running'},
     * 同一查询重复点击时插入会撞约束 —— 比「先查再插」的 check-then-act 原子。
     * 撞约束时抛 {@link DuplicateRunningException},由服务层映射成 400。
     */
    public long createRun(long workflowId) {
        access.requireOwner("query-workflow",workflowId);
        try (com.el2ck.repository.metadata.TableChangeGate.Lease gate = tableChangeGate.acquire()) {
            gate.check();
            KeyHolder keys = new GeneratedKeyHolder();
            try {
                jdbc.update(connection -> {
                    PreparedStatement statement = connection.prepareStatement(
                            "INSERT INTO query_workflow_runs (workflow_id, status) VALUES (?, 'running')",
                            Statement.RETURN_GENERATED_KEYS);
                    statement.setLong(1, workflowId);
                    return statement;
                }, keys);
            } catch (DataAccessException e) {
                if (isUniqueViolation(e)) {
                    throw new DuplicateRunningException(e);
                }
                throw e;
            }
            if (keys.getKey() == null) {
                throw new IllegalStateException("无法获取新建运行记录 ID");
            }
            return keys.getKey().longValue();
        }
    }

    /**
     * 收尾一条运行记录。
     *
     * <p>⚠️ 调用方必须【无条件】执行这一步(含异常路径):漏收尾会让这条记录永远停在 running,
     * 撞上部分唯一索引后该查询【再也跑不起来】,只能等下次进程启动时由 v22 迁移复位。
     */
    public void finishRun(long id, String status, long rowsRead, String generatedSql, String error) {
        jdbc.update("UPDATE query_workflow_runs SET status=?, finished_at=datetime('now'),"
                        + " rows_read=?, generated_sql=?, error=? WHERE id=?",
                status, rowsRead, generatedSql == null ? "" : generatedSql,
                error == null ? "" : error, id);
    }

    /** 列出某查询工作流的运行历史(最新在前)。limit &lt;= 0 用默认值,超过上限按上限。 */
    public List<QueryWorkflowRun> listRuns(long workflowId, int limit) {
        access.requireOwner("query-workflow",workflowId);
        int n = limit <= 0 ? DEFAULT_RUN_LIMIT : Math.min(limit, MAX_RUN_LIMIT);
        return jdbc.query("SELECT id, workflow_id, status, started_at, finished_at, rows_read,"
                        + " generated_sql, error FROM query_workflow_runs WHERE workflow_id=?"
                        + " ORDER BY id DESC LIMIT " + n,
                (rs, row) -> {
                    QueryWorkflowRun run = new QueryWorkflowRun();
                    run.setId(rs.getLong("id"));
                    run.setWorkflowId(rs.getLong("workflow_id"));
                    run.setStatus(rs.getString("status"));
                    run.setStartedAt(rs.getString("started_at"));
                    run.setFinishedAt(rs.getString("finished_at"));
                    run.setRowsRead(rs.getLong("rows_read"));
                    run.setGeneratedSql(rs.getString("generated_sql"));
                    run.setError(rs.getString("error"));
                    return run;
                }, workflowId);
    }

    /**
     * 逐行把结果集映射成实体(解析配置 JSON;失败时抛异常交给调用方决定跳过还是报错)。
     *
     * <p>⚠️ 方法签名只能声明 {@link SQLException}({@link RowMapper} 的契约就这么宽),
     * 所以配置 JSON 解析失败包成 IllegalStateException 抛出 ——
     * 列表接口逐行捕获后跳过该行,单条查询则向上冒泡。
     */
    private static QueryWorkflow read(ResultSet rs) throws SQLException {
        QueryWorkflow workflow = new QueryWorkflow();
        workflow.setId(rs.getLong("id"));
        workflow.setName(rs.getString("name"));
        workflow.setDescription(rs.getString("description"));
        // SQLite 无布尔类型:INTEGER 0/1 → boolean
        workflow.setEnabled(rs.getInt("enabled") != 0);
        String configJson = rs.getString("config_json");
        if (configJson != null && !configJson.trim().isEmpty()) {
            try {
                workflow.setConfig(JSON.readValue(configJson, QueryWorkflowConfig.class));
            } catch (Exception e) {
                throw new IllegalStateException(
                        "解析查询工作流配置失败(id=" + workflow.getId() + "): " + e.getMessage(), e);
            }
        }
        workflow.setCreatedAt(rs.getString("created_at"));
        workflow.setUpdatedAt(rs.getString("updated_at"));
        return workflow;
    }

    /** 配置 → JSON 文本;序列化失败直接抛(存一个空配置比报错危险)。 */
    private static String writeConfig(QueryWorkflowConfig config) {
        try {
            return JSON.writeValueAsString(config == null ? new QueryWorkflowConfig() : config);
        } catch (Exception e) {
            throw new IllegalStateException("序列化查询工作流配置失败: " + e.getMessage(), e);
        }
    }

    /**
     * 判定是否撞了「同一查询同时只能有一条 running」的唯一约束。
     *
     * <p>怎么认:Java 侧 Spring 会把 SQLite 的约束冲突翻成 {@link DataAccessException} 家族,
     * 具体子类取决于 SQLState 映射(不同驱动/Spring 版本可能落到
     * `DuplicateKeyException` 或 `DataIntegrityViolationException`),故不锁死类型 ——
     * 统一沿 cause 链找 SQLException,认 SQLState 的 23 类(完整性约束)且消息里有 UNIQUE。
     * 只看消息也够用,但加上 SQLState 能排除「查询里自带 UNIQUE 字样」这类误判。
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
     * 「该查询已有一次运行在进行中」。
     *
     * <p>刻意做成一个独立异常而不是在仓储里抛业务异常:仓储不依赖 service/controller
     * (见 ArchitectureLayerTest),状态码由服务层决定。
     */
    public static class DuplicateRunningException extends RuntimeException {
        /** 携带原始约束冲突,便于日志里看到底是哪条索引。 */
        public DuplicateRunningException(Throwable cause) {
            super("该查询正在运行中", cause);
        }
    }
}
