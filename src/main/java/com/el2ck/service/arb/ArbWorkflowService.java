/**
 * 【文件职责】本文件定义ARB 查询编排模块的业务服务 ArbWorkflowService。承载该模块的核心业务规则，协调仓储、连接器或外部服务，并统一转换业务异常和操作状态。
 */

package com.el2ck.service.arb;

import com.el2ck.service.connection.ConnectionService;

import com.el2ck.arb.ArbEngine;
import com.el2ck.arb.ArbPlanBuilder;
import com.el2ck.arb.ArbStats;
import com.el2ck.arb.ArbResumableEngine;
import com.el2ck.entity.arb.ArbColumn;
import com.el2ck.entity.arb.ArbField;
import com.el2ck.entity.arb.ArbPlan;
import com.el2ck.entity.arb.ArbProbe;
import com.el2ck.entity.arb.ArbSource;
import com.el2ck.entity.arb.ArbWorkflow;
import com.el2ck.entity.arb.ArbWorkflowInput;
import com.el2ck.entity.arb.ArbWorkflowRun;
import com.el2ck.entity.connection.ConnectionDTO;
import com.el2ck.exception.ApiException;
import com.el2ck.repository.arb.ArbWorkflowRepository;
import com.el2ck.util.Sql;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多源仲裁工作流的编排:校验 + 补默认值 + 执行 + 运行记录,
 * 对应 Go {@code internal/service/arb_workflow_service.go}。
 *
 * <p>本类只做编排与校验:SQL 怎么拼在 {@link ArbPlanBuilder},语句怎么发在 {@link ArbEngine},
 * 配置怎么存取在 {@link ArbWorkflowRepository}。三处共用同一份「配置 → SQL」的推导,
 * 所以「预览看到的、执行跑的就是同一条语句」。
 *
 * <p>⚠️ setup 与 rebuild 是【同一个操作】:都是「建临时表 → 回填 → 原子换名」,天然幂等。
 * action 只影响运行记录里的标签。
 */
@Service
public class ArbWorkflowService {
    @org.springframework.beans.factory.annotation.Autowired
    private com.el2ck.service.auth.ResourceAccessService access;
    @org.springframework.beans.factory.annotation.Autowired
    private com.el2ck.service.auth.ExecutionIdentityService identities;
    @org.springframework.beans.factory.annotation.Autowired
    private com.el2ck.service.ck.CkAccessProvisioner provisioner;

    private static final Logger LOG = LoggerFactory.getLogger(ArbWorkflowService.class);

    /** 删除产物后写到「最近一次执行」里的原因(列表上要能看出表已经不在了)。 */
    private static final String DROPPED_REASON = "产物已删除(聚合表与物化视图)";

    /** 工作流持久层。 */
    private final ArbWorkflowRepository repository;

    /** 执行引擎:建表/建视图/回填/摊平/探测/删产物。 */
    private final ArbEngine engine;

    /** 连接服务:取默认连接(界面上不再让用户选连接)。 */
    private final ConnectionService connSvc;
    private final java.util.concurrent.Semaphore workerSlots = new java.util.concurrent.Semaphore(2);
    private final java.util.Set<Thread> workers = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /**
     * 创建ARB 工作流业务服务并装配后续调用所需的依赖。
     *
     * 依赖由 Spring 容器统一管理；本构造器只保存引用或初始化轻量适配对象，不执行远程业务操作。
     *
     * @param repository 元数据仓储，用于读取或写入平台业务记录
     * @param engine ARB 执行引擎，负责运行工作流并产出执行状态
     * @param connSvc ClickHouse 连接服务，负责解析连接并按执行身份创建客户端
     */
    public ArbWorkflowService(ArbWorkflowRepository repository, ArbEngine engine, ConnectionService connSvc) {
        this.repository = repository;
        this.engine = engine;
        this.connSvc = connSvc;
    }

    // =====================================================================
    // 配置的增删改查
    // =====================================================================

    /** 全部工作流(最新在前)。 */
    public List<ArbWorkflow> list() {
        return repository.list();
    }

    /** 单条;不存在抛 404。 */
    public ArbWorkflow get(long id) {
        ArbWorkflow workflow = repository.get(id);
        if (workflow == null) {
            throw ApiException.notFound("工作流不存在");
        }
        return workflow;
    }

    /** 新建。 */
    public ArbWorkflow create(ArbWorkflowInput in) {
        ArbWorkflow workflow = normalize(in);
        checkNameFree(workflow);
        checkTargetTableFree(workflow);
        long id = repository.create(workflow);
        clearOtherWide(id);
        return repository.get(id);
    }

    /**
     * 更新配置(不动运行状态字段)。
     *
     * <p>先确认存在:否则 UPDATE 影响 0 行却返回成功,界面看起来「保存了」其实没改。
     */
    public ArbWorkflow update(long id, ArbWorkflowInput in) {
        ArbWorkflow old = repository.get(id);
        if (old == null) {
            throw ApiException.notFound("工作流不存在");
        }
        // 没传 isWide 就保持原值 —— 否则「只改启用开关」这种局部更新会把宽表标记抹掉
        if (in.getWide() == null) {
            in.setWide(old.isWide());
        }
        // 没传 enabled 同样保持原值 —— 否则「只改配置」这种局部更新会把【已停用】的工作流
        // 静默重新启用(sum 类工作流会因此重复累加)。Create 不传仍是默认启用,见 normalize。
        if (in.getEnabled() == null) {
            in.setEnabled(old.isEnabled());
        }
        ArbWorkflow workflow = normalize(in);
        workflow.setId(id);
        checkNameFree(workflow);
        checkTargetTableFree(workflow);
        engine.editConfiguration(old, workflow, () -> repository.update(workflow));
        clearOtherWide(id);
        return repository.get(id);
    }

    /** 删除配置(不删 ClickHouse 里的产物);不存在抛 404。 */
    public void delete(long id) {
        ArbWorkflow old = get(id);
        engine.editConfiguration(old, null, () -> {
            if (!repository.delete(id)) throw ApiException.notFound("工作流不存在");
        });
    }

    /**
     * 运行历史。
     *
     * <p>⚠️ 工作流不存在时【不报 404】,返回空列表 —— 与 Go 一致(它直接查运行表)。
     * 调用方(前端)只在详情页里拉历史,拿到的 id 必是存在的。
     */
    public List<ArbWorkflowRun> runs(long id, int limit) {
        List<ArbWorkflowRun> runs = repository.listRuns(id, limit);
        for (ArbWorkflowRun run : runs) decorateProgress(run);
        return runs;
    }

    // =====================================================================
    // 预览与执行
    // =====================================================================

    /**
     * 生成完整 SQL 计划(不连库、不改任何东西)。
     *
     * <p>聚合层是不可逆的写操作,保存/执行前必须能核对「我配的到底是哪些 SQL」。
     * 预览与执行共用同一份生成器产物,所以界面看到的就是真正会跑的。
     */
    public ArbPlan preview(ArbWorkflowInput in) {
        return ArbPlanBuilder.buildPlan(normalize(in));
    }

    /**
     * 执行一次:建聚合层 + 每源 MV + 回填历史。
     *
     * <p>⚠️ 同步执行:回填是分钟级(取决于源表行数),前端把请求超时单独放宽到数分钟。
     * Java 侧不设服务端超时,与「查询工作流导出」同一口径。
     */
    public ArbWorkflowRun apply(long id, String action) {
        // action 只影响运行记录里的标签,底层是同一个操作 —— 都是「全量重算」,所以天然幂等
        String normalized = ArbWorkflowRun.ACTION_REBUILD.equals(action)
                ? ArbWorkflowRun.ACTION_REBUILD : ArbWorkflowRun.ACTION_SETUP;
        ArbWorkflow workflow = get(id);
        requireEnabled(workflow);
        return runLocked(workflow, normalized);
    }

    /**
     * 只把聚合层摊平到服务表。
     *
     * <p>什么时候需要它:聚合表被物化视图实时更新了,而服务表还停在旧值 ——
     * 服务表必须全表 GROUP BY 才能摊,靠 MV 做不到(MV 只看得到刚插入的那批)。
     */
    public ArbWorkflowRun flatten(long id) {
        ArbWorkflow workflow = get(id);
        requireEnabled(workflow);
        return runLocked(workflow, ArbWorkflowRun.ACTION_FLATTEN);
    }

    /**
     * 在可选的本机执行租约内运行或继续工作流。
     *
     * <p>拿到租约后会重新读取最新配置、决定实际动作并创建运行记录；没有租约时
     * 直接执行当前快照。执行函数负责更新运行状态，租约关闭由 try-with-resources
     * 完成。
     *
     * @param w 工作流快照
     * @param action 请求动作
     * @return 本次运行结果
     */
    private ArbWorkflowRun runLocked(ArbWorkflow w, String action) {
        try (ArbResumableEngine.Lease lease = engine.reserve(w.getId())) {
            ArbWorkflow current = lease == null ? w : get(w.getId());
            requireEnabled(current);
            String actualAction = effectiveAction(current.getId(), action);
            long runId = startRun(current.getId(), actualAction, lease != null);
            java.util.function.Supplier<ArbWorkflowRun> work = () -> execute(current, runId, actualAction,
                    () -> ArbWorkflowRun.ACTION_FLATTEN.equals(actualAction) ? engine.flatten(current) : engine.apply(current));
            return lease == null ? work.get() : lease.call(work);
        }
    }

    /** HTTP 与定时入口均只启动后台工作；网络请求结束不影响执行或断点。 */
    public long startBackground(long id, String action) {
        if (!java.util.Arrays.asList("setup", "rebuild", "flatten").contains(action)) throw ApiException.validation("未知工作流动作");
        requireEnabled(get(id));
        if (!workerSlots.tryAcquire()) throw ApiException.validation("工作流执行槽位已满，请等待正在运行的任务完成");
        ArbResumableEngine.Lease lease = null;
        long created = 0;
        try {
            lease = engine.reserve(id);
            ArbWorkflow w = get(id);
            requireEnabled(w);
            String actualAction = effectiveAction(id, action);
            created = startRun(id, actualAction, lease != null);
            final long runId = created;
            final ArbResumableEngine.Lease reserved = lease;
            Thread worker = new Thread(() -> {
                try (ArbResumableEngine.Lease ignored = reserved) {
                    java.util.function.Supplier<ArbWorkflowRun> work = () -> identities.asOwner("arb-workflow", id, () ->
                            execute(w, runId, actualAction, () -> ArbWorkflowRun.ACTION_FLATTEN.equals(actualAction)
                                    ? engine.flatten(w) : engine.apply(w)));
                    if (reserved == null) work.get(); else reserved.call(work);
                } catch (RuntimeException | Error e) {
                    try { repository.failIfRunning(runId, id, Sql.messageOf(e)); }
                    catch (RuntimeException save) { LOG.error("工作流 {} 运行状态写入失败", id, save); }
                    LOG.warn("工作流 {} 停止，已保留断点: {}", id, e.getMessage());
                } finally { workers.remove(Thread.currentThread()); workerSlots.release(); }
            }, "arb-workflow-" + id);
            worker.setDaemon(true);
            workers.add(worker);
            try { worker.start(); } catch (RuntimeException | Error e) { workers.remove(worker); throw e; }
            return runId;
        } catch (RuntimeException | Error e) {
            if (lease != null) lease.close();
            workerSlots.release();
            if (created != 0) repository.failIfRunning(created, id, "启动工作流失败: " + Sql.messageOf(e));
            throw e;
        }
    }
    /**
     * 停止正在执行的业务流程。
     */
    @javax.annotation.PreDestroy
    public void stopWorkers() { for (Thread worker : workers) worker.interrupt(); }

    /**
     * 根据持久化断点决定本次应继续的动作。
     *
     * <p>存在 apply 断点时转为 rebuild，存在 flatten 断点时继续 flatten；
     * 没有未完成动作时沿用调用方请求。
     *
     * @param id 工作流 ID
     * @param requested 请求动作
     * @return 实际执行动作
     */
    private String effectiveAction(long id, String requested) {
        String pending = engine.incompleteAction(id);
        if ("apply".equals(pending)) return ArbWorkflowRun.ACTION_REBUILD;
        if ("flatten".equals(pending)) return ArbWorkflowRun.ACTION_FLATTEN;
        return requested;
    }
    /**
     * 启动业务执行流程。
     *
     * @param id 资源或记录标识
     * @param runId 运行记录 ID
     * @return 读取、创建或转换后的业务对象
     */
    public ArbWorkflowRun runStatus(long id, long runId) {
        get(id);
        ArbWorkflowRun run = repository.getRun(id, runId);
        if (run == null) throw ApiException.notFound("执行记录不存在");
        decorateProgress(run);
        return run;
    }

    /**
     * 为工作流运行记录补充便于前端展示的断点进度。
     *
     * <p>运行中的任务从内存引擎读取实时进度；已结束任务从持久化 SQL 的
     * {@code -- 断点阶段=...} 标记恢复阶段文字。该方法只修改响应对象，不落库；
     * 找不到实时状态时保留原值。
     *
     * @param run 待补充进度的运行记录
     */
    private void decorateProgress(ArbWorkflowRun run) {
        if (ArbWorkflowRun.STATUS_RUNNING.equals(run.getStatus())) run.setProgress(engine.resumeProgress(run.getWorkflowId()));
        else if (run.getSql() != null && run.getSql().startsWith("-- 断点阶段=")) run.setProgress(run.getSql().substring(3));
    }

    /** 采集聚合层现状(表在不在、视图齐不齐、多少行)。 */
    public ArbProbe probe(long id) {
        return engine.probe(get(id));
    }

    /**
     * 删掉 ClickHouse 里的聚合表与所有物化视图(不可逆)。
     *
     * <p>产物没了,「最近一次执行」的快照也要跟着清 —— 否则列表上还挂着「成功 · 900 万行」,
     * 但表已经不存在了。
     */
    public void dropObjects(long id) {
        ArbWorkflow workflow = get(id);
        engine.dropObjects(workflow);
        repository.clearRunState(id, DROPPED_REASON);
    }

    // =====================================================================
    // 后台任务入口
    // =====================================================================

    /**
     * 给定时循环用:逐个检查启用的工作流,落后的服务表摊一次。
     *
     * <p>「落后」= 聚合表的最后写入时间 &gt; 服务表的。聚合表是物化视图实时写的,
     * 所以只要源表有增量、而没人点过刷新,这里就会把它追上。
     *
     * <p>单个工作流连不上库/表被删:跳过并记日志,不影响其它工作流与后续 tick。
     * 返回实际摊平了几个。
     */
    public int flattenStale() {
        int done = 0;
        // 后台线程没有请求身份:先取候选 ID,再逐个以持久化所有者身份执行。
        for (long id : repository.enabledOwnedIds()) {
            if (workerSlots.availablePermits() == 0) break;
            try {
                boolean flattened = identities.asOwner("arb-workflow", id, () -> {
                    ArbWorkflow w = get(id);
                    if (w == null || !w.isEnabled()) return false;
                    if (engine.busy(id)) return false;
                    String pending = engine.pendingAction(id);
                    if ("apply".equals(pending)) startBackground(id, ArbWorkflowRun.ACTION_REBUILD);
                    else if ("flatten".equals(pending) || engine.svcStale(w)) startBackground(id, ArbWorkflowRun.ACTION_FLATTEN);
                    else return false;
                    return true;
                });
                if (flattened) done++;
            } catch (RuntimeException e) {
                // 常见原因:所有者禁用/未完成初始化、上一次还没跑完、配置已失效。
                LOG.warn("工作流 {} 自动摊平失败: {}", id, e.getMessage());
            }
        }
        return done;
    }

    /** 启动时把卡在 running 的记录标记为失败(进程重启导致),返回重置条数。 */
    public int resetStaleRuns() {
        return repository.resetStaleRuns();
    }

    // =====================================================================
    // 默认值补齐与校验
    // =====================================================================

    /**
     * 补默认值 + 校验,返回可直接落库的实体。
     *
     * <p>补默认值这件事放在后端(而不是只让前端补):界面可以有多个入口(向导 / 自动生成 /
     * 手工改),它们都可能漏字段;后端补齐后校验才有意义。
     *
     * <p>⚠️ 会就地修改传入的 {@code in}(与 Go 一致):它是本次请求的反序列化产物,不会被复用。
     */
    ArbWorkflow normalize(ArbWorkflowInput in) {
        if(in==null)throw ApiException.validation("工作流配置不能为空");
        String name = trim(in.getName());
        if (name.isEmpty()) {
            throw ApiException.validation("工作流名称不能为空");
        }

        // 连接:界面上不再让用户选(用户原话:基本信息中也不要连接了),传空就用默认连接。
        // 显式传了合法 id 仍尊重,便于接口直调与测试。
        long connId = in.getConnectionId();
        if (connId <= 0) {
            List<ConnectionDTO> conns = connSvc.list();
            if (conns.isEmpty()) {
                throw ApiException.validation("还没有配任何连接,无法执行");
            }
            // list() 已按 is_default DESC, id ASC 排
            connId = conns.get(0).getId();
        }

        // 界面上【不暴露 CK 的库概念】,统一落到默认库;传什么进来都不认,避免绕过前端写出别的库。
        String db = ArbWorkflow.DEFAULT_DB;
        // 聚合表名:用户只填中间那段,前后缀一律由这里补。
        // 两边的清理都要做 —— 否则用户填了 dwd_abc 就会拼成 dwd_dwd_abc_agg。
        String table = trim(in.getTargetTable());
        if (table.endsWith(ArbWorkflow.AGG_SUFFIX)) {
            table = table.substring(0, table.length() - ArbWorkflow.AGG_SUFFIX.length());
        }
        if (table.startsWith(ArbWorkflow.PREFIX)) {
            table = table.substring(ArbWorkflow.PREFIX.length());
        }
        table = trim(table);
        if (table.isEmpty()) {
            throw ApiException.validation("聚合表名不能为空");
        }
        table = ArbWorkflow.PREFIX + table;

        // ── 源:补 id / 默认启用 / 补优先级 ──
        List<ArbSource> sources = in.getSources() == null ? new ArrayList<>() : in.getSources();
        for (int i = 0; i < sources.size(); i++) {
            ArbSource s = sources.get(i);
            if (trim(s.getId()).isEmpty()) {
                s.setId("s" + i);
            }
            if (trim(s.getDatabase()).isEmpty()) {
                s.setDatabase("ods_data");
            }
            access.table(connId,s.getDatabase(),s.getTable(),true);
            // 界面上只能启用:想临时摘掉一个源,现在的做法是在界面上删掉它
            s.setEnabled(true);
        }
        // 全都没填优先级时按选择顺序给一组默认值(第一个最权威)。
        // 只补【全空】的情况:否则用户手动调成「后加的源优先级高」会被改回去。
        if (!sources.isEmpty()) {
            boolean allZero = true;
            for (ArbSource s : sources) {
                if (s.getPri() != 0) {
                    allZero = false;
                    break;
                }
            }
            if (allZero) {
                for (int i = 0; i < sources.size(); i++) {
                    sources.get(i).setPri(sources.size() - i);
                }
            }
        }

        // ── 字段:补目标列名 ──
        List<ArbField> fields = in.getFields() == null ? new ArrayList<>() : in.getFields();
        for (ArbField f : fields) {
            if (trim(f.getOutName()).isEmpty()) {
                f.setOutName(f.getCol());
            }
        }

        // ── 目标列:字段里出现过的目标列自动补一条(默认仲裁);已配的保留 ──
        Map<String, ArbColumn> byName = new LinkedHashMap<>();
        for (ArbColumn c : in.getColumns() == null ? new ArrayList<ArbColumn>() : in.getColumns()) {
            if (trim(c.getName()).isEmpty()) {
                continue;
            }
            if (trim(c.getMode()).isEmpty()) {
                c.setMode(ArbColumn.MODE_ARBITRATE);
            }
            // 同名以最后一条为准,但顺序按首次出现 —— 用户手改了取值方式不想让列跳动
            byName.put(c.getName(), c);
        }
        for (ArbField f : fields) {
            if (!byName.containsKey(f.getOutName())) {
                ArbColumn c = new ArbColumn();
                c.setName(f.getOutName());
                c.setMode(ArbColumn.MODE_ARBITRATE);
                byName.put(f.getOutName(), c);
            }
        }

        ArbWorkflow w = new ArbWorkflow();
        w.setName(name);
        w.setDescription(trim(in.getDescription()));
        w.setEnabled(in.getEnabled() == null || in.getEnabled());
        w.setConnectionId(connId);
        w.setTargetDb(db);
        w.setTargetTable(table);
        w.setSources(sources);
        w.setFields(fields);
        w.setColumns(new ArrayList<>(byName.values()));
        w.setWide(in.getWide() != null && in.getWide());

        // 完整校验复用生成器:它报的错已经具体到「哪个源哪个字段」
        ArbPlanBuilder.validate(w);
        return w;
    }

    // =====================================================================
    // 唯一性
    // =====================================================================

    /**
     * 工作流名称不能重复。
     *
     * <p>为什么在服务层查而不是加唯一索引:库里可能已经有重名数据(索引会建不上),
     * 而且目标表那条也是这么做的 —— 口径一致。并发创建同名仍可能同时通过,
     * 但那是极端情况,真发生也只是列表里两条同名,不影响数据正确性。
     */
    private void checkNameFree(ArbWorkflow w) {
        for (ArbWorkflow o : repository.list()) {
            if (o.getId() == w.getId()) {
                continue;
            }
            if (equalsIgnoreCaseTrim(o.getName(), w.getName())) {
                throw ApiException.validation(String.format(
                        "名字「%s」已经有一条工作流在用了,换一个名字吧(大小写不同也算重名)", w.getName()));
            }
        }
    }

    /**
     * 同一张目标表只能有一个工作流。
     *
     * <p>为什么必须管:物化视图名(mv_&lt;目标表&gt;_&lt;源id&gt;)和临时表名都由【目标表名】派生。
     * 两条工作流指向同一张表时,后一条会 DROP 掉前一条的视图后建自己的 —— 前一条的增量静默断流,
     * 而它的「检查」按名字比对仍然显示"视图齐全",谁也看不出问题。
     * (界面上的「一键生成草稿」固定用目标表 dwd_all,连点两次就会撞,实测踩过。)
     */
    private void checkTargetTableFree(ArbWorkflow w) {
        if(repository.targetUsedByOther(w))throw ApiException.validation("目标表已被其他工作流使用，请选择独立目标表");
    }

    /**
     * 同一时刻只允许一个宽表:新存/新标记的那张留下,别的清掉。
     *
     * <p>为什么强制唯一:宽表页签要「直接显示那一个」,两个标记就没法决定显示谁。
     * 清标记只是改配置,不动任何数据(那张工作流照旧运行)。
     *
     * <p>为什么先确认「被保存的这条自己就是宽表」:否则在列表里点一条普通工作流的启用开关
     * (前端会带上 isWide:false)就会把真正的宽表标记抹掉,宽表页签无表可显示。
     */
    private void clearOtherWide(long keepId) {
        ArbWorkflow keep = repository.get(keepId);
        if (keep == null || !keep.isWide()) {
            return;
        }
        repository.clearOtherWideFlag(keepId);
    }

    // =====================================================================
    // 运行记录
    // =====================================================================

    /** 工作流停用时拒绝执行(报错要能直接告诉用户下一步做什么)。 */
    private void requireEnabled(ArbWorkflow w) {
        if (!w.isEnabled()) {
            throw ApiException.validation("工作流已停用,请先启用再执行");
        }
        // 配置保存后源表可能被撤权；每次执行重新授权，不能仅依赖创建时的检查。
        for (ArbSource source : w.getSources()) {
            if (source.isEnabled()) access.table(w.getConnectionId(), source.getDatabase(), source.getTable(), true);
        }
    }

    /**
     * 建运行记录,并把并发拒绝翻译成给用户看的文案。
     *
     * <p>只有「并发执行」才是那句可读文案:磁盘满/库锁之类的写库故障照原样包出来,
     * 否则用户会照着一句误导的提示(「已有一次执行在进行中」)反复重试,而真正的原因一个字也看不到。
     */
    private long startRun(long workflowId, String action, boolean exclusivelyLocked) {
        try {
            return exclusivelyLocked ? repository.createRun(workflowId, action, true) : repository.createRun(workflowId, action);
        } catch (ArbWorkflowRepository.AlreadyRunningException e) {
            throw ApiException.validation("已有一次执行在进行中(同一工作流不能并发执行)");
        } catch (RuntimeException e) {
            throw ApiException.server("写入运行记录失败: " + Sql.messageOf(e));
        }
    }

    /** 执行体:跑一次、无条件收尾、把失败原样抛出去。 */
    private ArbWorkflowRun execute(ArbWorkflow w, long runId, String action, ArbCall call) {
        ArbStats stats = null;
        RuntimeException failure = null;
        try {
            stats = call.run();
            // 产物已在 CK 中;把服务表登记为资产并授予工作流所有者读写权限。
            ensureResultAsset(w);
        } catch (RuntimeException e) {
            failure = e;
        }
        long rows = stats == null ? 0 : stats.getRows();
        String sql = stats == null ? "" : stats.getSql();
        String progress = stats == null ? "" : stats.getProgress();
        if (stats == null) {
            try { progress = engine.resumeProgress(w.getId()); }
            catch (RuntimeException ignored) { /* 日志查询不能掩盖真正的任务错误。 */ }
        }
        String status = failure == null ? ArbWorkflowRun.STATUS_SUCCESS : ArbWorkflowRun.STATUS_FAILED;
        String errMsg = failure == null ? "" : Sql.messageOf(failure);

        // 落库失败不能掩盖真正的运行错误,但也不能悄悄丢掉 —— 两条都报出来
        try {
            repository.finishRun(runId, w.getId(), status, rows, sql, errMsg, progress);
        } catch (RuntimeException ferr) {
            if (failure != null) {
                throw ApiException.badGateway(errMsg + "(另外:写运行记录也失败:"
                        + Sql.messageOf(ferr) + ")");
            }
            throw ApiException.server("写运行记录失败: " + Sql.messageOf(ferr));
        }
        if (failure != null) {
            throw failure;
        }
        ArbWorkflowRun run = new ArbWorkflowRun();
        run.setId(runId);
        run.setWorkflowId(w.getId());
        run.setAction(action);
        run.setStatus(status);
        run.setRows(rows);
        run.setSql(sql);
        run.setProgress(progress);
        // Go 的零值就是空串,前端类型里 startedAt 是必填字符串 —— 留 null 会让它拿到 null
        run.setStartedAt("");
        return run;
    }

    /**
     * 工作流服务表是平台受控产物:执行成功后登记资产并授予所有者读写权限,
     * 这样「目标库表」页面才按该用户的 CK 身份看到并查询它。
     */
    private void ensureResultAsset(ArbWorkflow w) {
        long owner = com.el2ck.config.AuthContext.userId();
        long assetId = access.registerOrGetTable("clickhouse", w.getConnectionId(),
                w.getTargetDb(), w.svcTableName(), owner);
        // 服务表是 View,底层聚合表也需要通过同一读取角色授权。
        provisioner.grantOwner(assetId, w.aggTableName());
        // 聚合表也登记为资产,「查看聚合表」按钮和排查页面需要按用户身份打开它。
        long aggAssetId = access.registerOrGetTable("clickhouse", w.getConnectionId(),
                w.getTargetDb(), w.aggTableName(), owner);
        provisioner.grantOwner(aggAssetId);
    }

    /** 执行体的函数式接口(只需能跑、能抛)。 */
    private interface ArbCall {
        ArbStats run();
    }

    // =====================================================================
    // 小工具
    // =====================================================================

    /**
     * 去除首尾空白，null 转为空串。
     *
     * @param s 原始文本
     * @return 规范化文本
     */
    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    /** 去空白后忽略大小写比较(重名判定用)。 */
    private static boolean equalsIgnoreCaseTrim(String a, String b) {
        return trim(a).equalsIgnoreCase(trim(b));
    }
}
