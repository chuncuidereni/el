/**
 * 【文件职责】本文件定义工作流编排模块的业务服务 WorkflowService。承载该模块的核心业务规则，协调仓储、连接器或外部服务，并统一转换业务异常和操作状态。
 */

package com.el2ck.service.workflow;

import com.el2ck.exception.ApiException;
import com.el2ck.ckpool.CkPool;
import com.el2ck.duck.Duck;
import com.el2ck.entity.workflow.WfEdge;
import com.el2ck.entity.workflow.WfNode;
import com.el2ck.entity.workflow.WfRun;
import com.el2ck.entity.workflow.WfRunDTO;
import com.el2ck.entity.workflow.WfRunLog;
import com.el2ck.entity.workflow.WfTask;
import com.el2ck.entity.workflow.WorkflowDTO;
import com.el2ck.entity.workflow.WorkflowInput;
import com.el2ck.workflow.Engine;
import com.el2ck.repository.metadata.MetadataRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 工作流业务逻辑(对应 Go internal/service/workflow_service.go)。
 *
 * 工作流 = 节点(nodes: datasource/filter/map/dedup/code/ck_write)+ 连线(edges),以 JSON 存取。
 * 本类负责 CRUD 与轻量校验(节点 ID/类型/连线引用),触发运行并落 wf_runs + 每节点 wf_run_logs:
 * 运行前为每个节点登记 running 日志 → {@link Engine} 拓扑执行 → 汇总状态与各节点 rows/耗时/错误。
 */
public class WorkflowService {
    @org.springframework.beans.factory.annotation.Autowired
    private com.el2ck.service.auth.ResourceAccessService access;

    /** 按工作流串行化启动，避免并发点击运行造成重复执行。 */
    private static final ConcurrentMap<Long, Object> RUN_GATES = new ConcurrentHashMap<>();

    // 元数据仓库(工作流 CRUD / 运行历史落库)
    private final MetadataRepository repository;
    // 工作流执行引擎(负责 DAG 拓扑执行)
    private final Engine engine;

    // 通用 JSON 序列化器(节点/连线 JSON 互转)
    private static final ObjectMapper JSON = new ObjectMapper();
    /**
     * 创建同步工作流业务服务并装配后续调用所需的依赖。
     *
     * 依赖由 Spring 容器统一管理；本构造器只保存引用或初始化轻量适配对象，不执行远程业务操作。
     *
     * @param repository 元数据仓储，用于读取或写入平台业务记录
     * @param pool ClickHouse 客户端池，用于管理共享连接资源
     * @param duck DuckDB 辅助组件，用于本地文件与结构化查询处理
     * @param secret 应用主密钥，用于配置解密和完整性摘要计算
     * @param identities ClickHouse 用户身份服务，用于按任务所有者或当前用户解析执行身份
     */
    public WorkflowService(MetadataRepository repository, CkPool pool, Duck duck, String secret,
                           com.el2ck.service.ck.CkIdentityService identities) {
        // 注入元数据仓库
        this.repository = repository;
        // 构造引擎实例(复用连接池与 duckdb 运行时;CK 写入使用任务所有者身份)
        this.engine = new Engine(repository, pool, duck, secret, identities);
    }

    // =====================================================================
    // DTO 解析 / 列表
    // =====================================================================

    /** 任务实体 → DTO:回填最近一次运行状态并解析节点/连线 JSON。 */
    private WorkflowDTO toDTO(WfTask w) {
        // 构造 DTO,逐字段拷贝任务基本属性
        WorkflowDTO dto = new WorkflowDTO();
        dto.setId(w.getId());
        dto.setName(w.getName());
        dto.setDescription(w.getDescription());
        dto.setEnabled(w.isEnabled());
        // 取最近一次运行记录,用于回填“上次运行”信息
        WfRun latest = repository.latestWfRun(w.getId());
        // 存在历史运行才回填上次状态与启动时间
        if (latest != null) {
            dto.setLastRunStatus(latest.getStatus());
            dto.setLastRunStartedAt(latest.getStartedAt());
        }
        // 回填创建时间
        dto.setCreatedAt(w.getCreatedAt());
        dto.setUpdatedAt(w.getUpdatedAt());
        try {
            // 反序列化节点 JSON(失败视为数据损坏抛服务器错误)
            List<WfNode> nodes = JSON.readValue(w.getNodesJson(),
                    new com.fasterxml.jackson.core.type.TypeReference<List<WfNode>>() {
                    });
            // 回填节点列表;解析为 null 给空列表
            dto.setNodes(nodes == null ? new ArrayList<WfNode>() : nodes);
        } catch (Exception e) {
            throw ApiException.server(String.format("工作流 \"%s\" 节点解析失败: %s", w.getName(), msg(e)));
        }
        try {
            // 反序列化连线 JSON
            List<WfEdge> edges = JSON.readValue(w.getEdgesJson(),
                    new com.fasterxml.jackson.core.type.TypeReference<List<WfEdge>>() {
                    });
            // 回填连线列表;解析为 null 给空列表
            dto.setEdges(edges == null ? new ArrayList<WfEdge>() : edges);
        } catch (Exception e) {
            throw ApiException.server(String.format("工作流 \"%s\" 连线解析失败: %s", w.getName(), msg(e)));
        }
        return dto;
    }

    /** 工作流列表。 */
    public List<WorkflowDTO> list() {
        List<WorkflowDTO> out = new ArrayList<>();
        // 遍历全部工作流并逐个转换为 DTO
        for (WfTask w : repository.listWorkflows()) {
            out.add(toDTO(w));
        }
        return out;
    }

    /** 单个工作流。 */
    public WorkflowDTO get(long id) {
        // 按主键取工作流实体
        WfTask w = repository.getWorkflow(id);
        // 不存在则返回 404
        if (w == null) {
            throw ApiException.notFound("记录不存在");
        }
        return toDTO(w);
    }

    // =====================================================================
    // CRUD
    // =====================================================================

    /**
     * 校验并规范化工作流草稿。
     *
     * <p>方法会原地去除名称首尾空白；节点允许暂不配置，但每个节点必须具有唯一 ID 和合法类型，
     * 并按类型执行轻量必填校验，其中数据源归属和目标表权限会立即检查。连线两端必须引用已声明
     * 节点。这里不验证完整 DAG 可执行性，也不持久化数据，运行前仍会执行完整校验。
     *
     * @param in 待校验的工作流输入
     * @throws com.el2ck.exception.ApiException 输入、节点、权限或连线不合法时抛出
     */
    private void validate(WorkflowInput in) {
        // 请求对象为空直接拒绝
        if (in == null) {
            throw ApiException.validation("请求内容不能为空");
        }
        if (in.getName() != null) {
            // 名称先去首尾空白再校验
            in.setName(in.getName().trim());
        }
        // 工作流名称必填
        if (in.getName() == null || in.getName().isEmpty()) {
            throw ApiException.validation("工作流名称不能为空");
        }
        // 节点允许为空(先建任务后进画布编排),完整性运行时再校验
        List<WfNode> nodes = in.getNodes() == null ? new ArrayList<WfNode>() : in.getNodes();
        // 记录已出现的节点 ID,用于查重与连线引用检查
        Map<String, Boolean> seen = new LinkedHashMap<>();
        // 逐节点校验
        for (WfNode n : nodes) {
            // 空节点元素拒绝
            if (n == null) {
                throw ApiException.validation("工作流节点不能为空");
            }
            // 取节点 ID(def 兜底空串)
            String id = def(n.getId());
            // 每个节点都必须有 ID
            if (id.isEmpty()) {
                throw ApiException.validation("存在未设置 ID 的节点");
            }
            // 节点 ID 不允许重复
            if (seen.containsKey(id)) {
                throw ApiException.validation(String.format("节点 ID \"%s\" 重复", id));
            }
            // 登记该节点 ID
            seen.put(id, Boolean.TRUE);
            // 取节点类型
            String type = def(n.getType());
            // 节点类型必须在白名单内(数据源/过滤/映射/去重/代码/写入)
            if (!WfNode.validType(type)) {
                throw ApiException.validation(String.format("节点 \"%s\" 类型不合法", id));
            }
            // 按节点类型做必填配置校验(轻量,执行时仍有完整校验)
            validateWfNodeConfig(n);
        }
        // 连线列表(可缺省为空)
        List<WfEdge> edges = in.getEdges() == null ? new ArrayList<WfEdge>() : in.getEdges();
        // 逐条校验连线
        for (WfEdge ed : edges) {
            // 空连线元素拒绝
            if (ed == null) {
                throw ApiException.validation("工作流连线不能为空");
            }
            // 连线两端必须引用已声明的节点 ID
            if (!seen.containsKey(ed.getFrom()) || !seen.containsKey(ed.getTo())) {
                throw ApiException.validation(String.format("连线引用了不存在的节点(%s → %s)", ed.getFrom(), ed.getTo()));
            }
        }
    }

    /** 节点配置必填校验(轻量;执行时仍有完整校验)。 */
    private void validateWfNodeConfig(WfNode n) {
        // 配置缺省视为空 Map
        Map<String, Object> cfg = n.getConfig() == null ? new LinkedHashMap<String, Object>() : n.getConfig();
        if (WfNode.TYPE_MAP.equals(n.getType()) || WfNode.TYPE_CK_WRITE.equals(n.getType())) {
            validateMappings(cfg, WfNode.TYPE_MAP.equals(n.getType()) ? "map" : "write");
        }
        // 按节点类型分派必填项校验
        switch (n.getType()) {
            case WfNode.TYPE_DATASOURCE:
                // 数据源节点必须已选数据源
                if (cfgInt(cfg.get("dataSourceId")) <= 0) {
                    throw ApiException.validation("数据源节点未选择数据源");
                }
                access.requireOwner("datasource",cfgInt(cfg.get("dataSourceId")));
                break;
            // 写入节点分支:校验目标连接/库表/字段映射
            case WfNode.TYPE_CK_WRITE:
                // 写入节点必须已选目标连接
                if (cfgInt(cfg.get("connectionId")) <= 0) {
                    throw ApiException.validation("写入节点未选择目标连接");
                }
                // 目标库与表均必填
                if (str(cfg.get("db")).trim().isEmpty() || str(cfg.get("table")).trim().isEmpty()) {
                    throw ApiException.validation("写入节点需配置目标库与表");
                }
                access.table(cfgInt(cfg.get("connectionId")),str(cfg.get("db")),str(cfg.get("table")),true);
                // 字段映射必须为列表且非空
                if (!(cfg.get("mapping") instanceof List) || ((List<?>) cfg.get("mapping")).isEmpty()) {
                    throw ApiException.validation("写入节点未配置字段映射");
                }
                validateIncrementalConfig(cfg);
                break;
            default:
                break;
        }
    }

    /**
     * 将配置中的数值或十进制字符串安全转换为 long。
     *
     * <p>无法解析的字符串、null 和其它类型均返回 0，调用方依赖后续范围校验判断
     * 是否有效；该方法不会抛数字格式异常。
     *
     * @param v 原始配置值
     * @return long 值或 0
     */
    private static long cfgInt(Object v) {
        return com.el2ck.util.ReferenceId.parse(v);
    }

    /**
     * 将可空配置值转换为字符串，null 转为空串。
     *
     * @param v 原始值
     * @return 字符串表示
     */
    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    /** 增量键可选;填写时必须是已配置映射中的数字类型字段。 */
    private static void validateIncrementalConfig(Map<String, Object> cfg) {
        // 非增量模式不涉及水位过滤,无需校验增量键
        if (!"incremental".equalsIgnoreCase(str(cfg.get("mode")).trim())) {
            return;
        }
        String incr = str(cfg.get("incrColumn")).trim();
        // 增量键留空表示每次全量追加、不计算水位,属合法配置
        if (incr.isEmpty()) {
            return;
        }
        Object raw = cfg.get("mapping");
        // 在映射中查找同名源列以校验其类型;遍历完仍未命中则由方法末尾的 throw 兜底报错
        if (raw instanceof List) {
            for (Object item : (List<?>) raw) {
                if (item instanceof Map) {
                    Map<?, ?> mapping = (Map<?, ?>) item;
                    if (incr.equals(str(mapping.get("sourceColumn")).trim())) {
                        if (!isNumericType(str(mapping.get("type")))) {
                            throw ApiException.validation("增量字段必须是可按数值排序的数字类型: " + incr);
                        }
                        return;
                    }
                }
            }
        }
        throw ApiException.validation("增量字段必须存在于字段映射中: " + incr);
    }

    /** 目标类型是否为可按数值排序的数字类型(剥掉 nullable() 包裹后判定)。 */
    private static boolean isNumericType(String value) {
        String type = value == null ? "" : value.trim().toLowerCase();
        while (type.startsWith("nullable(") && type.endsWith(")")) {
            type = type.substring(9, type.length() - 1).trim();
        }
        return type.matches("(u?int8|u?int16|u?int32|u?int64|float32|float64|decimal.*|numeric.*)");
    }

    /** 持久化前校验映射:源列可为中文,目标列必须是用户填写的合法英文标识符。 */
    private static void validateMappings(Map<String, Object> cfg, String label) {
        Object raw = cfg.get("mapping");
        if (!(raw instanceof List) || ((List<?>) raw).isEmpty()) {
            throw ApiException.validation(label + " node mapping is required");
        }
        Set<String> targets = new HashSet<>();
        for (Object item : (List<?>) raw) {
            if (!(item instanceof Map)) {
                throw ApiException.validation(label + " node mapping is invalid");
            }
            Map mapping = (Map) item;
            String source = str(mapping.get("sourceColumn")).trim();
            String target = str(mapping.get("targetColumn")).trim();
            // 前端未选择时可能把 null 序列化成字面量 "null",与空串一样按“未填”处理
            if (source.isEmpty() || target.isEmpty() || "null".equalsIgnoreCase(source) || "null".equalsIgnoreCase(target)) {
                throw ApiException.validation(label + " node source and target columns are required");
            }
            if (!target.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw ApiException.validation("Target column must be a valid English identifier: " + target);
            }
            if (!targets.add(target)) {
                throw ApiException.validation("Duplicate target column: " + target);
            }
            // nullable 缺省 true:执行层据此拼建表 DDL,未显式声明时按可空列处理
            if (!mapping.containsKey("nullable") || mapping.get("nullable") == null) {
                mapping.put("nullable", Boolean.TRUE);
            }
            Object type = mapping.get("type");
            if (type != null && ("Bool".equalsIgnoreCase(String.valueOf(type).trim())
                    || "Boolean".equalsIgnoreCase(String.valueOf(type).trim()))) {
                mapping.put("type", "UInt8");
            }
        }
    }

    /** 新增工作流。 */
    public WorkflowDTO create(WorkflowInput in) {
        // 先做整体校验
        validate(in);
        // 构造工作流实体
        WfTask w = new WfTask();
        // 把入参写入实体并序列化节点/连线 JSON
        apply(w, in);
        // 落库,取回自动生成的主键
        long id = repository.createWorkflow(w);
        // 回填主键以便生成 DTO
        w.setId(id);
        return toDTO(w);
    }

    /** 更新工作流。 */
    public WorkflowDTO update(long id, WorkflowInput in) {
        // 取原工作流(不存在返回 404)
        WfTask old = repository.getWorkflow(id);
        if (old == null) {
            throw ApiException.notFound("记录不存在");
        }
        // 校验入参
        validate(in);
        // 在原实体上覆盖可更新字段
        apply(old, in);
        // 更新落库
        repository.updateWorkflow(old);
        return toDTO(old);
    }

    /** 删除工作流。 */
    public void delete(long id) {
        // 目标不存在返回 404
        if (repository.getWorkflow(id) == null) {
            throw ApiException.notFound("记录不存在");
        }
        // 存在正在运行的实例时禁止删除
        if (repository.hasRunningWfRun(id)) {
            throw ApiException.validation("工作流正在运行，完成后才能删除");
        }
        // 落库删除
        repository.deleteWorkflow(id);
    }

    /** 把入参覆盖到任务实体(规范写入节点映射,序列化节点/连线 JSON)。 */
    private void apply(WfTask w, WorkflowInput in) {
        // 节点缺省为空列表
        List<WfNode> nodes = in.getNodes() == null ? new ArrayList<WfNode>() : in.getNodes();
        // 连线缺省为空列表
        List<WfEdge> edges = in.getEdges() == null ? new ArrayList<WfEdge>() : in.getEdges();
        // 保存前先规范化写入节点的字段映射类型
        normalizeWriteMappings(nodes);
        String nodesJson;
        String edgesJson;
        try {
            // 把节点列表序列化为 JSON 字符串存储
            nodesJson = JSON.writeValueAsString(nodes);
            // 把连线列表序列化为 JSON 字符串存储
            edgesJson = JSON.writeValueAsString(edges);
        } catch (Exception e) {
            // 序列化失败按服务器错误抛出
            throw ApiException.server("工作流序列化失败: " + msg(e));
        }
        // 回填任务名称等可更新字段
        w.setName(in.getName());
        w.setDescription(def(in.getDescription()));
        w.setNodesJson(nodesJson);
        w.setEdgesJson(edgesJson);
        // 启用开关统一按 Boolean.TRUE 判定,避免空指针
        w.setEnabled(Boolean.TRUE.equals(in.getEnabled()));
    }

    /** 保存工作流时即规范写入节点映射，避免 Bool 类型流入执行层。 */
    private static void normalizeWriteMappings(List<WfNode> nodes) {
        // 遍历所有节点
        for (WfNode node : nodes) {
            // 仅处理带配置的“写入”节点,其余跳过
            if (node == null || node.getConfig() == null
                    || !(WfNode.TYPE_CK_WRITE.equals(node.getType()) || WfNode.TYPE_MAP.equals(node.getType()))) {
                continue;
            }
            // 读取该节点的字段映射配置
            Object raw = node.getConfig().get("mapping");
            // 映射非列表(未配置)则跳过
            if (!(raw instanceof List)) {
                continue;
            }
            // 逐条处理映射项
            for (Object item : (List<?>) raw) {
                // 映射项必须为 Map(JSON 对象)
                if (!(item instanceof Map)) {
                    continue;
                }
                // 取映射条目本身(保留原 Map 以改写 type)
                Map mapping = (Map) item;
                // 读取映射声明的目标类型
                Object type = mapping.get("type");
                // Bool / Boolean 一律归一为 UInt8,避免布尔类型流入执行层
                if (type != null && ("Bool".equalsIgnoreCase(String.valueOf(type).trim())
                        || "Boolean".equalsIgnoreCase(String.valueOf(type).trim()))) {
                    // 覆盖写入规范后的类型
                    mapping.put("type", "UInt8");
                }
            }
        }
    }

    // =====================================================================
    // 运行
    // =====================================================================

    /** 启动工作流(异步执行,返回 runId 供轮询历史)。 */
    public long startRun(long id) {
        // 按主键取工作流实体
        WfTask w = repository.getWorkflow(id);
        // 不存在则返回 404
        if (w == null) {
            throw ApiException.notFound("记录不存在");
        }
        // 未启用不允许触发运行
        if (!w.isEnabled()) {
            throw ApiException.validation("工作流未启用");
        }
        Object gate = RUN_GATES.computeIfAbsent(id, key -> new Object());
        final long runId;
        synchronized (gate) {
            // 检查与创建 running 记录必须在同一临界区内，避免并发点击重复执行。
            if (repository.hasRunningWfRun(id)) {
                throw ApiException.validation("工作流正在运行中");
            }
            // 构造运行记录实体
            WfRun run = new WfRun();
            // 绑定所属工作流 ID
            run.setWorkflowId(id);
            // 先落一条 running 记录,拿回 runId 供轮询
            runId = repository.createWfRun(run);
        }
        // 后台执行(与 Go go s.execute 等价)。
        // 捕获传给后台线程的常量,避免竞态
        final long rid = runId;
        // 固化任务快照,运行期间不受后续编辑影响
        final WfTask snapshot = w;
        // 另起守护线程执行任务(与 Go go s.execute 等价)
        Thread th = new Thread(() -> execute(rid, snapshot), "wf-run-" + rid);
        // 标记守护线程,不阻塞应用退出
        th.setDaemon(true);
        // 启动后台执行线程
        th.start();
        return runId;
    }

    /** 后台执行工作流并落历史(含各节点日志)。 */
    private void execute(long runId, WfTask w) {
        // 记录当前工作线程,便于超时后发起中断
        final Thread worker = Thread.currentThread();
        // 创建单线程定时器池(守护线程),专用于超时兜底
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
            // 按命名规则创建守护线程
            Thread t = new Thread(r, "wf-timeout-" + runId);
            t.setDaemon(true);
            return t;
        });
        // 预约 60 分钟后中断工作线程,防止任务无限挂起
        ScheduledFuture<?> timeout = timer.schedule(worker::interrupt, 60, TimeUnit.MINUTES);
        // 1. 先登记各节点日志(运行中)。
        // 预解析节点列表(失败仅跳过日志预登记)
        List<WfNode> nodes = new ArrayList<>();
        // 节点 ID → 预登记日志 ID 的映射
        Map<String, Long> logIds = new LinkedHashMap<>();
        try {
            // 尝试反序列化任务中的节点 JSON
            nodes = JSON.readValue(w.getNodesJson(), new com.fasterxml.jackson.core.type.TypeReference<List<WfNode>>() {
            });
        } catch (Exception ignored) {
            // 节点解析失败在引擎侧会再报错;这里仅跳过日志预登记。
        }
        // 为每个节点预登记一条 running 状态日志
        for (WfNode n : nodes) {
            // 构造节点日志实体并绑定 runId/节点信息
            WfRunLog l = new WfRunLog();
            l.setRunId(runId);
            l.setNodeId(n.getId());
            l.setNodeType(n.getType());
            try {
                // 落库日志并缓存其自增 ID,供结束回填
                logIds.put(n.getId(), repository.createWfRunLog(l));
            } catch (RuntimeException ignored) {
                // 日志创建失败不阻断运行。
            }
        }

        // 2. 执行引擎。
        // 构造运行完成记录,用于写回状态与错误
        WfRun finish = new WfRun();
        finish.setId(runId);
        List<Engine.NodeResult> results;
        String status;
        try {
            // 调用引擎按 DAG 拓扑执行全流程
            results = engine.run(w);
            // 正常结束标记为成功
            status = "success";
        // 捕获引擎业务异常:保留已产生的各节点结果
        } catch (Engine.WfException e) {
            results = e.results;
            status = "failed";
            // 记录引擎给出的错误信息
            finish.setError(e.getMessage());
        // 捕获其他运行时异常:整批视为失败
        } catch (RuntimeException e) {
            results = new ArrayList<>();
            status = "failed";
            finish.setError(msg(e));
        // 兜底捕获 Error(如 OOM),确保运行记录不会被卡死
        } catch (Throwable t) {
            results = new ArrayList<>();
            status = "failed";
            finish.setError("Error: " + t);
            // 打印兜底错误便于排查
            System.err.println("[wf-run-" + runId + "] Error: " + t);
        }
        // 把汇总状态写回完成记录
        finish.setStatus(status);
        try {
            // 落运行历史(失败静默)
            repository.finishWfRun(finish);
        } catch (RuntimeException ignored) {
            // 运行记录落库失败不抛出。
        }

        // 先在“节点日志回填”之前撤掉超时中断并回收定时器线程,避免回填途中被打断
        timeout.cancel(false);
        timer.shutdownNow();
        // 清除工作线程中断标记,避免影响后续逻辑
        Thread.interrupted();

        // 3. 节点日志落库。
        // 逐个节点把执行结果回填到预登记日志
        for (Engine.NodeResult r : results) {
            // 用预登记日志 ID 定位该节点日志
            Long logId = logIds.get(r.nodeId);
            if (logId == null) {
                continue;
            }
            // 构造日志更新实体并回填指标
            WfRunLog l = new WfRunLog();
            l.setId(logId);
            l.setNodeId(r.nodeId);
            l.setNodeType(r.nodeType);
            l.setRowsIn(r.rowsIn);
            l.setRowsOut(r.rowsOut);
            l.setDurationMs(r.durationMs);
            // 节点执行出错:日志记为失败并写错误信息
            if (r.err != null) {
                // 标记该节点失败
                l.setStatus("failed");
                l.setError(msg(r.err));
            } else {
                // 节点成功执行则日志记为成功
                l.setStatus("success");
            }
            try {
                // 单条节点日志落库(失败忽略)
                repository.finishWfRunLog(l);
            } catch (RuntimeException ignored) {
                // 忽略单条日志落库失败。
            }
        }
    }

    /** 某工作流运行历史(含每节点日志,最近 20 条)。 */
    public List<WfRunDTO> runs(long workflowId) {
        // 工作流不存在返回 404
        if (repository.getWorkflow(workflowId) == null) {
            throw ApiException.notFound("记录不存在");
        }
        List<WfRunDTO> out = new ArrayList<>();
        // 取最近 20 条运行记录并逐条转 DTO
        for (WfRun run : repository.listWfRuns(workflowId, 20)) {
            // 组装运行 DTO 的基本字段
            WfRunDTO dto = new WfRunDTO();
            dto.setId(run.getId());
            dto.setWorkflowId(run.getWorkflowId());
            dto.setStatus(run.getStatus());
            dto.setStartedAt(run.getStartedAt());
            dto.setFinishedAt(run.getFinishedAt());
            dto.setError(run.getError());
            // 附带该次运行的逐节点日志
            dto.setLogs(repository.listWfRunLogs(run.getId()));
            out.add(dto);
        }
        return out;
    }

    // =====================================================================
    // 工具
    // =====================================================================

    /** null 兜底空串。 */
    private static String def(String s) {
        return s == null ? "" : s;
    }

    /** 提取异常描述:沿 cause 链取首个带消息的层,否则兜底 toString。 */
    private static String msg(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && (t.getMessage() == null || t.getMessage().isEmpty())) {
            t = t.getCause();
        }
        String m = t.getMessage();
        return (m == null || m.isEmpty()) ? String.valueOf(e) : m;
    }
}
