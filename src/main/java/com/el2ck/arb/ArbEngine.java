package com.el2ck.arb;

/**
 * 【文件职责】封装 ARB 聚合层与服务视图的编排、DDL 执行和运行统计，是工作流引擎使用的 ClickHouse 操作门面。
 */

import com.el2ck.ckpool.CkClient;
import com.el2ck.ckpool.QueryResult;
import com.el2ck.entity.arb.ArbColumn;
import com.el2ck.entity.arb.ArbProbe;
import com.el2ck.entity.arb.ArbSource;
import com.el2ck.entity.arb.ArbSourceProbe;
import com.el2ck.entity.arb.ArbWorkflow;
import com.el2ck.exception.ApiException;
import com.el2ck.service.connection.ConnectionService;
import com.el2ck.util.Sql;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 多源仲裁工作流的执行(建聚合层 + 每源 MV + 回填 / 摊平 / 探测 / 删产物),
 * 对应 Go {@code internal/sync/engine_arb.go}。SQL 怎么拼全在 {@link ArbPlanBuilder}。
 *
 * <h3>为什么是「先停 MV → 清空 → 回填 → 再建 MV」这个顺序</h3>
 *
 * MV 是 INSERT 触发器,回填期间源表若正好有新写入,那批数据会被 MV 和回填各算一次。
 * 仲裁类(max)幂等没事,sum 类会翻倍。所以回填前先把 MV 删掉,回填完再建回来 ——
 * 建 MV 是纯元数据操作(毫秒),代价可以忽略,换来的是重建结果【严格正确】。
 *
 * <p>⚠️ 窗口期内【确实会丢】:回填读的是启动那一刻的快照,快照之后、该源 MV 建回来之前
 * 写入的行,既不在快照里也没人接 —— 要等下一次执行才补上。窗口长度 = 回填耗时(大源表不是
 * 毫秒级)。所以别在源表频繁写入时反复重建。
 *
 * <h3>为什么走临时表 + EXCHANGE 而不是「清空原表再回填」</h3>
 *
 * 回填中途失败(源表改了、列名对不上、类型不符……)会留下一个【空了一半的表】,而且物化视图
 * 已经停掉了、没人接增量。现在的顺序任何一步失败旧数据都毫发无损:
 * <pre>
 * ① 建临时表 &lt;目标&gt;_agg__arb_new_&lt;id&gt;(结构复制自当前聚合表)
 * ② 停掉所有 MV        —— 否则回填窗口内源表的写入会被 MV 和回填各算一次(sum 翻倍)
 * ③ 逐源回填 → 临时表
 * ④ EXCHANGE 原子换名(首次则 RENAME)
 * ⑤ 删掉换出来的旧表
 * ⑥ 重建 MV            —— 接住之后的增量
 * </pre>
 *
 * <p>⚠️ 失败路径上会【尽力把 MV 建回去】:否则增量会静默断流,而用户很可能以为只是
 * 「这次没跑成功」。没建回来的那些会拼进返回的错误 + stats 的 SQL 备注,否则「视图没建回来」
 * 这件事在运行记录里毫无痕迹。
 *
 * <h3>与 Go 的一处实现差异(超时模型)</h3>
 *
 * Go 每个语句都带 {@code context};收尾恢复 MV 时专门用 {@code context.WithoutCancel}
 * 换一个全新的 30 秒上下文(走到那里时传进来的 ctx 往往已经超时,那正是最常见的失败原因)。
 * Java 侧没有请求上下文与语句绑定,不存在「ctx 已死」这个最坏情况;每条语句的等待上限由
 * {@link CkClient} 的语句超时(60 秒)兜底。恢复 MV 因此直接用同一个客户端,不做额外包装。
 */
@Component
public class ArbEngine {

    @org.springframework.beans.factory.annotation.Autowired
    private ArbResumableEngine resumable;


/**
 * 返回界面应提示的待处理动作。
 *
 * <p>存在未完成断点时返回其动作；已完成但需要重新摊平时返回 {@code flatten}；
 * 无断点或已完成且无需刷新时返回空串。恢复组件未装配时也返回空串。
 *
 * @param id 工作流 ID
 * @return 待处理动作或空串
 * @throws IllegalStateException 读取持久化断点失败时抛出
 */
    public String pendingAction(long id) { return resumable == null ? "" : resumable.pendingAction(id); }

/**
 * 返回工作流断点的可读进度摘要。
 *
 * <p>摘要包含阶段、已确认批次、当前阶段行数、自动重试次数和本轮耗时。
 * 恢复组件未装配或工作流没有断点时返回空串。
 *
 * @param id 工作流 ID
 * @return 进度摘要或空串
 * @throws IllegalStateException 读取持久化断点失败时抛出
 */
    public String resumeProgress(long id) { return resumable == null ? "" : resumable.progress(id); }

/**
 * 为工作流执行获取一个持有 MySQL 命名锁的租约。
 *
 * <p>返回的 {@link ArbResumableEngine.Lease} 取得执行权并拥有数据库锁；调用方必须使用
 * try-with-resources 或在所有路径关闭租约。恢复组件未装配时返回 {@code null}，此时没有
 * 取得锁。锁采用非等待模式，其他实例或线程正在执行同一工作流时立即失败。
 *
 * @param id 工作流 ID
 * @return 已取得锁的执行租约；恢复组件不可用时返回 {@code null}
 * @throws ArbCheckpointStore.BusyException 同一工作流已被其他执行占用时抛出
 * @throws IllegalStateException 打开断点会话或查询锁失败时抛出
 */
    public ArbResumableEngine.Lease reserve(long id) { return resumable == null ? null : resumable.reserve(id); }

/**
 * 查询工作流当前是否由任意实例或线程执行。
 *
 * <p>恢复组件未装配时返回 {@code false}；否则通过数据库命名锁判断执行状态。
 * 该方法只查询，不会获取或释放锁。
 *
 * @param id 工作流 ID
 * @return 命名锁被持有时返回 {@code true}，否则返回 {@code false}
 * @throws IllegalStateException 数据库锁状态查询失败时抛出
 */
    public boolean busy(long id) { return resumable != null && resumable.busy(id); }

/**
 * 返回尚未完成的工作流断点动作。
 *
 * <p>只有断点存在且 {@code complete=false} 时才返回动作；已完成、无断点或恢复组件
 * 未装配时均返回空串。
 *
 * @param id 工作流 ID
 * @return 未完成动作或空串
 * @throws IllegalStateException 读取持久化断点失败时抛出
 */
    public String incompleteAction(long id) { return resumable == null ? "" : resumable.incompleteAction(id); }


/**
 * 在串行化执行上下文中修改或删除工作流配置。
 *
 * <p>恢复组件可用时先取得 {@code old.getId()} 对应的命名锁：未完成断点仅允许保持
 * 相同配置指纹的修改；已完成断点会先清理其临时对象。随后在仍持有锁期间执行
 * {@code edit}，因此同一工作流的并发执行会立即失败。{@code next == null} 表示删除，
 * 回调完成后会清理断点。此方法不自动开启数据库事务，回调自身负责其写入的一致性。
 *
 * @param old 修改前的工作流，用于取得执行锁和清理旧临时对象
 * @param next 修改后的工作流；为 {@code null} 时表示删除
 * @param edit 实际执行配置写入或删除的回调
 * @throws ArbCheckpointStore.BusyException 工作流正被其他执行占用时抛出
 * @throws IllegalStateException 未完成断点与目标配置不兼容、锁失效或清理失败时抛出
 * @throws RuntimeException {@code edit} 自身抛出的异常原样向调用方传播
 */
    public void editConfiguration(ArbWorkflow old, ArbWorkflow next, Runnable edit) {
        if (resumable == null) edit.run();
        else resumable.editConfiguration(old, next, edit);
    }

    /** 原有结构漂移检查和孤儿 MV 清理继续沿用；每次恢复均先停止旧 MV。 */
    private void prepareResume(CkClient client, ArbWorkflow w, java.util.function.Consumer<String> execute) {
        if (tableExists(client, w.getTargetDb(), w.aggTableName())) {
            List<String> drift = aggDrift(client, w);
            if (!drift.isEmpty()) throw ApiException.validation("聚合列类型发生变化，请清理已生成的数据后重建: " + String.join(",", drift));
        }
        for (ArbSource source : w.getSources()) execute.accept(ArbPlanBuilder.buildDropMvSql(w, source));
        OrphanScan scan = orphanMvs(client, w);
        for (String name : scan.orphans) execute.accept("DROP VIEW IF EXISTS " + quoteCol(w.getTargetDb()) + "." + quoteCol(name));
        if (!scan.unparsed.isEmpty()) LOG.warn("工作流 {} 存在无法确认归属的 MV: {}", w.getId(), scan.unparsed);
    }

    private static final Logger LOG = LoggerFactory.getLogger(ArbEngine.class);

    /** 重建期间临时表名的基础后缀(实际名字还会带上工作流 ID)。 */
    private static final String TEMP_SUFFIX = "__arb_new";

    /** 从 system.tables.create_table_query 里抠出物化视图的 TO 目标(库.表)。 */
    private static final Pattern MV_TO_TABLE_RE =
            Pattern.compile("\\bTO\\s+`?([A-Za-z_][\\w]*)`?\\.`?([A-Za-z_][\\w]*)`?\\s");

    /** 连接服务:管理身份(默认连接/只读判定)。 */
    private final ConnectionService connSvc;

    /** 当前工作流所有者的真实 CK 身份。 */
    private final com.el2ck.service.ck.CkIdentityService identities;


    public ArbEngine(ConnectionService connSvc, com.el2ck.service.ck.CkIdentityService identities) {
        this.connSvc = connSvc;
        this.identities = identities;
    }

    // =====================================================================
    // 执行:建聚合层 + 每源 MV + 回填(setup 与 rebuild 是同一个操作)
    // =====================================================================

    /**
     * 把工作流应用到位:建聚合层 + 每源 MV + 回填历史。
     *
     * <p>这是一次【全量重算】:先把数据灌进临时表,再原子换名,所以重复执行安全且幂等 ——
     * 「初始化」和「重建聚合层」是同一个操作,只是叫法不同。
     */
    public ArbStats apply(ArbWorkflow w) {
        if (resumable != null) return resumable.run(w, false, (client, execute) -> prepareResume(client, w, execute));
        if (w == null) {
            throw ApiException.validation("工作流为空");
        }
        // 先跑一遍完整校验(源/字段/列/类型),错在配置上就别动库
        ArbPlanBuilder.buildPlan(w);
        if (w.getConnectionId() <= 0) {
            throw ApiException.validation("工作流未指定连接");
        }
        CkClient client = targetClient(w.getConnectionId());

        long start = System.currentTimeMillis();
        RunRecorder rec = new RunRecorder();
        ArbStats stats = rec.stats();

        String dbQ = quoteCol(w.getTargetDb());
        String aggQ = dbQ + "." + quoteCol(w.aggTableName());
        String tmpName = tempName(w.aggTableName(), w.getId());
        String tmpQ = dbQ + "." + quoteCol(tmpName);

        // 目标表存在与否决定走 EXCHANGE 还是 RENAME
        boolean existed = tableExists(client, w.getTargetDb(), w.aggTableName());
        // 改过取值方式的列不会被自动改类型 —— 动手之前先查清楚,别把错数据写进去
        if (existed) {
            List<String> drift = aggDrift(client, w);
            if (!drift.isEmpty()) {
                throw ApiException.validation(String.format(
                        "这几列的取值方式和聚合表里已有的类型对不上:%s。改取值方式只影响【新建】的表,"
                                + "已有列的状态类型不会跟着变(老 part 里的聚合状态是另一种),硬灌只会算错。"
                                + "请先「清理已生成的数据」再重新「生成 / 更新」——新表会按当前配置建",
                        String.join("、", drift)));
            }
        }

        // ① 先确保库存在(界面上不暴露库概念,库里没这个库就自动建)
        rec.runWrapped(client, "CREATE DATABASE IF NOT EXISTS " + dbQ, "创建数据库失败");

        // ② 临时表(结构复制自当前表;表还不存在时用生成的 DDL)
        rec.runWrapped(client, "DROP TABLE IF EXISTS " + tmpQ, "清理残留临时表失败");
        if (existed) {
            rec.runWrapped(client, "CREATE TABLE " + tmpQ + " AS " + aggQ, "建临时聚合表失败");
        } else {
            String ddl = ArbPlanBuilder.buildAggDdl(w, tmpName);
            rec.runWrapped(client, ddl, "建临时聚合表失败");
        }

        // ②b 给临时表补上新增的目标列。
        //
        // CREATE TABLE tmp AS agg 只复制【当前结构】,后来加的目标列不会在里面 ——
        // 少了这一步,「加一列」(并入新源带来新字段就是这种情形)会在回填时报
        // 「No such column」,整次执行失败(实测踩过)。
        //
        // ⚠️ 生成语句本身也要包在 try 里:它在下面那句 dropQuietly 之前求值,
        //    抛出来就会把这张临时表留在库里(与 Go 一致:构建失败也要先清理)。
        List<String> aggAlters;
        try {
            aggAlters = ArbPlanBuilder.buildAggAlterSql(w, tmpName);
        } catch (RuntimeException e) {
            dropQuietly(client, tmpQ);
            throw e;
        }
        for (String alter : aggAlters) {
            if (!rec.tryRun(client, alter)) {
                dropQuietly(client, tmpQ);
                throw ApiException.badGateway("给聚合表补新列失败: " + rec.lastError());
            }
        }

        // ③ 停掉所有 MV(包括已停用源的 —— 它们可能还挂在源表上)
        for (ArbSource s : w.getSources()) {
            rec.runWrapped(client, ArbPlanBuilder.buildDropMvSql(w, s), "停用物化视图失败");
        }

        // ③b 清掉【配置外】的 MV:从配置里删掉一个源之后,它的 MV 还留在库里继续往聚合表灌数据。
        //
        // 不管的后果(实测过):删掉的源"又活了" —— 重建后的历史里没它,但它之后写入的行会进来;
        // 而且 Probe 只按配置里的名字比对,永远显示"视图齐全",界面上根本看不到它。
        // 更糟的是它 TO 的旧聚合表被 DROP 之后,那张源表自身的 INSERT 会连带报错。
        // ⚠️ try 只包【列名单】那一步(查询失败不该让工作流完全跑不了);
        //    DROP 本身失败必须让整次执行失败 —— 吞掉的后果正是上面注释里那个故障:
        //    废弃的 MV 还在往聚合表灌数据,而运行记录显示成功,用户从界面上再也看不到它。
        //    与 Go 一致(engine_arb.go 的 orphanArbMVs 分支)。
        OrphanScan scan = null;
        try {
            scan = orphanMvs(client, w);
        } catch (RuntimeException e) {
            rec.note("注意:未能列出配置外的物化视图:" + Sql.messageOf(e));
        }
        if (scan != null) {
            for (String name : scan.orphans) {
                rec.runWrapped(client, "DROP VIEW IF EXISTS " + dbQ + "." + quoteCol(name),
                        "清理废弃物化视图 " + name + " 失败");
            }
            if (!scan.unparsed.isEmpty()) {
                // 名字像自己那套、但 TO 解析不出归属:绝不删(很可能正是别人的视图),但必须留痕
                rec.note("注意:以下物化视图的 TO 目标未能从 DDL 解析,已跳过删除(请人工核对):"
                        + String.join(", ", scan.unparsed));
            }
        }

        // ④ 逐源回填 → 临时表
        try {
            backfill(client, w, tmpQ, rec);
        } catch (RuntimeException e) {
            dropQuietly(client, tmpQ);
            throw restoreMvs(client, w, rec, e);
        }

        // ⑤ 原子换名
        if (existed) {
            if (!rec.tryRun(client, "EXCHANGE TABLES " + aggQ + " AND " + tmpQ)) {
                dropQuietly(client, tmpQ);
                throw restoreMvs(client, w, rec,
                        ApiException.badGateway("替换聚合表失败(旧表未受影响): " + rec.lastError()));
            }
            // 此刻 tmpQ 指向的是【旧数据】,删它
            if (!rec.tryRun(client, "DROP TABLE " + tmpQ)) {
                // 换名已经成功,旧表删不掉只是留垃圾,不该让整个运行算失败;但记一下,
                // 否则孤儿表攒多了下次 EXCHANGE 命名冲突时很难回溯到是哪次留下的。
                LOG.warn("工作流 {} 清理临时表 {} 失败(忽略): {}", w.getId(), tmpQ, rec.lastError());
            }
        } else {
            if (!rec.tryRun(client, "RENAME TABLE " + tmpQ + " TO " + aggQ)) {
                dropQuietly(client, tmpQ);
                throw restoreMvs(client, w, rec,
                        ApiException.badGateway("创建聚合表失败: " + rec.lastError()));
            }
        }

        // ⑤b 聚合表的表注释:换名之后它是正式表了,把工作流名写上去(纯元数据,幂等)
        String aggComment = ArbPlanBuilder.aggTableCommentSql(w);
        if (!aggComment.isEmpty()) {
            if (!rec.tryRun(client, aggComment)) {
                throw restoreMvs(client, w, rec,
                        ApiException.badGateway("写聚合表说明失败: " + rec.lastError()));
            }
        }

        // ⑥ 重建 MV(接住增量)
        for (ArbSource s : w.getSources()) {
            if (!s.isEnabled()) {
                continue;
            }
            String sql;
            try {
                sql = ArbPlanBuilder.buildMvSql(w, s);
            } catch (ApiException e) {
                // ③ 已经把 MV 全删了:这里不恢复就会出现【部分源从此不再收增量】的静默断流
                throw restoreMvs(client, w, rec, e);
            }
            if (!rec.tryRun(client, sql)) {
                throw restoreMvs(client, w, rec,
                        ApiException.badGateway("建物化视图失败(源 " + s.getTable() + "): " + rec.lastError()));
            }
        }

        // ⑦ 摊平成服务表(普通列,业务只查这张)
        //
        // 服务表的新列由 flatten 自己处理(先 CREATE TABLE IF NOT EXISTS 建出来、再逐列 ADD COLUMN),
        // 所以这里不用再补 —— 重复补一次在首次执行时会因为"表还没建"直接报 Could not find table。
        //
        // 为什么必须在这里再跑一次:物化视图是 INSERT 触发器,看不到全表,
        // 所以服务表没法自己跟着聚合表长(二级 MV 只会摊出「看着正常的错值」)。
        // 归到「生成/更新」里是为了让用户一键拿到能查的东西。
        try {
            flattenInternal(client, w, rec);
        } catch (RuntimeException e) {
            throw ApiException.badGateway("聚合层已更新,但摊平服务表失败: " + Sql.messageOf(e));
        }

        // ⑧ 行数:报【服务表】的行数(= 收敛后的号码数)。
        //    聚合表的物理行数含未合并的部分(2000 vs 1000 那种),拿它当「产出多少」很误导。
        stats.setRows(countRowsForStats(client, w));
        stats.setElapsedMs(System.currentTimeMillis() - start);
        return stats;
    }

    /**
     * 只把聚合层摊平到服务表(不碰源表、不重建 MV)。
     *
     * <p>用途:聚合层已被物化视图实时更新,但服务表还停在旧值 —— 点一下这个就好。
     * 只读聚合表,比重跑回填便宜得多(回填要扫全部源表)。
     */
    public ArbStats flatten(ArbWorkflow w) {
        if (resumable != null) return resumable.run(w, true, (client, execute) -> prepareResume(client, w, execute));
        if (w == null) {
            throw ApiException.validation("工作流为空");
        }
        ArbPlanBuilder.buildPlan(w);
        CkClient client = targetClient(w.getConnectionId());

        long start = System.currentTimeMillis();
        RunRecorder rec = new RunRecorder();
        flattenInternal(client, w, rec);
        rec.stats().setRows(countRowsForStats(client, w));
        rec.stats().setElapsedMs(System.currentTimeMillis() - start);
        return rec.stats();
    }

    // =====================================================================
    // 摊平
    // =====================================================================

    /**
     * 摊平的实际动作(apply 与 flatten 共用)。
     *
     * <p>两条路径:
     * <pre>
     * 有 sum 列 → 物理服务表(MergeTree):补库/补表/补列 → 灌数 → 临时表 + EXCHANGE 原子换名。
     * 无 sum 列 → 服务视图(View):临时 VIEW → 零行验证 → Atomic RENAME 原子替换。零存储、自动同步。
     * </pre>
     *
     * <p>⚠️ 物理表路径不能裸 {@code INSERT INTO 服务表 SELECT …}:服务表是普通 MergeTree,
     * 裸 INSERT 是【追加】,同一个号码会出现旧值 + 新值两行。
     *
     * <p>优化:服务表为空(不存在或刚建好)时,没有读者会被打扰,跳过临时表 + EXCHANGE,
     * 直接 INSERT INTO 服务表 —— 省掉一次 CREATE TABLE AS + 一次 EXCHANGE + 一次 DROP,
     * 千万级数据能省几秒到十几秒。
     */
    private void flattenInternal(CkClient client, ArbWorkflow w, RunRecorder rec) {
        String dbQ = quoteCol(w.getTargetDb());
        String svcQ = dbQ + "." + quoteCol(w.svcTableName());

        rec.runWrapped(client, "CREATE DATABASE IF NOT EXISTS " + dbQ, "创建数据库失败");

        // ── 无 sum 列:用 VIEW 替代物理表(零存储、自动同步、不需要灌数) ──
        if (!ArbPlanBuilder.workflowHasSum(w)) {
            // 新定义先落在临时 VIEW 下并执行零行查询验证；只有验证通过后才把正式名称原子
            // 换到新对象。旧 VIEW 或旧物理表在新对象提交前始终可读，任何失败路径都不会先删目标。
            publishServiceView(client, w, rec);
            return;
        }
        // ── 有 sum 列:物理服务表 ──
        // 同样先生成 DDL 再删(理由见上面 VIEW 分支的注释)。
        String ddl = ArbPlanBuilder.buildSvcDdl(w);
        // 可能残留的旧 VIEW(以前没有 sum 列,后来加了)→ 先清掉,否则 CREATE TABLE 冲突
        if (svcIsView(client, w)) {
            rec.runWrapped(client, "DROP VIEW IF EXISTS " + svcQ, "清理旧服务视图失败");
        }
        rec.runWrapped(client, ddl, "建服务表失败");
        for (String alter : ArbPlanBuilder.buildSvcAlterSql(w)) {
            rec.runWrapped(client, alter, "给服务表补列失败");
        }
        for (String comment : ArbPlanBuilder.buildSvcCommentSql(w)) {
            rec.runWrapped(client, comment, "给服务表补说明失败");
        }

        String sel = ArbPlanBuilder.buildSvcSelect(w);
        List<String> cols = ArbPlanBuilder.buildSvcColumns(w);
        List<String> quoted = new ArrayList<>();
        for (String c : cols) {
            quoted.add(quoteCol(c));
        }
        String colList = String.join(", ", quoted);

        // 服务表有旧数据时必须走临时表 + EXCHANGE(裸 INSERT 是追加,会出双份);
        // 空表直接写,省掉一次 CREATE AS + EXCHANGE + DROP。
        if (!svcHasData(client, w)) {
            rec.runWrapped(client, "INSERT INTO " + svcQ + " (" + colList + ")\n" + sel, "摊平写入失败");
            return;
        }
        String tmpName = tempName(w.svcTableName(), w.getId());
        String tmpQ = dbQ + "." + quoteCol(tmpName);
        rec.runWrapped(client, "DROP TABLE IF EXISTS " + tmpQ, "清理残留临时表失败");
        rec.runWrapped(client, "CREATE TABLE " + tmpQ + " AS " + svcQ, "建临时服务表失败");
        if (!rec.tryRun(client, "INSERT INTO " + tmpQ + " (" + colList + ")\n" + sel)) {
            dropQuietly(client, tmpQ);
            throw ApiException.badGateway("摊平写入失败(旧服务表未受影响): " + rec.lastError());
        }
        if (!rec.tryRun(client, "EXCHANGE TABLES " + svcQ + " AND " + tmpQ)) {
            dropQuietly(client, tmpQ);
            throw ApiException.badGateway("替换服务表失败(旧表未受影响): " + rec.lastError());
        }
        if (!rec.tryRun(client, "DROP TABLE " + tmpQ)) {
            LOG.warn("工作流 {} 清理临时表 {} 失败(忽略): {}", w.getId(), tmpQ, rec.lastError());
        }
    }

    /**
     * 通过 {@link CkClient} 发布无 sum 工作流的服务视图。
     *
     * <p>临时名带工作流 ID 与随机 token，避免同一条工作流并发执行或上一次异常退出时
     * 互相覆盖。数据库引擎、存在性检查、创建、零行验证、原子交换和首次发布换名都复用当前 CkClient 与 RunRecorder：
     * 不额外建连接，也不会绕开既有超时、只读连接限制和运行 SQL 记录。
     */
    private void publishServiceView(CkClient client, ArbWorkflow w, RunRecorder rec) {
        String temporaryTable = serviceObjectName(w.svcTableName(), w.getId(), "new");
        ArbServiceViewPublisher.publish(w, temporaryTable, new ArbServiceViewPublisher.Gateway() {
            @Override
            public boolean exists(String database, String table) {
                return existsTable(client, database, table, "检查服务对象是否存在失败");
            }

            @Override
            public String databaseEngine(String database) {
                return ArbEngine.this.databaseEngine(client, database);
            }

            @Override
            public void execute(String sql, String errorPrefix) {
                rec.runWrapped(client, sql, errorPrefix);
            }

            @Override
            public void validate(String sql, String errorPrefix) {
                rec.runQueryWrapped(client, sql, errorPrefix);
            }

            @Override
            public void cleanup(String database, String table) {
                dropServiceObjectQuietly(client, database, table);
            }
        });
    }

    // =====================================================================
    // 回填与 MV 恢复
    // =====================================================================

    /** 逐源把历史数据灌进指定表(启用源才有)。 */
    private void backfill(CkClient client, ArbWorkflow w, String dstQ, RunRecorder rec) {
        for (ArbSource s : w.getSources()) {
            if (!s.isEnabled()) {
                continue;
            }
            String sql = ArbPlanBuilder.buildBackfillSql(w, s);
            // 回填目标由参数决定(临时表),所以把语句里的目标表名换掉 —— 只换第一处,
            // 也就是语句开头那个 INSERT INTO 的目标,别动 SELECT 里的东西。
            sql = replaceFirst(sql,
                    quoteCol(w.getTargetDb()) + "." + quoteCol(w.aggTableName()), dstQ);
            if (!rec.tryRun(client, sql)) {
                throw ApiException.badGateway("回填源 " + s.getTable() + " 失败: " + rec.lastError());
            }
        }
    }

    /**
     * 尽力把物化视图建回去,返回【没能建回来】的源(源表名 + 原因)。
     *
     * <p>失败路径专用:MV 一旦没建回来,源表之后的写入就【静默】不再进聚合层,而界面只会显示
     * 「这次运行失败」—— 下次跑成功之前数据一直是断的。所以【必须】把这份清单拼进返回的错误里:
     * 失败的视图连一行痕迹都不留,用户会以为「只是这次没跑成功」。
     */
    private RuntimeException restoreMvs(CkClient client, ArbWorkflow w, RunRecorder rec, RuntimeException cause) {
        List<String> failed = new ArrayList<>();
        for (ArbSource s : w.getSources()) {
            if (!s.isEnabled()) {
                continue;
            }
            try {
                client.exec(ArbPlanBuilder.buildMvSql(w, s));
            } catch (RuntimeException e) {
                failed.add(String.format("源 %s(%s)", s.getTable(), Sql.messageOf(e)));
            }
        }
        if (failed.isEmpty()) {
            return cause;
        }
        String msg = mvFailNote(failed);
        rec.note(msg);
        // 保留原始状态码:配置类错误(400)不该因为「顺手恢复 MV」被翻成 502 上游故障 ——
        // 那会把「你配错了」显示成「目标库挂了」,排查方向直接跑偏。
        int status = cause instanceof ApiException ? ((ApiException) cause).getStatus() : 502;
        return new ApiException(status, Sql.messageOf(cause) + "\n" + msg);
    }

    /** 组「物化视图没能恢复」的提示(错误文本与 SQL 备注共用同一份)。 */
    static String mvFailNote(List<String> failed) {
        if (failed == null || failed.isEmpty()) {
            return "";
        }
        return String.format("§ 另外:%d 个源的物化视图未能恢复(增量已断流,请重新执行一次):%s",
                failed.size(), String.join("、", failed));
    }

    /**
     * 库里【TO 指向本工作流聚合表】、但已不在配置里的物化视图。
     *
     * <p>名字前缀("mv_&lt;目标表&gt;_")只用来【粗筛】,免得把库里别人那套名字全拉进来解析;
     * 最终归属由 TO 判定(见 {@link #mvTargetsTable})。不能用 LIKE 粗筛:{@code _} 在 LIKE 里
     * 是通配符,目标表名自带的下划线会多匹配到别人。
     *
     * <p>{@link #unparsed} 里的是「名字命中粗筛、但 TO 无法解析」的视图,调用方【不删】,
     * 只用来留痕 —— 解析不出归属就绝不猜。
     */
    private static final class OrphanScan {
        private final List<String> orphans = new ArrayList<>();
        private final List<String> unparsed = new ArrayList<>();
    }

    /**
     * 扫出库里「已不属于本工作流配置」的物化视图,分两栏返回。
     *
     * <p>流程:先按名字前缀 mv_&lt;目标表&gt;_ 在 system.tables 里【粗筛】出候选,再逐个判定归属——
     * 粗筛只为缩小解析范围(MV 的 DDL 解析不便宜),归属【只认 TO 指向本工作流聚合表】
     * (见 {@link #mvTargetsTable});名字在前缀集合里、TO 却解析不出归属的,进 {@link OrphanScan#unparsed}
     * 而不进 orphans,调用方据此【只留痕不删】。
     *
     * <p>为何用 Set 记配置名:配置里同一源可能因别名等产生同名 MV,去重后比对,避免把已配置的视图
     * 误判成孤儿而 DROP(那是静默断流)。
     *
     * <p>注意本方法只【列名单】、不执行删除;查询本身失败应被调用方容忍(见 run / probe 的 try),
     * 因为「列不出孤儿」不该让整次工作流跑不起来,而真正 DROP 失败必须让执行失败。
     */
    private OrphanScan orphanMvs(CkClient client, ArbWorkflow w) {
        OrphanScan scan = new OrphanScan();
        QueryResult res = client.query(
                "SELECT name, create_table_query FROM system.tables WHERE database = ?"
                        + " AND engine = 'MaterializedView' AND startsWith(name, ?)",
                java.util.Arrays.asList(w.getTargetDb(), "mv_" + w.getTargetTable() + "_"));
        Set<String> configured = new LinkedHashSet<>();
        for (ArbSource s : w.getSources()) {
            configured.add(ArbPlanBuilder.mvName(w, s));
        }
        for (List<Object> row : res.getRows()) {
            if (row.size() < 2) {
                continue;
            }
            String name = String.valueOf(row.get(0));
            String ddl = String.valueOf(row.get(1));
            if (configured.contains(name)) {
                continue;
            }
            if (mvTargetsTable(ddl, w.getTargetDb(), w.aggTableName())) {
                scan.orphans.add(name);
            } else if (!MV_TO_TABLE_RE.matcher(ddl).find()) {
                scan.unparsed.add(name);
            }
        }
        return scan;
    }

    /**
     * 这张物化视图的 TO 目标是不是 (db, aggTable)。
     *
     * <p>为什么必须看 TO、不能靠名字前缀:视图名规则是 mv_&lt;目标表&gt;_&lt;源id&gt;,而前缀【不是身份】——
     * 目标表 dwd_a 的 "mv_dwd_a_" 同时也是目标表 dwd_a_2 的 "mv_dwd_a_2_…" 的前缀。
     * 只看前缀时,目标表 dwd_a 的工作流会把 dwd_a_2 那套视图当成「自己配置外的孤儿」DROP 掉:
     * 对方的增量从此静默断流,而对方的 Probe 只按配置名比对,仍然显示「视图齐全」,谁也看不出问题。
     * TO 的目标表是视图的【实际归属】(每条工作流的聚合表名唯一),用它判定不会误伤别人。
     *
     * <p>⚠️ 解析失败一律判「不是我的」:宁可漏删一张废弃视图(用户手工删即可),
     * 也绝不能误删别人正在用的视图(那是静默断流)。
     */
    static boolean mvTargetsTable(String createQuery, String db, String aggTable) {
        Matcher m = MV_TO_TABLE_RE.matcher(createQuery == null ? "" : createQuery);
        if (!m.find()) {
            return false;
        }
        // 精确比较:CK 的表名区分大小写,大小写不同的那张确实不是同一张表
        return m.group(1).equals(db) && m.group(2).equals(aggTable);
    }

    // =====================================================================
    // 产物删除
    // =====================================================================

    /**
     * 删掉聚合表与所有物化视图(还有服务表)。
     *
     * <p>⚠️ 这是【不可逆】的:聚合层里的仲裁结果会一起没掉。界面必须二次确认。
     *
     * <p>删除范围是【精确名】(见 {@link ArbPlanBuilder#buildDropAllSql}):配置里每个源的
     * mv_&lt;目标表&gt;_&lt;源id&gt;,加本工作流的聚合表/服务表。这里【绝不能】改成按名字前缀删
     * (理由见 {@link #mvTargetsTable})。
     */
    public void dropObjects(ArbWorkflow w) {
        if (resumable != null) resumable.clear(w, () -> dropObjectsInternal(w));
        else dropObjectsInternal(w);
    }

    private void dropObjectsInternal(ArbWorkflow w) {
        if (w == null) {
            throw ApiException.validation("工作流为空");
        }
        CkClient client = targetClient(w.getConnectionId());
        for (String sql : ArbPlanBuilder.buildDropAllSql(w)) {
            client.exec(sql);
        }
    }

    // =====================================================================
    // 探测
    // =====================================================================

    /** 查聚合层/视图是否都在(界面上的「检查」)。 */
    public ArbProbe probe(ArbWorkflow w) {
        CkClient client = targetClient(w.getConnectionId());
        ArbProbe p = new ArbProbe();

        // 聚合表是否存在
        p.setAggExists(existsTable(client, w.getTargetDb(), w.aggTableName(), "检查聚合表失败"));

        // 视图清单:一次查全,避免逐个往返
        QueryResult mvRes;
        try {
            mvRes = client.query(
                    "SELECT name FROM system.tables WHERE database = ? AND engine = 'MaterializedView'",
                    Collections.<Object>singletonList(w.getTargetDb()));
        } catch (RuntimeException e) {
            throw ApiException.badGateway("查询物化视图失败: " + Sql.messageOf(e));
        }
        Set<String> have = new LinkedHashSet<>();
        for (List<Object> row : mvRes.getRows()) {
            if (!row.isEmpty()) {
                have.add(String.valueOf(row.get(0)));
            }
        }
        for (ArbSource s : w.getSources()) {
            String name = ArbPlanBuilder.mvName(w, s);
            if (have.contains(name)) {
                p.getMvs().add(name);
            } else if (s.isEnabled()) {
                // 配置里应有却没有:还没初始化过,或上次建失败了
                p.getMissingMvs().add(name);
            }
        }

        if (p.isAggExists()) {
            String q = quoteCol(w.getTargetDb()) + "." + quoteCol(w.aggTableName());
            try {
                QueryResult res = client.query("SELECT count(), uniqExact(" + quoteCol(ArbWorkflow.MDN_COLUMN)
                        + ") FROM " + q);
                if (!res.getRows().isEmpty() && res.getRows().get(0).size() >= 2) {
                    p.setRows(toLong(res.getRows().get(0).get(0)));
                    p.setMdns(toLong(res.getRows().get(0).get(1)));
                }
            } catch (RuntimeException ignored) {
                // 行数取不到不影响「表在不在」这个主结论
            }
            // 取值方式改过的列:结构不会自己跟上(建表只在首次按配置生成)。
            // 探测失败不拦其它信息 —— 「检查」本来就是最需要看得见的时候。
            try {
                p.getDrift().addAll(aggDrift(client, w));
            } catch (RuntimeException ignored) {
                // 同上:漂移探测失败不能淹掉整份探测结果
            }
        }

        // 配置外的视图:界面上必须看得见,否则「删掉的源又活了」这个现象无从排查
        // (解析不出 TO 归属的那些不列进来 —— 它们未必是我的,列出来只会误导)
        try {
            p.getOrphanMvs().addAll(orphanMvs(client, w).orphans);
        } catch (RuntimeException ignored) {
            // 列表页拿不到孤儿视图不影响主体信息
        }

        // 逐源统计(行数 / 号码数)。一个源出错只记在它自己身上。
        for (ArbSource s : w.getSources()) {
            ArbSourceProbe sp = new ArbSourceProbe();
            sp.setSourceId(s.getId());
            sp.setTable(s.getDatabase() + "." + s.getTable());
            sp.setMvName(ArbPlanBuilder.mvName(w, s));
            sp.setMvExists(have.contains(ArbPlanBuilder.mvName(w, s)));
            String q = quoteCol(s.getDatabase()) + "." + quoteCol(s.getTable());
            try {
                QueryResult res = client.query("SELECT count(), uniqExact(" + quoteCol(s.getMdnCol()) + ") FROM " + q);
                if (!res.getRows().isEmpty() && res.getRows().get(0).size() >= 2) {
                    sp.setRows(toLong(res.getRows().get(0).get(0)));
                    sp.setMdns(toLong(res.getRows().get(0).get(1)));
                }
            } catch (RuntimeException e) {
                // 单个源失败不能让整张图拿不到 —— 那正是最需要看图的时候
                sp.setError(Sql.messageOf(e));
            }
            p.getSources().add(sp);
        }

        // 服务层现状 + 两张表的最后写入时间(判「服务表跟上了没」)
        if (existsTableOrFalse(client, w.getTargetDb(), w.svcTableName())) {
            p.setSvcExists(true);
            // ⚠️ 计数失败不要连累整个 probe:这一页正是出问题时最需要看的地方,
            //    「服务表多大」取不到就留 0,别让整张拓扑拿不到(与 Go 一致)。
            try {
                p.setSvcRows(countSvcRows(client, w));
            } catch (RuntimeException e) {
                LOG.warn("工作流 {} 探测服务表行数失败(留 0): {}", w.getId(), Sql.messageOf(e));
            }
        }
        p.setAggModified(lastModified(client, w.getTargetDb(), w.aggTableName()));
        p.setSvcModified(lastModified(client, w.getTargetDb(), w.svcTableName()));
        return p;
    }

    /**
     * 服务表是否落后于聚合表(只读元数据,不扫数据)。
     *
     * <p>判据 = 两张表的最后写入时间。为什么不看行数:行数一样但内容旧的情况也存在
     * (比如只更新了某个列的值),而 modification_time 反映的是真实的 part 变化。
     *
     * <p>返回 false 的三种情况:
     * <ul>
     *   <li>聚合表都不存在(还没生成过)→ 没什么可摊的;</li>
     *   <li>服务表是 VIEW → 实时读聚合表,永远同步;</li>
     *   <li>服务表比聚合表新或一样 → 已经跟上了。</li>
     * </ul>
     */
    public boolean svcStale(ArbWorkflow w) {
        CkClient client = targetClient(w.getConnectionId());
        String agg = lastModified(client, w.getTargetDb(), w.aggTableName());
        if (agg.isEmpty()) {
            return false; // 聚合表还没生成
        }
        // VIEW 实时读聚合表,永远同步 —— system.parts 里没有 VIEW 的 part,
        // lastModified 会返回空,不能当成「服务表不存在」判成落后
        if (svcIsView(client, w)) {
            return false;
        }
        String svc = lastModified(client, w.getTargetDb(), w.svcTableName());
        if (svc.isEmpty()) {
            return true; // 服务表没有 → 落后
        }
        return agg.compareTo(svc) > 0;
    }

    // =====================================================================
    // 行数与元数据小查询
    // =====================================================================

    /** 服务表当前行数。表不存在 → 0(不是错误);其它错误原样抛。 */
    public long countSvcRows(CkClient client, ArbWorkflow w) {
        String q = quoteCol(w.getTargetDb()) + "." + quoteCol(w.svcTableName());
        try {
            QueryResult res = client.query("SELECT count() FROM " + q);
            if (res.getRows().isEmpty() || res.getRows().get(0).isEmpty()) {
                return 0;
            }
            return toLong(res.getRows().get(0).get(0));
        } catch (RuntimeException e) {
            if (missingObject(e)) {
                return 0; // 表还不存在 → 0 行,不是错误
            }
            throw e; // 权限/超时:不能当成 0 行报上去
        }
    }

    /** 聚合层的去重号码数(服务表数不出来时的兜底指标)。表不存在 → 0;其它错误原样抛。 */
    public long countMdns(CkClient client, ArbWorkflow w) {
        String q = quoteCol(w.getTargetDb()) + "." + quoteCol(w.aggTableName());
        try {
            QueryResult res = client.query(
                    "SELECT uniqExact(" + quoteCol(ArbWorkflow.MDN_COLUMN) + ") FROM " + q);
            if (res.getRows().isEmpty() || res.getRows().get(0).isEmpty()) {
                return 0;
            }
            return toLong(res.getRows().get(0).get(0));
        } catch (RuntimeException e) {
            if (missingObject(e)) {
                return 0;
            }
            throw e;
        }
    }

    /**
     * 报给用户看的行数:服务表行数;服务表数不出来(少见)时兜底报聚合层的【去重号码数】。
     *
     * <p>⚠️ 两条都是「取统计」而不是「做事情」,取不到不该改写已经落地的事实:
     * 回填与摊平都成功了,却因为最后一句 count() 失败把整次运行记成 failed,
     * 下次重跑还会因为「上次失败」被用户当成没生效(与 Go 的链式兜底一致)。
     *
     * <p>也不能回退到聚合表的【物理行数】—— 那个数含未合并的部分(2000 vs 1000),
     * 正是上面 ⑧ 那段注释说「很误导」的数。两个都取不到才留 0。
     */
    private long countRowsForStats(CkClient client, ArbWorkflow w) {
        try {
            return countSvcRows(client, w);
        } catch (RuntimeException ignored) {
            // 继续兜底
        }
        try {
            return countMdns(client, w);
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    /** 服务表是否已有数据(决定走直接写入还是临时表 + EXCHANGE)。表不存在或为空都返回 false。 */
    private boolean svcHasData(CkClient client, ArbWorkflow w) {
        String q = quoteCol(w.getTargetDb()) + "." + quoteCol(w.svcTableName());
        try {
            QueryResult res = client.query("SELECT count() FROM " + q);
            if (res.getRows().isEmpty() || res.getRows().get(0).isEmpty()) {
                return false;
            }
            return toLong(res.getRows().get(0).get(0)) > 0;
        } catch (RuntimeException e) {
            if (missingObject(e)) {
                return false;
            }
            throw ApiException.badGateway("检查服务表数据量失败: " + Sql.messageOf(e));
        }
    }

    /**
     * 服务对象是否是 VIEW。
     *
     * <p>VIEW 在 system.parts 里没有 part,所以 {@link #lastModified} 对 VIEW 永远返回空。
     * 调用方据此判断「不是落后,而是根本不需要摊平」。
     */
    private boolean svcIsView(CkClient client, ArbWorkflow w) {
        try {
            QueryResult res = client.query("SELECT engine FROM system.tables WHERE database = ? AND name = ?",
                    java.util.Arrays.asList(w.getTargetDb(), w.svcTableName()));
            if (res.getRows().isEmpty() || res.getRows().get(0).isEmpty()) {
                return false;
            }
            return "View".equals(String.valueOf(res.getRows().get(0).get(0)));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * 某张表最后一次写入时间(该表 active part 的最大 modification_time)。
     *
     * <p>只读元数据,不扫数据。查不到(表不存在/权限不够)就返回空,不影响其它信息。
     */
    private String lastModified(CkClient client, String db, String table) {
        try {
            QueryResult res = client.query(
                    "SELECT toString(max(modification_time)) FROM system.parts"
                            + " WHERE database = ? AND table = ? AND active",
                    java.util.Arrays.asList(db, table));
            if (res.getRows().isEmpty() || res.getRows().get(0).isEmpty()) {
                return "";
            }
            String s = String.valueOf(res.getRows().get(0).get(0));
            if ("1970-01-01 00:00:00".equals(s) || "null".equals(s) || "<nil>".equals(s)) {
                return "";
            }
            return s;
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** 聚合表里「现有类型 ≠ 配置要的状态类型」的列(带可读说明)。 */
    private List<String> aggDrift(CkClient client, ArbWorkflow w) {
        QueryResult res;
        try {
            res = client.query("SELECT name, type FROM system.columns WHERE database = ? AND table = ?",
                    java.util.Arrays.asList(w.getTargetDb(), w.aggTableName()));
        } catch (RuntimeException e) {
            throw ApiException.badGateway("读取聚合表结构失败: " + Sql.messageOf(e));
        }
        Map<String, String> have = new LinkedHashMap<>();
        for (List<Object> row : res.getRows()) {
            if (row.size() >= 2) {
                have.put(String.valueOf(row.get(0)), String.valueOf(row.get(1)));
            }
        }
        return driftColumns(w, have);
    }

    /**
     * 纯比对:配置要的状态类型 vs 表里已有的类型。
     *
     * <p>拆出来是为了能测 —— 漂移这件事错一个方向就是「静默算错数据」。
     *
     * <p>为什么只报错不自动改表:SimpleAggregateFunction 列的类型变更要重写已有 part 里的
     * 聚合状态,不同状态之间并不等价(sum 的历史没法折成 max),自动 ALTER 等于假装能迁数据。
     * 正解是清掉重建,所以把话说明白。
     *
     * <p>库里还没有的列不算漂移(那正是 ADD COLUMN 的活)。
     */
    static List<String> driftColumns(ArbWorkflow w, Map<String, String> have) {
        List<String> drift = new ArrayList<>();
        for (ArbColumn c : w.getColumns()) {
            String want = ArbPlanBuilder.stateType(c, ArbPlanBuilder.srcTypeForColumn(w, c.getName()));
            String got = have.get(c.getName());
            // CK 会把 Decimal(38, 2) 回成带空格的形态,而我们生成的有的带空格有的不带 ——
            // 空格不是语义,比对前先去掉。
            if (got == null || normType(got).equals(normType(want))) {
                continue;
            }
            drift.add(String.format("%s(表里是 %s,配置要 %s)", c.getName(), got, want));
        }
        return drift;
    }

    /** 类型串比对用的归一化:去掉全部空格。 */
    private static String normType(String t) {
        return t == null ? "" : t.replace(" ", "");
    }

    // =====================================================================
    // 小工具
    // =====================================================================

    /**
     * 重建期间的临时表名。
     *
     * <p>⚠️ 必须带工作流 ID:临时表名如果只由目标表名派生,两条工作流(或同一条被并发触发)
     * 会互相 DROP 掉对方正在灌的那张临时表,EXCHANGE 就可能把半张表换进正式表。
     */
    static String tempName(String base, long workflowId) {
        return base + TEMP_SUFFIX + "_" + workflowId;
    }

    /**
     * 服务视图发布专用对象名。
     *
     * <p>随机 token 让并发执行拥有各自独立的临时 VIEW 与备份对象；工作流 ID 和用途保留在名称里，
     * 便于故障排查。token 去掉 UUID 的连字符，保证结果可直接作为 ClickHouse 标识符。
     */
    static String serviceObjectName(String base, long workflowId, String purpose) {
        String token = UUID.randomUUID().toString().replace("-", "");
        return base + "__arb_svc_" + purpose + "_" + workflowId + "_" + token;
    }

    /** 取目标客户端;只读连接禁止任何写入(对应 Go targetClient 的只读检查)。 */
    private CkClient targetClient(long connId) {
        if (connSvc.effectiveConfig(connId).isReadOnly()) {
            throw ApiException.readOnly("当前连接为只读模式,不允许执行该操作");
        }
        // 工作流 DDL/物化视图/回填由平台管理身份执行(服务端生成 SQL,不接受用户任意 SQL);
        // 产物生成后再把目标表的读写权限授予工作流所有者。
        return identities.managementClient(connId);
    }

    /** {@code EXISTS TABLE db.table},失败抛 502(错误前缀可定制)。 */
    private boolean existsTable(CkClient client, String db, String table, String errPrefix) {
        try {
            return existsTableQuietly(client, db, table);
        } catch (RuntimeException e) {
            throw ApiException.badGateway(errPrefix + ": " + Sql.messageOf(e));
        }
    }

    /** {@code EXISTS TABLE db.table},失败一律当 false。 */
    private boolean existsTableQuietly(CkClient client, String db, String table) {
        QueryResult res = client.query("EXISTS TABLE " + quoteCol(db) + "." + quoteCol(table));
        if (res.getRows().isEmpty() || res.getRows().get(0).isEmpty()) {
            return false;
        }
        return toLong(res.getRows().get(0).get(0)) == 1;
    }

    /** 同上,但连查询本身的失败也吞掉(调用方只关心「有没有」)。 */
    private boolean existsTableOrFalse(CkClient client, String db, String table) {
        try {
            return existsTableQuietly(client, db, table);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean tableExists(CkClient client, String db, String table) {
        return existsTable(client, db, table, "检查目标表是否存在失败");
    }

    /** 读取目标数据库引擎；发布服务视图前据此确认 Atomic RENAME 的原子性保证成立。 */
    private String databaseEngine(CkClient client, String db) {
        try {
            QueryResult res = client.query("SELECT engine FROM system.databases WHERE name = ?",
                    Collections.singletonList(db));
            if (res.getRows().isEmpty() || res.getRows().get(0).isEmpty()) {
                return "<unknown>";
            }
            return String.valueOf(res.getRows().get(0).get(0));
        } catch (RuntimeException e) {
            throw ApiException.badGateway("检查目标数据库引擎失败: " + Sql.messageOf(e));
        }
    }

    /** 尽力删表(失败路径上的清理):用独立调用,不进运行记录的 SQL 清单。 */
    private void dropQuietly(CkClient client, String qualified) {
        try {
            client.exec("DROP TABLE IF EXISTS " + qualified);
        } catch (RuntimeException e) {
            LOG.warn("清理临时表 {} 失败(忽略): {}", qualified, Sql.messageOf(e));
        }
    }

    /**
     * 尽力清理服务视图发布留下的临时对象或旧对象。
     *
     * <p>对象可能是 VIEW，也可能是历史 sum 分支留下的物理 TABLE，所以先按 VIEW 删，再按 TABLE
     * 兜底。第一次 DROP 的引擎不匹配错误不记录警告，只有两种方式都失败才告警，避免正常替换
     * 物理表时产生误导日志。清理失败不向上抛出，不能掩盖发布流程的原始异常。
     */
    private void dropServiceObjectQuietly(CkClient client, String db, String table) {
        String qualified = quoteCol(db) + "." + quoteCol(table);
        try {
            client.exec("DROP VIEW IF EXISTS " + qualified);
            return;
        } catch (RuntimeException ignored) {
            // 物理表不是 VIEW，继续走 DROP TABLE。
        }
        try {
            client.exec("DROP TABLE IF EXISTS " + qualified);
        } catch (RuntimeException e) {
            LOG.warn("清理服务视图发布对象 {} 失败(忽略): {}", qualified, Sql.messageOf(e));
        }
    }

    /**
     * 报错是不是「表/库不存在」(那种情况返回 0 行是合理的,不算错误)。
     *
     * <p>CK 错误码 60 = UNKNOWN_TABLE,81 = UNKNOWN_DATABASE。
     */
    static boolean missingObject(Throwable err) {
        if (err == null) {
            return false;
        }
        String msg = Sql.messageOf(err);
        return msg.contains("UNKNOWN_TABLE") || msg.contains("UNKNOWN_DATABASE")
                || msg.contains("Code: 60") || msg.contains("Code: 81");
    }

    /** 单元格 → long(驱动可能回 Long/Integer/BigDecimal 转出的字符串)。 */
    static long toLong(Object v) {
        if (v == null) {
            return 0;
        }
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 只替换第一处出现(与 Go 的 strings.Replace(…, 1) 同义)。 */
    private static String replaceFirst(String s, String from, String to) {
        int i = s.indexOf(from);
        if (i < 0) {
            return s;
        }
        return s.substring(0, i) + to + s.substring(i + from.length());
    }

    /** 反引号引用标识符。 */
    private static String quoteCol(String s) {
        return Sql.quoteCol(s);
    }

    /**
     * 执行记录器:一条语句「先记录再执行」,失败的【那一条】也留在清单里。
     *
     * <p>备注(notes)与语句清单分开存,末尾以 {@code -- 备注} 追加上去 ——
     * 直接往 SQL 里拼备注会被后面任何一条 run 覆盖掉。
     */
    private static final class RunRecorder {
        private final List<String> ran = new ArrayList<>();
        private final List<String> notes = new ArrayList<>();
        private final ArbStats stats = new ArbStats();
        private String lastError = "";

        private ArbStats stats() {
            return stats;
        }

        private String lastError() {
            return lastError;
        }

        private String sqlText() {
            StringBuilder out = new StringBuilder(String.join(";\n", ran));
            for (String n : notes) {
                out.append(";\n-- ").append(n);
            }
            return out.toString();
        }

        /** 记录一条语句(失败也记录 —— 失败的那条最关键),成功返回 true。 */
        private boolean tryRun(CkClient client, String sql) {
            ran.add(sql);
            stats.setSql(sqlText());
            try {
                client.exec(sql);
                return true;
            } catch (RuntimeException e) {
                lastError = Sql.messageOf(e);
                return false;
            }
        }

        /** 同上,但失败直接包装成 502 抛出。 */
        private void runWrapped(CkClient client, String sql, String errPrefix) {
            if (!tryRun(client, sql)) {
                throw ApiException.badGateway(errPrefix + ": " + lastError);
            }
        }

        /** 记录并执行零行只读验证；验证 SQL 同样进入运行记录，失败统一包装为 502。 */
        private void runQueryWrapped(CkClient client, String sql, String errPrefix) {
            ran.add(sql);
            stats.setSql(sqlText());
            try {
                client.query(sql);
            } catch (RuntimeException e) {
                lastError = Sql.messageOf(e);
                throw ApiException.badGateway(errPrefix + ": " + lastError);
            }
        }

        /** 追加一条只出现在 SQL 备注里的提示。 */
        private void note(String msg) {
            notes.add(msg);
            stats.setSql(sqlText());
        }
    }
}
