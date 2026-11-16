/**
 * 【文件职责】本文件定义查询工作流模块的业务服务 QueryWorkflowService。承载该模块的核心业务规则，协调仓储、连接器或外部服务，并统一转换业务异常和操作状态。
 */

package com.el2ck.service.queryworkflow;

import com.el2ck.service.connection.ConnectionService;
import com.el2ck.service.query.QueryService;
import com.el2ck.service.query.SqlQueryService;

import com.el2ck.ckpool.CkClient;
import com.el2ck.ckpool.QueryResult;
import com.el2ck.entity.table.ColumnAliasHint;
import com.el2ck.entity.query.ProgColumn;
import com.el2ck.entity.query.QueryExportRequest;
import com.el2ck.entity.query.QueryRequest;
import com.el2ck.entity.query.QueryResponse;
import com.el2ck.entity.queryworkflow.QueryWorkflow;
import com.el2ck.entity.queryworkflow.QueryWorkflowConfig;
import com.el2ck.entity.queryworkflow.QueryWorkflowFieldRule;
import com.el2ck.entity.queryworkflow.QueryWorkflowInput;
import com.el2ck.entity.queryworkflow.QueryWorkflowRun;
import com.el2ck.entity.queryworkflow.QueryWorkflowSource;
import com.el2ck.exception.ApiException;
import com.el2ck.repository.queryworkflow.QueryWorkflowRepository;
import com.el2ck.util.Sql;
import com.el2ck.workflow.QueryPlan;
import com.el2ck.workflow.QueryPlanBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 查询工作流(4 步向导:多表查询,只 SELECT 不落库)的业务逻辑,对应 Go
 * {@code internal/api/query_workflow_api.go}(HTTP 编排)+ {@code internal/sync/engine_query_workflow.go}(SQL 生成)。
 *
 * <p>本类只做编排与校验:SQL 怎么拼在 {@link QueryPlanBuilder},语句怎么执行在 {@link QueryService},
 * 配置怎么存/取在 {@link QueryWorkflowRepository}。三条路径(试跑 / 执行 / 导出)共用同一套
 * 「配置 → SQL + 程序算子计划」的推导,保证【看到的、执行到的、导出的是同一条语句】——
 * 程序算子(SM3/3DES/FF1)不进 SQL,任何一处漏传计划都不会报错,只会让密钥列以明文出现在
 * 结果或 CSV 里,因此这里刻意不让调用方分别传 SQL 与计划。
 *
 * <h3>取消运行:为什么是 KILL QUERY 而不是断开连接</h3>
 * Go 侧靠 {@code context} 取消,驱动随之把连接层面的查询中止。Java 的原生驱动没有等价能力:
 * <ul>
 *   <li>{@code Statement.cancel()} 在 clickhouse-native-jdbc 2.6.4 里直接抛
 *       {@code SQLFeatureNotSupportedException}(已反编译确认);</li>
 *   <li>关掉底层连接只会让【客户端】的阻塞读立刻返回,<b>服务端的查询照跑不误</b> ——
 *       实测:关连接后 1 秒内客户端异常返回,而 {@code system.processes} 里那条查询仍然在,
 *       直到自然结束。那等于界面说「已取消」而 ClickHouse 还在烧资源。</li>
 * </ul>
 * 因此每次运行会给执行语句加一个只属于该次运行的块注释标记
 * {@code &#47;* el2ck-qw-<runId> *&#47;},取消时在另一条连接上发
 * {@code KILL QUERY WHERE query LIKE '&#47;* el2ck-qw-<runId> *&#47;%'},
 * 由服务端真正终止该查询(实测:客户端 ~1.8 秒拿到异常,{@code system.processes} 里该
 * query_id 立即消失)。标记锚定在语句开头,所以 KILL 自己(KILL 开头)不会被这条 LIKE 命中。
 */
@Service
public class QueryWorkflowService {
    @org.springframework.beans.factory.annotation.Autowired
    private com.el2ck.service.auth.ResourceAccessService access;

    /**
     * 校验工作流配置并逐个检查源表读取权限。
     *
     * <p>空配置、空源列表或列表中的空元素均按参数错误处理；任一源表无读权限时
     * 由资源访问服务抛出 403。检查按配置顺序执行，不访问源表数据。
     *
     * @param config 查询工作流配置
     */
    private void authorizeSources(QueryWorkflowConfig config) {
        if(config==null || config.getSources()==null)throw ApiException.validation("请配置源表");
        for(com.el2ck.entity.queryworkflow.QueryWorkflowSource source:config.getSources()) {
            if(source==null)throw ApiException.validation("源表不能为空");
            access.table(source.getConnectionId(),source.getDatabase(),source.getTable(),true);
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(QueryWorkflowService.class);

    /** 运行中/成功/失败,与 Go {@code model.QueryRunStatus*} 一致。 */
    private static final String STATUS_RUNNING = "running";
    private static final String STATUS_SUCCESS = "success";
    private static final String STATUS_FAILED = "failed";

    /** 运行历史默认条数。 */
    private static final int RUNS_DEFAULT_LIMIT = 20;

    /** 运行历史条数上限;超出这个范围的值 Go 一律忽略并回落到默认值。 */
    private static final int RUNS_MAX_LIMIT = 200;

    /** 被取消的运行在历史里的失败原因。见 {@link #cancel(long)} 对与 Go 差异的说明。 */
    private static final String CANCELLED_MESSAGE = "已取消";

    /** 取消标记前缀:与 runId 拼成块注释,放在执行语句最前面。 */
    private static final String MARKER_PREFIX = "/* el2ck-qw-";

    /** 取消标记后缀。 */
    private static final String MARKER_SUFFIX = " */ ";

    /** 查询工作流持久层。 */
    private final QueryWorkflowRepository repository;

    /** 连接服务:管理身份(仅连接配置/默认连接查询)。 */
    private final ConnectionService connSvc;

    /** 当前用户的真实 CK 身份:取消查询也必须使用同一身份。 */
    private final com.el2ck.service.ck.CkIdentityService identities;

    /** SQL 执行服务:三条执行路径都走它,只读判定/截断/分页/程序算子的语义只有一份。 */
    private final QueryService queryService;

    /**
     * 正在运行的查询:runId → 取消所需的上下文。
     *
     * <p>为什么需要它:取消是【另一个 HTTP 请求】发起的,得知道那次运行用的是哪个连接、
     * 以及它这条语句的标记;也是「该运行是否还在跑」的唯一判据(与 Go 的 {@code running} 表对应)。
     */
    private final Map<Long, RunHandle> running = new ConcurrentHashMap<Long, RunHandle>();
    /**
     * 创建查询工作流业务服务并装配后续调用所需的依赖。
     *
     * 依赖由 Spring 容器统一管理；本构造器只保存引用或初始化轻量适配对象，不执行远程业务操作。
     *
     * @param repository 元数据仓储，用于读取或写入平台业务记录
     * @param connSvc ClickHouse 连接服务，负责解析连接并按执行身份创建客户端
     * @param queryService ClickHouse 查询执行服务，用于执行经过授权的 SQL
     * @param identities ClickHouse 用户身份服务，用于按任务所有者或当前用户解析执行身份
     */
    public QueryWorkflowService(QueryWorkflowRepository repository,
                                ConnectionService connSvc,
                                QueryService queryService,
                                com.el2ck.service.ck.CkIdentityService identities) {
        this.repository = repository;
        this.connSvc = connSvc;
        this.queryService = queryService;
        this.identities = identities;
    }

    /** 一次运行的可取消句柄。 */
    private static final class RunHandle {
        /** 执行这次查询所用的连接 ID(KILL 要在同一条连接上发)。 */
        private final long connectionId;
        /** 该次运行的语句标记;取消时按它精确匹配。 */
        private final String marker;
        /** 是否是通过取消接口被叫停的(决定历史里记「已取消」还是底层报错)。 */
        private volatile boolean cancelled;

        RunHandle(long connectionId, String marker) {
            this.connectionId = connectionId;
            this.marker = marker;
        }
    }

    // =====================================================================
    // 配置的增删改查
    // =====================================================================

    /** 列表(最新在前)。 */
    public List<QueryWorkflow> list() {
        return repository.list();
    }

    /** 单条;不存在抛 404。 */
    public QueryWorkflow get(long id) {
        QueryWorkflow workflow = repository.get(id);
        if (workflow == null) {
            throw ApiException.notFound("查询工作流不存在");
        }
        return workflow;
    }

    /**
     * 新建。
     *
     * <p>保存前先跑一遍 SQL 生成:存进去一条生成不出 SQL 的配置,等于存了一条永远跑不起来的记录,
     * 用户要到点「运行」时才发现。错误文案与 Go 一致(「请输入名称」/「配置无效: …」)。
     */
    public QueryWorkflow create(QueryWorkflowInput input) {
        QueryWorkflow workflow = fromInput(input);
        authorizeSources(workflow.getConfig());
        requireConfigGeneratable(workflow.getConfig());
        workflow.setId(repository.create(workflow));
        // 回读一次拿库侧生成的时间戳(Go 返回的是内存对象,时间戳为空串;
        // 本仓库既有约定是回读,见 SqlQueryService)
        return repository.get(workflow.getId());
    }

    /** 更新;不存在抛 404。 */
    public QueryWorkflow update(long id, QueryWorkflowInput input) {
        access.requireOwner("query-workflow",id);
        QueryWorkflow workflow = fromInput(input);
        authorizeSources(workflow.getConfig());
        workflow.setId(id);
        requireConfigGeneratable(workflow.getConfig());
        if (repository.update(workflow) == 0) {
            throw ApiException.notFound("查询工作流不存在");
        }
        return repository.get(id);
    }

    /** 删除(运行历史一并删除);不存在抛 404。 */
    public void delete(long id) {
        if (!repository.delete(id)) {
            throw ApiException.notFound("查询工作流不存在");
        }
    }

    // =====================================================================
    // 生成 / 试跑
    // =====================================================================

    /**
     * 只生成 SQL,不执行(对应 {@code POST /query-workflows/{id}/preview-sql})。
     *
     * <p>返回里带 {@code progCols}:程序算子不进 SQL,界面要展示「这条 SQL 之后还有哪些列会被
     * 本程序处理」只能靠它。
     */
    public Map<String, Object> previewSql(long id) {
        QueryWorkflow workflow = get(id);
        authorizeSources(workflow.getConfig());
        QueryPlan plan = buildPlan(workflow.getConfig());
        return planResponse(plan);
    }

    /**
     * 根据未保存配置生成 SQL 文本。旧版 run=true 试跑须等执行隔离完成后才能恢复。
     */
    public Map<String, Object> preview(QueryWorkflowConfig config, boolean run, int limit) {
        if (run) throw new ApiException(403, "当前暂未开放 SQL 试跑，请使用受控表预览");
        authorizeSources(config);
        requireConfigGeneratable(config);
        QueryPlan plan = buildPlan(config);
        return planResponse(plan);
    }

    // =====================================================================
    // 可视化「执行 / 导出」
    // =====================================================================
    //
    // 为什么这两个入口只收 config(而不是 sql + progCols):
    //   程序算子(SM3/3DES/FF1)不生成 SQL,计划必须和 SQL 一起下发。让前端分两处传,
    //   任何一处漏传都会让程序算子被静默跳过(脱敏/加密列直接漏明文)。
    //   这里由后端在同一次调用里把两者一起推导出来 —— 结构上不可能漏。

    /** 按配置生成 SQL 并执行,返回结果集(对应 {@code POST /query-workflows/execute})。 */
    public Map<String, Object> execute(QueryWorkflowConfig config, int page, int pageSize) {
        VisualQuery visual = prepareVisualQuery(config);
        QueryRequest req = new QueryRequest();
        req.setSql(visual.sql);
        req.setProgCols(visual.plan.getProgCols());
        req.setDatabase(visual.database);
        req.setPage(page);
        req.setPageSize(pageSize);
        // 别名 → 源字段:让【结果列】上的字段注释能精确解析
        // (改了别名的输出列在 ClickHouse 里并不存在,按列名查注释必然查不到)
        req.setColumnAliases(columnAliasHints(config));
        QueryResponse resp = queryService.execute(visual.connId, req);

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("sql", visual.sql);
        out.put("progCols", visual.plan.getProgCols());
        out.put("columnComments", resp.getColumnComments());
        out.put("readOnly", resp.getReadOnly());
        out.put("results", resp.getResults());
        out.put("messages", resp.getMessages());
        out.put("resultStatements", resp.getResultStatements());
        out.put("truncated", resp.getTruncated());
        return out;
    }

    /**
     * 按配置准备 CSV 导出(对应 {@code POST /query-workflows/export} 的准备阶段)。
     *
     * <p>返回的是「已探好列、还没写出一个字节」的导出计划,由 Controller 流式写出 ——
     * 与 {@code /connections/{id}/query/export} 同构:响应体是裸 CSV,
     * 一旦写出第一个字节,状态码就改不了了,所以能失败的校验必须留在这一步。
     */
    public QueryService.QueryExportPlan exportPlan(QueryWorkflowConfig config, List<String> columns,
                                                   List<String> headerNames, boolean withHeader,
                                                   String delimiter, Integer limit) {
        VisualQuery visual = prepareVisualQuery(config);
        QueryExportRequest req = new QueryExportRequest();
        req.setSql(visual.sql);
        req.setDatabase(visual.database);
        req.setProgCols(visual.plan.getProgCols());
        req.setColumns(columns);
        req.setHeaderNames(headerNames);
        req.setWithHeader(withHeader);
        req.setDelimiter(delimiter);
        req.setLimit(limit);
        return queryService.prepareExport(visual.connId, req);
    }

    // =====================================================================
    // 运行 / 取消 / 历史
    // =====================================================================

    /**
     * 运行已保存的查询(对应 {@code POST /query-workflows/{id}/run})。
     *
     * <p>与「执行」的区别只有两点:写运行历史、可取消。结果集本身走同一条执行路径。
     *
     * <p>⚡ 并发去重靠数据库的部分唯一索引({@code query_workflow_runs} 上
     * {@code (workflow_id) WHERE status='running'}),不是「先查再插」—— 见仓储层说明。
     */
    public Map<String, Object> run(long id, int page, int pageSize) {
        QueryWorkflow workflow = get(id);
        QueryWorkflowConfig config = workflow.getConfig();
        if (config.getSources() == null || config.getSources().isEmpty()) {
            throw ApiException.validation("未配置源表");
        }
        long connId = config.getSources().get(0).getConnectionId();
        if (connId <= 0) {
            throw ApiException.validation("源表未指定连接");
        }
        QueryPlan plan = buildPlan(config);
        String sql = plan.getSql();

        // 运行记录:插入本身承担并发去重(同一查询同时只允许一条 running)
        long runId;
        try {
            runId = repository.createRun(id);
        } catch (QueryWorkflowRepository.DuplicateRunningException e) {
            throw ApiException.validation("该查询正在运行中,请等待其结束");
        }
        String marker = MARKER_PREFIX + runId + MARKER_SUFFIX;
        RunHandle handle = new RunHandle(connId, marker);
        running.put(runId, handle);

        String status = STATUS_SUCCESS;
        String error = "";
        long rowsRead = 0;
        QueryResponse resp = null;
        RuntimeException failure = null;
        try {
            QueryRequest req = new QueryRequest();
            // 标记只影响 ClickHouse 侧的语句文本(块注释,read/write 判定先剥注释),
            // 记进历史与回给界面的都是不带标记的原 SQL
            req.setSql(marker + sql);
            req.setProgCols(plan.getProgCols());
            req.setDatabase(config.getSources().get(0).getDatabase());
            req.setPage(page);
            req.setPageSize(pageSize);
            resp = queryService.execute(connId, req);
            if (resp.getResults() != null && !resp.getResults().isEmpty()) {
                rowsRead = rowCount(resp.getResults().get(0));
            }
        } catch (RuntimeException e) {
            status = STATUS_FAILED;
            if (handle.cancelled) {
                // 取消是【我们主动发的 KILL】,客户端收到的却是服务端异常
                //(ClickHouse 回的是 "Query was cancelled")。
                // 直接把它记成服务端异常会误导排查方向(Go 侧这里记的是 "context canceled"),
                // 但也不能只写「已取消」—— 那就把「服务端确实停了这条查询」这条证据抹掉了。
                // 故取上游消息的首行(去掉堆栈)附在后面:既是给用户的结论,也是可回归验证的凭据。
                error = CANCELLED_MESSAGE + "(上游: " + brief(Sql.messageOf(e)) + ")";
                failure = ApiException.badGateway(error);
            } else {
                error = Sql.messageOf(e);
                failure = e;
            }
        } finally {
            running.remove(runId, handle);
            // ⚠️ 收尾必须无条件执行:漏掉会让这条记录永远停在 running,
            //    撞上部分唯一索引后该查询【再也跑不起来】,只能等进程重启时由迁移复位
            try {
                repository.finishRun(runId, status, rowsRead, sql, error);
            } catch (RuntimeException e) {
                LOG.warn("[query-workflow] 收尾运行记录失败(runId={}): {}", runId, e.getMessage());
            }
        }
        if (failure != null) {
            throw failure;
        }

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("runId", runId);
        out.put("sql", sql);
        out.put("results", resp == null ? null : resp.getResults());
        out.put("messages", resp == null ? null : resp.getMessages());
        out.put("truncated", resp == null ? null : resp.getTruncated());
        out.put("rowsRead", rowsRead);
        out.put("resultStatements", stripMarker(resp == null ? null : resp.getResultStatements(), marker));
        return out;
    }

    /**
     * 取消一次进行中的运行(对应 {@code POST /query-workflows/runs/{runId}/cancel})。
     *
     * <p>先置取消标记再发 KILL:反过来(先 KILL 后置位)时,run 线程可能已经拿到异常并收尾,
     * 历史里就会记成一条服务端异常 —— 而它其实是我们主动杀的。
     *
     * <p>与 Go 的差异:Go 取消的是本进程的 {@code context},记进历史的是驱动给的
     * {@code context canceled};这里记「已取消」。两者都是 400/成功语义一致、文案不同。
     * 未登记(已结束或本进程没在跑)时与 Go 一样 400。
     */
    public void cancel(long runId) {
        RunHandle handle = running.get(runId);
        if (handle == null) {
            // 与 Go 一致:未登记的运行不可取消(不是 404 —— 运行记录可能存在于历史里)
            throw ApiException.validation("该运行已结束或不可取消");
        }
        handle.cancelled = true;
        CkClient killer = identities.openDedicated(handle.connectionId, null);
        try {
            killer.exec("KILL QUERY WHERE query LIKE '" + handle.marker + "%'");
        } catch (RuntimeException e) {
            // KILL 失败(权限不足、连接不可达)必须让调用方看见:否则界面提示「已取消」而查询还在跑
            handle.cancelled = false;
            throw ApiException.badGateway("取消失败: " + Sql.messageOf(e));
        } finally {
            killer.close();
        }
    }

    /** 运行历史(最新在前)。limit 越界与 Go 同口径:非法值一律回落到默认条数。 */
    public List<QueryWorkflowRun> runs(long id, int limit) {
        int n = (limit > 0 && limit <= RUNS_MAX_LIMIT) ? limit : RUNS_DEFAULT_LIMIT;
        return repository.listRuns(id, n);
    }

    // =====================================================================
    // 内部:配置 → SQL / 计划
    // =====================================================================

    /** 「配置 → SQL + 程序算子计划 + 连接 + 库」四件套,执行与导出共用。 */
    private static final class VisualQuery {
        /** 生成的可执行 SQL。 */
        private final String sql;
        /** 程序算子计划。 */
        private final QueryPlan plan;
        /** 在哪个连接上执行(取第一张源表的连接)。 */
        private final long connId;
        /** 在哪个库下执行(仅用于「列注释优先取哪个库」的判定)。 */
        private final String database;

        VisualQuery(String sql, QueryPlan plan, long connId, String database) {
            this.sql = sql;
            this.plan = plan;
            this.connId = connId;
            this.database = database;
        }
    }

    /** 从配置推导执行所需的四件套;缺源表/缺连接/生成失败都在这里拦住。 */
    private static VisualQuery prepareVisualQuery(QueryWorkflowConfig config) {
        if (config.getSources() == null || config.getSources().isEmpty()) {
            throw ApiException.validation("未配置源表");
        }
        long connId = config.getSources().get(0).getConnectionId();
        if (connId <= 0) {
            throw ApiException.validation("源表未指定连接");
        }
        QueryPlan plan = buildPlan(config);
        return new VisualQuery(plan.getSql(), plan, connId, config.getSources().get(0).getDatabase());
    }

    /** 生成 SQL,失败时按 Go 的文案前缀回报 400。 */
    private static QueryPlan buildPlan(QueryWorkflowConfig config) {
        try {
            return QueryPlanBuilder.build(config);
        } catch (ApiException e) {
            throw new ApiException(e.getStatus(), "生成 SQL 失败: " + e.getMessage());
        }
    }

    /**
     * 保存前的配置校验,失败文案与 Go 一致(「配置无效: …」)。
     *
     * <p>Go 的 {@code ValidateConfig} 就是「试着生成一次 SQL」,这里同构 ——
     * 存一条生成不出 SQL 的配置,等于存一条永远跑不起来的记录。
     */
    private static void requireConfigGeneratable(QueryWorkflowConfig config) {
        try {
            QueryPlanBuilder.validate(config == null ? new QueryWorkflowConfig() : config);
        } catch (ApiException e) {
            throw new ApiException(e.getStatus(), "配置无效: " + e.getMessage());
        }
    }

    /** 请求体 → 实体;名称按本仓库约定先去空白再判空。 */
    private static QueryWorkflow fromInput(QueryWorkflowInput input) {
        String name = input == null || input.getName() == null ? "" : input.getName().trim();
        if (name.isEmpty()) {
            throw ApiException.validation("请输入名称");
        }
        QueryWorkflow workflow = new QueryWorkflow();
        workflow.setName(name);
        workflow.setDescription(input.getDescription() == null ? "" : input.getDescription());
        workflow.setEnabled(input.isEnabled());
        workflow.setConfig(input.getConfig() == null ? new QueryWorkflowConfig() : input.getConfig());
        return workflow;
    }

    /** 浅拷贝配置并改 LIMIT(试跑用)。字段逐个搬运,避免改动请求里的对象。 */
    private static QueryWorkflowConfig withLimit(QueryWorkflowConfig config, int limit) {
        QueryWorkflowConfig copy = new QueryWorkflowConfig();
        copy.setSources(config.getSources());
        copy.setMdnRules(config.getMdnRules());
        copy.setFields(config.getFields());
        copy.setJoin(config.getJoin());
        copy.setWhere(config.getWhere());
        copy.setHaving(config.getHaving());
        copy.setOrderBy(config.getOrderBy());
        copy.setGroupByMdn(config.isGroupByMdn());
        copy.setGroupBy(config.getGroupBy());
        copy.setDistinctKeys(config.getDistinctKeys());
        copy.setLimit(limit);
        return copy;
    }

    /** {@code {sql, progCols}} 响应体。 */
    private static Map<String, Object> planResponse(QueryPlan plan) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("sql", plan.getSql());
        out.put("progCols", plan.getProgCols());
        return out;
    }

    /**
     * 把向导配置里的「输出别名 → 源字段」抽出来,供精确取字段注释。
     *
     * <p>⚠️ 为什么必须由配置提供:向导允许给输出字段改别名(如 mdn → 手机号),
     * 结果列名就是别名 —— 它在 ClickHouse 里不存在,按列名查注释必然查不到。
     * 只收「会输出」的字段:标记为不输出的字段不在结果里,不需要注释。
     */
    private static List<ColumnAliasHint> columnAliasHints(QueryWorkflowConfig config) {
        List<ColumnAliasHint> out = new ArrayList<ColumnAliasHint>();
        List<QueryWorkflowSource> sources = config.getSources();
        List<QueryWorkflowFieldRule> fields = config.getFields();
        if (fields == null || sources == null) {
            return out;
        }
        for (QueryWorkflowFieldRule field : fields) {
            if (field.isHidden() || isBlank(field.getAlias()) || isBlank(field.getSourceCol())) {
                continue;
            }
            if (field.getSourceIdx() < 0 || field.getSourceIdx() >= sources.size()) {
                continue;
            }
            QueryWorkflowSource source = sources.get(field.getSourceIdx());
            ColumnAliasHint hint = new ColumnAliasHint();
            hint.setAlias(field.getAlias());
            hint.setDatabase(source.getDatabase());
            hint.setTable(source.getTable());
            hint.setColumn(field.getSourceCol());
            out.add(hint);
        }
        return out;
    }

    /** 把结果语句里的取消标记去掉(回给界面/存历史的应当是干净 SQL)。 */
    private static List<String> stripMarker(List<String> statements, String marker) {
        if (statements == null) {
            return null;
        }
        List<String> out = new ArrayList<String>(statements.size());
        for (String st : statements) {
            out.add(st != null && st.startsWith(marker) ? st.substring(marker.length()) : st);
        }
        return out;
    }

    /** 第一条结果集的行数(与 Go 一样只取第一条:可视化链路生成的是单条 SELECT)。 */
    private static long rowCount(QueryResult result) {
        return result == null || result.getRows() == null ? 0 : result.getRows().size();
    }

    /** 判空(null 或全空白)。 */
    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /**
     * 把上游报错压成一行短消息(去掉驱动附带的整段堆栈,最多 300 字符)。
     *
     * <p>跑失败的查询由驱动回传的消息里带几十 KB 的 ClickHouse 堆栈,
     * 整条存进运行历史既会撑大 MySQL 记录，也不便于阅读；首行才是结论。
     */
    private static String brief(String message) {
        String s = message == null ? "" : message;
        // 驱动把「查询执行失败: 」这层包装和重复的 DB::Exception 一起拼了上来,去掉噪音
        if (s.startsWith("查询执行失败: ")) {
            s = s.substring("查询执行失败: ".length());
        }
        s = s.replace("DB::ExceptionDB::Exception:", "DB::Exception:");
        int stack = s.indexOf("Stack trace");
        if (stack > 0) {
            s = s.substring(0, stack);
        }
        int newline = s.indexOf('\n');
        if (newline > 0) {
            s = s.substring(0, newline);
        }
        s = s.trim();
        final int max = 300;
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
