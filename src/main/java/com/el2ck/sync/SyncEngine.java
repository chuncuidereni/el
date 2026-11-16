package com.el2ck.sync;

/**
 * 【文件职责】执行数据抽取、字段转换、ClickHouse 分批写入、断点提交和失败恢复，是同步链路的核心引擎。
 */

import com.el2ck.ckpool.CkClient;
import com.el2ck.ckpool.CkConfig;
import com.el2ck.ckpool.CkPool;
import com.el2ck.ckpool.QueryResult;
import com.el2ck.util.CkColumns;
import com.el2ck.util.Crypto;
import com.el2ck.duck.Duck;
import com.el2ck.entity.common.Column;
import com.el2ck.entity.connection.Connection;
import com.el2ck.entity.datasource.DataSource;
import com.el2ck.entity.sync.SyncFieldMapping;
import com.el2ck.entity.sync.SyncTask;
import com.el2ck.entity.sync.SyncBaselinePreview;
import com.el2ck.repository.metadata.MetadataRepository;
import com.el2ck.service.datasource.DbSource;
import com.el2ck.util.RemoteSourceFile;
import com.el2ck.util.SourceFile;
import com.el2ck.util.SyncTime;
import com.el2ck.util.SyncMappingTypes;
import com.el2ck.util.SyncWatermark;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.nio.file.Path;
import java.sql.Date;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 同步执行引擎(对应 Go internal/sync/engine.go)。
 *
 * 把「本地文件数据源(CSV/JSON)」按字段映射与策略写入 ClickHouse 目标库。
 *   - 源:通过内嵌 DuckDB 的 read_csv/read_json 直接读文件(应用 header/names 映射),分页 10000 行/批;
 *   - 目标:从连接池取客户端;目标表缺失时按映射自动建 MergeTree(ORDER BY orderKey),已存在则校验;
 *   - 策略:full = TRUNCATE 后全量重建;incremental = 按增量键取目标表 max 做 keyset 增量过滤;
 *   - 写入:值按目标类型转换(字符串/整数/浮点/布尔/日期/时间,未知类型透传),batch 提交;
 * 异常以中文消息抛出,由服务层写入失败历史。
 */
public class SyncEngine {
    // 断点同步的核心不变量: 源读取不提交游标；目标写入确认后才允许把 pending 提升为 last。
    // 分块 INSERT 之间没有事务；MySQL 保存子批次位置，目标通过批次令牌去重，不添加续传字段。

    /** 单批读取/写入行数(分页每页大小)。 */
    private static final int PAGE_SIZE = 10000;

    /** 同步/校验诊断日志(写操作是否被 mdn 校验拦住,可在应用日志里直接查)。 */
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SyncEngine.class);

    /** 目标表名/列名合法模式:字母或下划线开头,后可跟字母/数字/下划线。 */
    private static final java.util.regex.Pattern IDENT_RE =
            java.util.regex.Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    // 复用的 JSON 工具(ObjectMapper,线程安全),用于反序列化解密后的数据源配置。
    private static final ObjectMapper JSON = new ObjectMapper();

    // 元数据仓库:存取数据源、连接与任务等记录。
    private final MetadataRepository store;
    // ClickHouse 连接池:供按连接取目标客户端。
    private final CkPool pool;
    // 内嵌 DuckDB:负责解析本地/CSV/JSON 源文件。
    private final Duck duck;
    // 加解密密钥:用于解密源配置与连接密码密文。
    private final String secret;
    private SyncCheckpointStore checkpoints;
    private final Path resumeCacheRoot;
    private final ThreadLocal<SyncProgressReporter> progress=new ThreadLocal<>();
    /** 仅当前执行线程收集目标补值说明，成功时进入普通说明，绝不放入错误列。 */
    private final ThreadLocal<List<String>> targetWriteNotes = new ThreadLocal<>();
    private void phase(String phase) { if(progress.get()!=null) progress.get().phase(phase); }
    private void readProgress(long rows) { if(progress.get()!=null) progress.get().read(rows); }
    private void writtenProgress(long rows) { if(progress.get()!=null) progress.get().written(rows); }
    private void totalProgress(long rows) { if(progress.get()!=null) progress.get().total(rows); }
    private void objectProgress(String object) { if(progress.get()!=null) progress.get().object(object); }
    private void unitsProgress(String unit,long done,long total) { if(progress.get()!=null) progress.get().units(unit,done,total); }
/** 注入同步断点仓库，供读取、保存和清理任务进度时使用。 */
    public void setCheckpointStore(SyncCheckpointStore checkpoints) { this.checkpoints=checkpoints; }
    // 当前任务所有者的真实 CK 身份。
    private final com.el2ck.service.ck.CkIdentityService identities;
    // 目标表资产登记与生命周期授权。
    private final com.el2ck.service.ck.TableLifecycleService lifecycle;
    // 同步入库前的 mdn 明文校验参数(阈值 / 抽样行数,可在 yml 配置)。
    private final com.el2ck.config.AppConfig.SyncMdnCheck mdnCheck;

    /** 构造同步引擎:注入元数据仓库、连接池、DuckDB 与解密密钥。 */
    public SyncEngine(MetadataRepository store, CkPool pool, Duck duck, String secret,
                      com.el2ck.service.ck.CkIdentityService identities,
                      com.el2ck.service.ck.TableLifecycleService lifecycle,
                      com.el2ck.config.AppConfig appConfig) {
        // 保存元数据仓库引用。
        this.store = store;
        // 保存连接池引用。
        this.pool = pool;
        // 保存 DuckDB 引用。
        this.duck = duck;
        // 保存解密密钥。
        this.secret = secret;
        this.identities = identities;
        this.lifecycle = lifecycle;
        this.resumeCacheRoot=java.nio.file.Paths.get(appConfig==null ? "./data" : appConfig.getDataDir()).resolve("sync-resume");
        // 未注入配置时使用默认阈值,避免单测/旧调用方需要改构造。
        this.mdnCheck = appConfig == null || appConfig.getSyncMdnCheck() == null
                ? new com.el2ck.config.AppConfig.SyncMdnCheck() : appConfig.getSyncMdnCheck();
    }

    /**
     * 一次同步过程的统计结果。除读/写行数外,SFTP 目录同步还会累计逐文件的处理结果与提示
     * (对齐 Go SFTPRunResult):新增/跳过/变化/更新/排除/失败 计数 + notes。
     * 非目录同步只用 rowsRead/rowsWritten,其余字段保持 0/空。
     */
    public static class Stats {
        // 已读取行数。
        private long rowsRead;
        // 已写入行数。
        private long rowsWritten;
        // 新入库文件数(SFTP 目录同步)。
        private int newFiles;
        // 已同步未变化而跳过的文件数。
        private int skipped;
        // 已记录但远端已变化的文件数。
        private int changed;
        // 变化文件已按 path 删旧 + 重灌的文件数。
        private int updated;
        // 列名或顺序与任务选定结构(旧任务为首个文件)不一致而排除的文件数。
        private int excluded;
        // 处理失败(下载/解析/入库/删旧)的文件数 —— 失败不中止整次运行,逐文件隔离。
        private int failed;
        // 普通运行说明，与失败原因分开，成功记录不写入 error。
        private final java.util.List<String> notes = new ArrayList<>();
        private final java.util.List<String> errors = new ArrayList<>();
        void fileSummary(int added,int updated,int changed,int skipped,List<String> excludedNotes) {
            this.newFiles=added; this.updated=updated; this.changed=changed; this.skipped=skipped;
            this.excluded=excludedNotes.size(); this.notes.clear(); this.errors.clear(); this.errors.addAll(excludedNotes);
        }

        void noteIgnoredCsvTail() {
            notes.add("已启用忽略额外尾列：CSV 超出文件列结构的内容不入库");
        }

        void noteLegacyCsvReader() {
            notes.add("本轮沿用旧版 CSV 断点读取规则；完成后新轮次按任务的尾列配置读取");
        }

        /** 返回本次已读取行数。 */
        public long getRowsRead() {
            return rowsRead;
        }

        /** 返回本次已写入行数。 */
        public long getRowsWritten() {
            return rowsWritten;
        }

        /** 新入库文件数。 */
        public int getNewFiles() {
            return newFiles;
        }

        /** 已同步未变化而跳过的文件数。 */
        public int getSkipped() {
            return skipped;
        }

        /** 已记录但远端已变化的文件数。 */
        public int getChanged() {
            return changed;
        }

        /** 变化文件已按 path 重灌的文件数。 */
        public int getUpdated() {
            return updated;
        }

        /** 列结构不一致被排除的文件数。 */
        public int getExcluded() {
            return excluded;
        }

        /** 处理失败的文件数。 */
        public int getFailed() {
            return failed;
        }

        /** 逐文件提示(只读视图)。 */
        public java.util.List<String> getNotes() {
            return java.util.Collections.unmodifiableList(notes);
        }

        void addNote(String note) { notes.add(note); }

        /** 真正的文件处理失败或结构排除原因。 */
        public java.util.List<String> getErrors() {
            return java.util.Collections.unmodifiableList(errors);
        }
    }

    /**
     * 记录一次逐文件失败:计数 +1 并追加提示;不抛出,让 SFTP 目录同步继续处理其余文件
     * (对齐 Go engine_sftp.go 的「失败文件计入 Failed + Notes 后 continue」)。
     */
    static void noteFailure(Stats stats, String path, String phase, Throwable e) {
        stats.failed++;
        stats.errors.add("文件 " + path + " " + phase + "失败: " + msg(e));
    }

    /** 源页读取回调:where 为增量过滤 SQL;limit/offset 分页;返回的行与源列顺序一致。 */
    // 三种源(本地文件/库表/库内 CK 表)的差异全部收敛到这个函数式接口,transfer 因此只需
    // 面对“分页取一批行”这一件事,策略、映射、转换、写库只实现一份。
    // 约定:返回行必须与 transfer 拿到的 cols 顺序一致(转换阶段按该顺序的下标取值);
    // orderBy 供增量场景固定行序；文件源沿单次查询游标取批次，不使用 offset。
    interface PageSource extends AutoCloseable {
        List<List<String>> page(String where, int limit, int offset, String orderBy);
        default void watermark(String column, String value) { }
        /** 源侧总行数;未知返回 -1(文件/流式源不实现,校验时按“先全量后抽样”降级)。 */
        default long count(String where) { return -1L; }
        @Override default void close() { }
    }

    /** 执行一次同步任务,返回读取/写入行数统计。 */
    public Stats run(SyncTask task) {
        return run(task, false);
    }

    /**
     * 执行同步。{@code force=true} 表示 SFTP 目录任务的「全量重建」(清空目标表 + 去重记录后重入全部文件);
     * 非 SFTP 目录任务忽略该标记。
     */
    public Stats run(SyncTask task, boolean force) {
        return run(task, force, null);
    }

    /**
     * 同上,额外带上运行记录 id:只用于把断点续传事件写进结构化日志,便于按 run_id 与运行历史对齐。
     * 其他调用方(测试/内部)可以继续用两参重载。
     */
    public Stats run(SyncTask task, boolean force, Long runId) {
        // 沿用所有任务自动续传的契约；无分段键时使用持久化快照，进度由相应读取器上报。
        task.setResumable(true);
        SyncProgressReporter reporter = runId == null ? null
                : new SyncProgressReporter(task.getId(), runId, store::updateSyncRunProgress);
        if (reporter != null) progress.set(reporter);
        targetWriteNotes.set(new ArrayList<>());
        try {
            Stats result = runInternal(task,force,runId);
            for (String note : targetWriteNotes.get()) result.addNote(note);
            if (SyncTask.MODE_INCREMENTAL.equals(task.getMode()))
                result.addNote("本任务按已确认水位追加读取；低于断点的迟到/回填数据不会补读，源记录更新不会覆盖目标旧行。完成状态表示本轮水位范围已处理。");
            if (reporter != null) reporter.finish(result.getFailed() + result.getExcluded() > 0 ? "已停止" : "已完成");
            return result;
        } catch (RuntimeException | Error e) {
            if (reporter != null) reporter.finish(Thread.currentThread().isInterrupted() ? "已取消" : "已失败");
            throw e;
        } finally {
            // 线程复用时不能把上一次任务的计数、runId 或失败状态带给下一次运行。
            progress.remove();
            targetWriteNotes.remove();
            sourceIdentityGuard.remove();
        }
    }

    /** 为所有断点来源接上同一条上报链路，覆盖分段读取、文件下载、快照采集和 Kafka。 */
    private void trackCheckpoint(SyncCheckpointStore.Session session, SyncTask task) {
        if (session.state.currentObject == null) session.state.currentObject = task.getSourceObject();
        SyncProgressReporter reporter = progress.get();
        if (reporter != null) {
            session.onProgress(reporter::checkpoint);
            reporter.checkpoint(session.state);
        }
    }

    private static final ThreadLocal<Runnable> EXECUTION_FENCE = new ThreadLocal<>();
    /** 固定本次执行的源配置；清表复核不能改用执行期间被编辑过的数据源记录。 */
    private final ThreadLocal<java.util.function.Consumer<CkClient>> sourceIdentityGuard = new ThreadLocal<>();

    /** 在批次/取消检查处检查任务会话锁；每秒至多一次，避免逐行远程查询。 */
    public Stats run(SyncTask task, boolean force, Long runId, Runnable checkLease) {
        final long[] nextCheck = {0};
        EXECUTION_FENCE.set(() -> {
            long now = System.nanoTime();
            if (now >= nextCheck[0]) { checkLease.run(); nextCheck[0] = now + 1_000_000_000L; }
        });
        try { checkCancelled(); return run(task, force, runId); }
        finally { EXECUTION_FENCE.remove(); }
    }

    private Stats runInternal(SyncTask task, boolean force, Long runId) {
        // ---------- 1. 数据源 + 配置 ----------
        // 按任务配置的数据源 id 取数据源记录。
        DataSource ds = store.getDataSource(task.getDataSourceId());
        // 数据源不存在则报错。
        if (ds == null) {
            throw new IllegalStateException("记录不存在");
        }
        if(progress.get()!=null) progress.get().source(ds.getType(),task.getSourceObject());
        phase("读取源结构");
        validateMappingSystemColumns(task.getMapping(), task.isOds(),
                DataSource.SFTP.equals(ds.getType()) && !def(task.getRemotePath()).trim().isEmpty());
        SyncMappingTypes.validateMappings(task.getMapping());
        for (SyncFieldMapping mapping : task.getMapping()) {
            if (Boolean.TRUE.equals(mapping.getAutoMdnMd5())
                    && def(mapping.getSourceColumn()).trim().equals(def(task.getStableKey()).trim()))
                throw new IllegalArgumentException("开启手机号转 MD5 的字段不能作为稳定分段键，请留空或选择其他字段");
        }
        if (!SyncTask.validMode(task.getMode())) throw new IllegalArgumentException("同步策略不合法");
        for (SyncFieldMapping mapping : task.getMapping()) defaultSql(mapping);
        // 解密数据源配置,得到解析参数 Map。
        Map<String, Object> cfg = decryptConfig(ds);
        sourceIdentityGuard.set(client -> requireDifferentCkTables(task,ds,cfg,client));
        sourceIdentityGuard.get().accept(targetClient(task.getConnectionId()));
        if(task.isResumable()) {
            if(!supportsCheckpoint(ds.getType(),task.getMode(),task.isTruncate()))
                throw new IllegalStateException("该数据源或同步策略暂不支持断点续传");
            return runCheckpointed(task, ds, cfg, force, runId);
        }
        if (com.el2ck.service.datasource.BigDataSource.supports(ds.getType())) {
            if (!SyncTask.MODE_FULL.equals(task.getMode()))
                throw new IllegalArgumentException("Hive、Kafka 当前仅支持全量快照同步");
            return runBigData(task, ds, cfg);
        }
        // SFTP 且任务配置了远端目录(而非单文件)时走目录同步链路。
        if (DataSource.SFTP.equals(ds.getType()) && !def(task.getRemotePath()).trim().isEmpty()) {
            return runSftpDirectory(task, ds, cfg, force);
        }
        // 以下分支按数据源类型互斥(本地文件 / SQL 库表 / 库内 CK 表 / 远端文件),顺序不承载语义;
        // 四条链路最终都汇入 transfer,差别只在“怎么把源列描述出来”和“怎么分页取行”。
        // 本地文件类型(csv/json/xlsx)走本地文件同步链路。
        if (DataSource.isLocalFile(ds.getType())) {
            return runFile(task, ds, cfg);
        }
        // SQL 数据库源(clickhouse/mysql/pgsql)走库表分页读取链路。
        if (DbSource.isSqlSource(ds.getType())) {
            return runDb(task, ds, cfg);
        }
        if (DataSource.CK_TABLE.equals(ds.getType())) {
            return runCkTable(task, ds, cfg);
        }
        if (DataSource.MONGODB.equals(ds.getType())) {
            return runMongo(task, ds, cfg);
        }
        if (DataSource.SFTP.equals(ds.getType())) {
            return runRemoteFile(task, ds, cfg);
        }
        // 其余类型暂不支持作为同步源执行。
        throw new IllegalStateException(String.format("暂不支持将 \"%s\" 数据源作为同步源执行", ds.getType()));
    }

    /** 所有源入口共用检查；真正清表前再次读取身份，避免重试或快照采集绕过保护。 */
    private void requireDifferentCkTables(SyncTask task, DataSource ds, Map<String,Object> cfg, CkClient target) {
        if (!DataSource.CLICKHOUSE.equals(ds.getType()) && !DataSource.CK_TABLE.equals(ds.getType())) return;
        boolean local = DataSource.CK_TABLE.equals(ds.getType());
        CkConfig config = local ? targetConfig(task.getConnectionId()) : DbSource.ckConfig(cfg);
        String qualified = local ? ckSourceName(cfgStr(cfg,"database",""),cfgStr(cfg,"table",""))
                : DbSource.qualified("clickhouse",cfg,task.getSourceObject());
        String[] names = qualified.replace("`", "").split("\\.");
        String database = names.length == 1 ? config.getDatabase() : names[0];
        String table = names[names.length - 1];
        SyncTableIdentity destination = SyncTableIdentity.read(target,task.getTargetDb(),task.getTargetTable(),false);
        if (local) SyncTableIdentity.read(target,database,table,true).requireDifferent(destination);
        else try (CkClient source = openSourceClient(config)) {
            SyncTableIdentity.read(source,database,table,true).requireDifferent(destination);
        }
    }

    private void truncateTarget(SyncTask task, CkClient client, String sql) {
        checkCancelled();
        java.util.function.Consumer<CkClient> guard = sourceIdentityGuard.get();
        if (guard == null) throw new IllegalStateException("缺少本轮源表身份检查，禁止清表");
        guard.accept(client);
        client.exec(sql);
    }

    /** 地址锁继续保护已有部署；新物理锁让 HTTP/native、IP/域名别名共享一个执行范围。 */
    private SyncCheckpointStore.Session openCheckpoint(SyncTask task, String legacyTarget, String fingerprint,
                                                       boolean restart, String retryToken) {
        SyncTableIdentity.Target target=SyncTableIdentity.target(targetClient(task.getConnectionId()),task.getTargetDb(),task.getTargetTable());
        return checkpoints.open(task.getId(),legacyTarget,fingerprint,restart,retryToken,false,target.lockKey,target.uuid);
    }

    private void checkPhysicalTarget(SyncTask task, CkClient client, SyncCheckpointStore.Session session) {
        SyncTableIdentity.Target target=SyncTableIdentity.target(client,task.getTargetDb(),task.getTargetTable());
        session.requirePhysicalTarget(target.lockKey);
    }

    /** 本地文件同步(csv/json/xlsx):Excel 先转临时 CSV,DuckDB 按扩展名解析。 */
    private Stats runFile(SyncTask task, DataSource ds, Map<String, Object> cfg) {
        // 以数据源原始类型作为文件类型,委托给通用实现。
        return runFile(task, ds, cfg, ds.getType());
    }

    /** 按指定 fileType 执行本地文件同步:解析路径 → 描述结构 → 委托通用落盘。 */
    private Stats runFile(SyncTask task, DataSource ds, Map<String, Object> cfg, String fileType) {
        phase("转换与解析文件");
        // 读取源文件路径并去掉首尾空白。
        String path = cfgStr(cfg, "path", "").trim();
        // 路径为空说明配置中未指定文件。
        if (path.isEmpty()) {
            throw new IllegalStateException(String.format("数据源 \"%s\" 未配置文件路径", ds.getCode()));
        }
        // 文件不存在则直接报错。
        if (!new File(path).exists()) {
            throw new IllegalStateException("源文件不存在: " + path);
        }
        // 解析文件依赖 DuckDB,未就绪时报错。
        if (!duck.isReady()) {
            throw new IllegalStateException("duckdb 未初始化");
        }
        // 读取是否首行为表头、手动列名与分隔符等解析参数。
        boolean header = headerFromConfig(cfg);
        List<String> names = namesFromConfig(cfg);
        String delimiter = SourceFile.delimiter(cfg);
        // 得到可直接被 DuckDB 读取的路径(Excel 会先转换并缓存临时 CSV)。
        String readPath;
        try {
            readPath = SourceFile.readableCsv(duck, fileType, cfg);
        } catch (RuntimeException e) {
            // 转换失败时向上抛出其最内层原因。
            throw new IllegalStateException(msg(e));
        }
        // 探测源文件结构,得到源列定义。
        List<Column> cols;
        try {
            cols = duck.describeFile(readPath, header, names, delimiter);
        } catch (RuntimeException e) {
            // 结构探测失败时报错并携带根因。
            throw new IllegalStateException("解析源文件失败: " + msg(e));
        }
        if (cols == null || cols.isEmpty()) {
            throw new IllegalStateException(DataSource.MONGODB.equals(ds.getType())
                    ? "MongoDB 集合为空或没有可识别的顶层字段，无法建立字段映射"
                    : "源文件没有可识别字段");
        }
        // 水位确定后才打开一次查询；后续批次沿同一结果集读取。
        final Duck.FileCursor[] cursor = new Duck.FileCursor[1];
        PageSource src = new PageSource() {
            @Override public List<List<String>> page(String where, int limit, int offset, String orderBy) {
                if (cursor[0] == null) cursor[0] = duck.scanFile(readPath, header, names, delimiter, where, orderBy);
                return cursor[0].next(limit);
            }
        };
        try {
            return transfer(task, ds.getCode(), cols, src, "postgres");
        } finally {
            if (cursor[0] != null) cursor[0].close();
        }
    }

/**
 * 校验 {@code supportsCheckpoint} 所涉及的前置条件，失败时阻止后续读写以避免产生不一致状态。
 */
    public static boolean supportsCheckpoint(String type,String mode,boolean truncate) {
        return DataSource.validType(type) && SyncTask.validMode(mode)
                && (!(DataSource.HIVE.equals(type) || DataSource.KAFKA.equals(type)) || SyncTask.MODE_FULL.equals(mode));
    }

/**
 * 校验 {@code supportsCheckpointWatermark} 所涉及的前置条件，失败时阻止后续读写以避免产生不一致状态。
 */
    public static boolean supportsCheckpointWatermark(String type) { return JdbcSegmentReader.supportedWatermark(type); }

    /** 保存及执行都校验主键/索引，避免配置保存后才发现每批都在扫描整个源表。 */
    public void validateResumableSource(SyncTask task,DataSource ds) {
        if (task.getMysqlDateTables() != null) {
            if (!DataSource.MYSQL.equals(ds.getType())) throw new IllegalArgumentException("日期分表仅支持 MySQL 数据源");
            task.getMysqlDateTables().validate();
            if (def(task.getStableKey()).trim().isEmpty())
                throw new IllegalArgumentException("日期分表需选择各表共同的整数单列主键作为分段键");
            if (task.getStableKey().equals(task.getPhoneColumn()))
                throw new IllegalArgumentException("手机号转换列不能作为日期分表的分段主键");
            // 构造器只检查字段与类型，不开启连接；保存时即拒绝不可恢复的映射。
            try (JdbcSegmentReader ignored = new JdbcSegmentReader("mysql", decryptConfig(ds), task.getSchema(), task, null, null)) { }
        }
        if(task.isResumable() && !def(task.getStableKey()).trim().isEmpty() && (DataSource.MYSQL.equals(ds.getType()) || DataSource.PGSQL.equals(ds.getType())))
            DbSource.validateSegmentKey(DbSource.engineOf(ds.getType()),decryptConfig(ds),task.getSourceObject(),
                    task.getStableKey(),SyncTask.MODE_INCREMENTAL.equals(task.getMode()) ? task.getIncrColumn() : task.getStableKey());
    }

    private Stats runCheckpointed(SyncTask task, DataSource ds, Map<String,Object> cfg, boolean restart, Long runId) {
        String[] retryToken={null};
        long runStartedAtMs=System.currentTimeMillis();
        for(int attempt=0;;attempt++) {
            checkCancelled();
            try {
                return runCheckpointAttempt(task,ds,cfg,restart,runId,retryToken,runStartedAtMs,attempt);
            } catch(RuntimeException e) {
                if(Thread.currentThread().isInterrupted() || !SyncRetry.transientFailure(e)) throw e;
                if(attempt>=5)
                    throw new IllegalStateException("本次任务自动重试 5 次后仍未恢复，断点已保留: "+msg(e),e);
                long delay=Math.min(30000L,2000L << attempt);
                if (progress.get() != null) progress.get().retry(attempt + 1, delay);
                log.warn("event=sync.checkpoint.auto_retry task_id={} run_id={} attempt={} delay_ms={} error={}",
                        task.getId(),runId,attempt+1,delay,msg(e));
                SyncRetry.pause(delay);
            }
        }
    }

    private Stats runCheckpointAttempt(SyncTask task, DataSource ds, Map<String,Object> cfg,
                                       boolean restart, Long runId, String[] retryToken,long runStartedAtMs,int retryCount) {
        if(checkpoints==null) throw new IllegalStateException("同步断点存储未初始化");
        if (task.getMysqlDateTables() != null)
            return runMysqlDateTables(task, ds, cfg, restart, runId, retryToken, runStartedAtMs, retryCount);
        if(DataSource.KAFKA.equals(ds.getType())) return runKafkaCheckpointAttempt(task,ds,cfg,restart,runId,retryToken,runStartedAtMs,retryCount);
        if(DataSource.SFTP.equals(ds.getType()) && !def(task.getRemotePath()).trim().isEmpty()) return runSftpCheckpointAttempt(task,ds,cfg,restart,runId,retryToken,runStartedAtMs,retryCount);
        boolean keyed=usesKeyedReader(task,ds);
        if(!keyed) return runSnapshotCheckpointAttempt(task,ds,cfg,restart,runId,retryToken,runStartedAtMs,retryCount);
        if(!validIdent(task.getStableKey()) || task.getBatchSize()<100 || task.getBatchSize()>10000)
            throw new IllegalStateException("请配置合法分段键和 100–10000 的批次行数");
        if(task.getStableKey().equals(task.getPhoneColumn())) throw new IllegalStateException("号码转换列不能用作分段键");
        String engine=DbSource.engineOf(ds.getType());
        boolean jdbc=!"clickhouse".equals(engine);
        CkConfig source=jdbc ? null : DbSource.ckConfig(cfg), target=targetConfig(task.getConnectionId());
        String table=DbSource.qualified(engine,cfg,task.getSourceObject());
        List<Column> columns=DbSource.describe(engine,cfg,task.getSourceObject());
        if(jdbc) validateResumableSource(task,ds);
        else {
        // LIMIT 不等于只扫描一批：分段键不在排序键首位时，每段可能重新扫描整张大表。
        String[] sourceParts=task.getSourceObject().trim().split("\\.");
        String sourceDb=sourceParts.length==1 ? source.getDatabase() : sourceParts[0].trim();
        String sourceTable=sourceParts[sourceParts.length-1].trim();
        try(CkClient c=openSourceClient(source)) {
            QueryResult metadata=c.query("SELECT engine,sorting_key FROM system.tables WHERE database=? AND name=?",
                    java.util.Arrays.asList((Object)sourceDb,sourceTable));
            if(metadata.getRows().size()!=1)
                throw new IllegalStateException("无法读取源表排序键，不能确认分段读取条件");
            List<Object> row=metadata.getRows().get(0);
            String sorting=String.valueOf(row.get(1)).replace("`", "").trim();
            if(sorting.startsWith("tuple(")) sorting=sorting.substring(6);
            else if(sorting.startsWith("(")) sorting=sorting.substring(1);
            String first=sorting.split(",",2)[0].trim();
            if(first.endsWith(")")) first=first.substring(0,first.length()-1).trim();
            if(!String.valueOf(row.get(0)).endsWith("MergeTree") || !task.getStableKey().equals(first))
                throw new IllegalStateException("大表分段读取要求源表为 MergeTree 系列表，且分段键为 ORDER BY 首列；当前排序键: "+row.get(1));
        }
        }
        String fingerprint=SyncCheckpointFingerprint.segment(secret,task,ds,cfg,target,columns,task.getMode(),task.getIncrColumn());
        String targetLock=target.getHost().toLowerCase(java.util.Locale.ROOT)+":"+task.getTargetDb()+"."+task.getTargetTable();
        SyncCheckpointStore.Session session=openCheckpoint(task,targetLock,fingerprint,restart,retryToken[0]);
        try {
            session.stopPreviousWrites(token->identities.stopCheckpointWrites(task.getConnectionId(),token));
            inheritIncrementalBaseline(task,ds,cfg,target,columns,session,restart,false);
            validateIncrementalTargetBeforeRead(task,session);
            session.state.sourceType=ds.getType();
            if(!java.util.Objects.equals(session.state.runId,runId)) {
                session.state.runId=runId;
                session.state.runStartRows=session.state.rows;
                session.state.runStartKey=session.state.last;
                session.state.runStartedAtMs=runStartedAtMs;
            }
            session.state.retryCount=retryCount;
            trackCheckpoint(session, task);
            // complete 的保存可能已提交但响应丢失。自动重试应返回成功，绝不能再次清表。
            if(session.state.complete) {
                Stats completed=new Stats();
                completed.rowsRead=session.state.rows; completed.rowsWritten=session.state.rows;
                return completed;
            }
            SegmentReader reader=jdbc ? new JdbcSegmentReader(engine,cfg,columns,task,session,runId)
                    : new CkSegmentReader(()->openSourceClient(source),table,columns,task,session,runId);
            return transfer(task,ds.getCode(),columns,reader);
        } finally {
            String token=session.recoveryToken();
            if(token!=null) retryToken[0]=token;
            session.close();
        }
    }

    /** 同一目标锁覆盖整个分表轮次，既不逐表清空，也不逐表释放写入保护。 */
    private Stats runMysqlDateTables(SyncTask task, DataSource ds, Map<String,Object> cfg, boolean restart,
                                    Long runId, String[] retryToken, long started, int retryCount) {
        if (!DataSource.MYSQL.equals(ds.getType())) throw new IllegalArgumentException("日期分表仅支持 MySQL");
        task.getMysqlDateTables().validate();
        if (def(task.getStableKey()).isEmpty() || task.getBatchSize() < 100 || task.getBatchSize() > 10000)
            throw new IllegalArgumentException("日期分表需要整数单列主键和 100–10000 的批次行数");
        CkConfig target = targetConfig(task.getConnectionId());
        String fingerprint = SyncCheckpointFingerprint.mysqlDateTables(secret, task, ds, cfg, target, task.getMode(), task.isTruncate());
        String lock = target.getHost().toLowerCase(java.util.Locale.ROOT) + ":" + task.getTargetDb() + "." + task.getTargetTable();
        SyncCheckpointStore.Session session = openCheckpoint(task, lock, fingerprint, restart, retryToken[0]);
        try {
            SyncCheckpointStore.State state = session.state, old = session.previousState;
            if (!restart && old != null && old != state && old.complete && SyncTask.MODE_INCREMENTAL.equals(task.getMode())) {
                boolean same = fingerprint.equals(old.fingerprint);
                // 全量分表保存的是各表主键位置，只能安全继承为同主键增量，不能从合并目标倒推。
                boolean fromFull = SyncCheckpointFingerprint.completedMysqlDateFull(secret, task, ds, cfg, target, old);
                if (!MysqlDateTableReader.MODE.equals(old.readerMode) || (!same && !fromFull))
                    throw new IllegalStateException("日期分表配置与旧水位不兼容；已完成全量仅支持使用原分段主键切换增量，其余配置需保持一致");
                state.dateTableWatermarks = new LinkedHashMap<>(old.dateTableWatermarks);
                if (old.dateTableBaselines != null)
                    state.dateTableBaselines = new LinkedHashMap<>(old.dateTableBaselines);
                state.dateTableLastScanAtMs = old.dateTableLastScanAtMs;
                state.inheritTarget(old);
            }
            session.stopPreviousWrites(token -> identities.stopCheckpointWrites(task.getConnectionId(), token));
            state.readerMode = MysqlDateTableReader.MODE;
            state.sourceType = ds.getType();
            state.retryCount = retryCount;
            if (!java.util.Objects.equals(state.runId, runId)) {
                state.runId = runId; state.runStartRows = state.rows; state.runStartKey = state.last; state.runStartedAtMs = started;
            }
            trackCheckpoint(session, task);
            if (state.complete) {
                Stats result = new Stats(); result.rowsRead = state.rows; result.rowsWritten = state.rows; return result;
            }
            validateIncrementalTargetBeforeRead(task, session);
            preflightSnapshotTarget(task, session);
            if (state.dateTablePlan == null) {
                phase("发现 MySQL 日期分表");
                com.el2ck.service.datasource.MysqlDateTables.Preview discovery =
                        com.el2ck.service.datasource.MysqlDateTables.discover(cfg, task.getMysqlDateTables());
                if (discovery.getTables().isEmpty()) {
                    // 尚未建表不能解释为要求清空目标；也不能覆盖上一轮有效的逐表水位。
                    Stats result = new Stats();
                    result.notes.add((discovery.isAllDates() ? "全部匹配日期中" : "截至 " + discovery.getEffectiveEndDate())
                            + " 未发现符合规则的日期分表，本次未写入或清空目标，下次执行重新发现");
                    return result;
                }
                state.dateTablePlan = new ArrayList<>();
                boolean newTableFull = "full".equals(task.getMysqlDateTables().getNewTablePolicy());
                for (String name : discovery.getTables()) {
                    String watermark = state.dateTableWatermarks.get(name);
                    // 还没有水位的增量新表沿用上次未读到的基线；全量补齐策略下不使用旧基线。
                    String baseline = watermark == null && !newTableFull && state.dateTableBaselines != null
                            ? state.dateTableBaselines.get(name) : null;
                    state.dateTablePlan.add(new MysqlDateTableReader.Item(name, watermark, baseline));
                }
                state.dateTableEndDate = discovery.getEffectiveEndDate();
                state.dateTableMissing = discovery.getMissingCount();
                state.progressUnit = "表"; state.completedUnits = 0L; state.totalUnits = (long) state.dateTablePlan.size();
                state.phase = "准备读取日期分表";
                session.save();
            }
            return transfer(task, ds.getCode(), task.getSchema(), new MysqlDateTableReader(cfg, task, session, runId));
        } finally {
            String token = session.recoveryToken(); if (token != null) retryToken[0] = token;
            session.close();
        }
    }

    /** 只认完整的逐表断点，不以最近日志成功或目标表最大值替代同步基线。 */
    public static boolean dateTableBaselineReady(SyncCheckpointStore.State state) {
        return state != null && state.complete && state.initialized && state.targetIdentity != null
                && state.fingerprint != null && !state.fingerprint.trim().isEmpty()
                && state.token != null && !state.token.trim().isEmpty()
                && state.pending == null && state.pendingAction == null
                && (state.pendingStopTokens == null || state.pendingStopTokens.isEmpty())
                && MysqlDateTableReader.MODE.equals(state.readerMode)
                && state.dateTableWatermarks != null && state.dateTablePlan != null
                && !state.dateTablePlan.isEmpty() && state.dateTablePlan.stream().allMatch(item -> item.done);
    }

    /**
     * 保存全量转增量前检查断点，调用方持有任务执行锁；这里只读元数据，不写目标或推进水位。
     * 未运行过的任务可以直接选择增量；已有运行必须完整结束，并保留原逐表主键游标。
     * 执行时仍复查相同指纹及目标身份，防止保存后连接或目标结构再发生变化。
     */
    public DateTableModeChange validateDateTableModeChange(SyncTask previous, SyncTask next) {
        if (previous.getMysqlDateTables() == null || !SyncTask.MODE_FULL.equals(previous.getMode())
                || !SyncTask.MODE_INCREMENTAL.equals(next.getMode())) return null;
        SyncCheckpointStore.State state = checkpoints.read(previous.getId());
        if (state == null) return null;
        if (!dateTableBaselineReady(state))
            throw new IllegalArgumentException("日期分表全量尚未完整完成，请先按原配置继续传输，成功后再切换增量");
        if (next.getMysqlDateTables() == null || !java.util.Objects.equals(previous.getStableKey(), next.getIncrColumn()))
            throw new IllegalArgumentException("全量转增量需保留日期分表方式，增量键必须使用原分段主键：" + previous.getStableKey());
        DataSource source = store.getDataSource(next.getDataSourceId());
        // 只允许保留原结束日期或清空为持续采集；不能借模式切换任意替换原日期范围。
        String previousEnd = previous.getMysqlDateTables().getEndDate();
        String nextEnd = next.getMysqlDateTables().getEndDate();
        if (next.getMysqlDateTables().hasEndDate() && !java.util.Objects.equals(previousEnd, nextEnd))
            throw new IllegalArgumentException("全量转增量时结束日期可清空，开始日期和其他来源配置需保持不变");
        SyncTask originalRange = new SyncTask();
        org.springframework.beans.BeanUtils.copyProperties(next, originalRange);
        // 清空结束日期后，页面可能按另一张参考表重新预览 schema；旧断点必须用原任务保存的
        // schema 重建指纹。字段映射等用户配置仍取 next，从而继续严格校验其未被修改。
        originalRange.setSchema(previous.getSchema());
        com.el2ck.entity.sync.MysqlDateTableRule rule = new com.el2ck.entity.sync.MysqlDateTableRule();
        org.springframework.beans.BeanUtils.copyProperties(next.getMysqlDateTables(), rule);
        rule.setEndDate(previousEnd);
        originalRange.setMysqlDateTables(rule);
        if (source == null) throw new IllegalArgumentException("源数据源不存在");
        Map<String,Object> config = decryptConfig(source);
        CkConfig target = targetConfig(next.getConnectionId());
        if (!SyncCheckpointFingerprint.completedMysqlDateFull(secret, originalRange, source, config, target, state))
            throw new IllegalArgumentException("无法继承原逐表断点，请保持数据源、表名规则、开始日期、字段映射、分段键、批大小和目标配置不变；结束日期可清空，新表策略和定时设置可以调整");
        // 不在这里直接保存断点：必须和任务配置在同一事务中切换，避免重启后配置与断点不一致。
        return new DateTableModeChange(state.fingerprint, state.token,
                SyncCheckpointFingerprint.mysqlDateTables(secret, next, source, config, target, next.getMode(), next.isTruncate()));
    }

    /** 已完成全量断点向增量配置的授权变更，不更改任何已确认行数、逐表游标或目标身份。 */
    public static final class DateTableModeChange {
        public final String previousFingerprint, previousToken, nextFingerprint;
        private DateTableModeChange(String previousFingerprint, String previousToken, String nextFingerprint) {
            this.previousFingerprint = previousFingerprint;
            this.previousToken = previousToken;
            this.nextFingerprint = nextFingerprint;
        }
    }

    private static boolean usesKeyedReader(SyncTask task,DataSource ds) {
        return (DataSource.MYSQL.equals(ds.getType()) || DataSource.PGSQL.equals(ds.getType()) || DataSource.CLICKHOUSE.equals(ds.getType()))
                && !def(task.getStableKey()).trim().isEmpty()
                && ((SyncTask.MODE_FULL.equals(task.getMode()) && task.isTruncate()) || (!DataSource.CLICKHOUSE.equals(ds.getType()) && SyncTask.MODE_INCREMENTAL.equals(task.getMode())));
    }

    /** 任务列表只判断元数据中已知的接管条件，不连接源端或扫描目标数据。 */
    public static String baselineAdoptionBlockedReason(SyncTask task,String sourceType,SyncCheckpointStore.State state) {
        if (task.getMysqlDateTables() != null) return "日期分表不能从合并后的目标最大值推导逐表水位，请在原分表任务上继续执行";
        if(!SyncTask.MODE_INCREMENTAL.equals(task.getMode())) return "只有增量任务可以接管已有全量数据";
        if(!(DataSource.MYSQL.equals(sourceType) || DataSource.PGSQL.equals(sourceType) || DataSource.CLICKHOUSE.equals(sourceType)))
            return "当前数据源不支持接管已有全量数据";
        return SyncIncrementalBaseline.unwrittenReason(state);
    }

    /**
     * 预览/确认接管已经全量导入的目标。服务层已取得任务锁并校验源与目标归属；
     * 此处再持有目标锁。预览无写操作；确认仅保存新的已完成基线，不执行 TRUNCATE、INSERT 或 ALTER。
     */
    public SyncBaselinePreview adoptBaseline(SyncTask task,long userId,String confirmationToken,boolean confirm,Runnable taskFence) {
        if (task.getMysqlDateTables() != null) throw new IllegalStateException("日期分表不支持接管合并表的单一水位");
        if(checkpoints==null) throw new IllegalStateException("同步断点存储未初始化");
        if(!SyncTask.MODE_INCREMENTAL.equals(task.getMode())) throw new IllegalStateException("只有增量任务可以接管已有全量数据");
        DataSource ds=store.getDataSource(task.getDataSourceId());
        if(ds==null || !(DataSource.MYSQL.equals(ds.getType()) || DataSource.PGSQL.equals(ds.getType()) || DataSource.CLICKHOUSE.equals(ds.getType())))
            throw new IllegalStateException("接管已有全量数据目前支持 MySQL、PostgreSQL 和 ClickHouse 表数据源");
        Map<String,Object> cfg=decryptConfig(ds);
        CkConfig target=targetConfig(task.getConnectionId());
        List<Column> columns=DbSource.describe(DbSource.engineOf(ds.getType()),cfg,task.getSourceObject());
        SyncMappingTypes.validateSource(task.getMapping(),columns,task.getIncrColumn());
        validateTargetSchema(task,false);
        boolean snapshot=!usesKeyedReader(task,ds);
        if(!snapshot) validateResumableSource(task,ds);
        String fingerprint=snapshot
                ? SyncCheckpointFingerprint.snapshot(secret,task,ds,cfg,target,task.getMode(),task.isTruncate(),task.getIncrColumn())
                : SyncCheckpointFingerprint.segment(secret,task,ds,cfg,target,columns,task.getMode(),task.getIncrColumn());
        String lock=target.getHost().toLowerCase(java.util.Locale.ROOT)+":"+task.getTargetDb()+"."+task.getTargetTable();
        // restart 仅用于锁内读取旧状态；绝不把它作为重传写入保存。下面必须先确认旧状态没有任何写入。
        try(SyncCheckpointStore.Session session=openCheckpoint(task,lock,fingerprint,true,null)) {
            taskFence.run(); session.checkLock();
            SyncIncrementalBaseline.requireUnwritten(session.previousState,fingerprint);
            if(!snapshot) new JdbcSegmentReader(DbSource.engineOf(ds.getType()),cfg,columns,task,session,null).close();
            SyncIncrementalBaseline.Existing existing=SyncIncrementalBaseline.inspectExisting(task,ds.getCode(),columns,snapshot,targetClient(task.getConnectionId()));
            SyncBaselinePreview preview=new SyncBaselinePreview();
            preview.setTaskId(task.getId()); preview.setTarget(task.getTargetDb()+"."+task.getTargetTable());
            preview.setSourceCode(ds.getCode()); preview.setIncrementalColumn(task.getIncrColumn());
            preview.setWatermark(existing.watermark); preview.setTargetRows(existing.rows);
            String digest;
            try { digest=SyncCheckpointStore.hash(JSON.writeValueAsString(java.util.Arrays.asList(
                    "adopt-baseline-v1",userId,task.getId(),fingerprint,session.previousState,existing.identity,existing.definitionHash,existing.watermark,existing.rows))); }
            catch(Exception e) { throw new IllegalStateException("无法生成接管预览",e); }
            long now=System.currentTimeMillis();
            if(confirm) {
                SyncBaselineConfirmation.verify(secret,confirmationToken,digest,now);
                SyncCheckpointStore.State seed=new SyncCheckpointStore.State();
                seed.fingerprint=fingerprint; seed.sourceType=ds.getType(); seed.targetIdentity=existing.identity;
                seed.targetDefinitionHash=existing.definitionHash;
                seed.targetLock=session.state.targetLock; seed.physicalTargetLock=session.state.physicalTargetLock;
                seed.initialized=true; seed.complete=true; seed.phase="已接管全量数据，等待增量执行";
                // seed 表示可继承的边界，不把存量行数计入本任务“已写入”统计。
                if(snapshot) { seed.snapshotBaseline=existing.watermark; seed.snapshotWatermark=existing.watermark; }
                else seed.last=existing.watermark;
                seed.baselineAdoptedAtMs=now; seed.baselineAdoptedBy=userId; seed.baselineTargetRows=existing.rows;
                taskFence.run(); session.checkLock(); session.state=seed; session.save();
                log.info("event=sync.incremental.baseline_adopted task_id={} user_id={} target_rows={}",task.getId(),userId,existing.rows);
            }
            preview.setConfirmationToken(SyncBaselineConfirmation.issue(secret,digest,now));
            return preview;
        }
    }

    private Stats runKafkaCheckpointAttempt(SyncTask task,DataSource ds,Map<String,Object> cfg,boolean restart,
                                            Long runId,String[] retryToken,long started,int retryCount) {
        if(task.getBatchSize()<100 || task.getBatchSize()>10000) throw new IllegalStateException("批次行数需为 100–10000");
        CkConfig target=targetConfig(task.getConnectionId());
        List<Column> columns=KafkaSegmentReader.columns(task);
        String fingerprint;
        try {
            fingerprint=SyncCheckpointStore.hash(secret+JSON.writeValueAsString(java.util.Arrays.asList(
                    "kafka-window-v1",new java.util.TreeMap<>(cfg),target,ds.getId(),ds.getCode(),task.getSourceObject(),
                    columns,task.getMapping(),task.getTargetDb(),task.getTargetTable(),task.isOds(),task.getPhoneColumn(),
                    task.getBatchSize(),task.getOrderKey(),task.getSortingKeys(),task.getPrimaryKeys(),task.getPartitionExpr())));
        } catch(Exception e) { throw new IllegalStateException("无法计算 Kafka 断点指纹",e); }
        if(!task.isTruncate()) fingerprint=SyncCheckpointStore.hash(fingerprint+"kafka-append-v1");
        String targetLock=target.getHost().toLowerCase(java.util.Locale.ROOT)+":"+task.getTargetDb()+"."+task.getTargetTable();
        SyncCheckpointStore.Session session=openCheckpoint(task,targetLock,fingerprint,restart,retryToken[0]);
        try {
            session.stopPreviousWrites(token->identities.stopCheckpointWrites(task.getConnectionId(),token));
            session.state.sourceType=DataSource.KAFKA;
            if(!java.util.Objects.equals(session.state.runId,runId)) {
                session.state.runId=runId; session.state.runStartRows=session.state.rows;
                session.state.runStartKey=session.state.last; session.state.runStartedAtMs=started;
            }
            session.state.retryCount=retryCount;
            trackCheckpoint(session, task);
            if(session.state.complete) {
                Stats result=new Stats(); result.rowsRead=session.state.rows; result.rowsWritten=session.state.rows; return result;
            }
            return transfer(task,ds.getCode(),columns,new KafkaSegmentReader(cfg,columns,task,session,runId));
        } finally {
            String token=session.recoveryToken(); if(token!=null) retryToken[0]=token;
            session.close();
        }
    }

    private Stats runSnapshotCheckpointAttempt(SyncTask task,DataSource ds,Map<String,Object> cfg,boolean restart,
                                               Long runId,String[] retryToken,long started,int retryCount) {
        if(task.getBatchSize()<100 || task.getBatchSize()>10000) throw new IllegalStateException("每批行数需为 100–10000");
        CkConfig target=targetConfig(task.getConnectionId());
        String fingerprint=SyncCheckpointFingerprint.snapshot(secret,task,ds,cfg,target,task.getMode(),task.isTruncate(),task.getIncrColumn());
        SyncCheckpointStore.Session session=openCheckpoint(task,target.getHost().toLowerCase(java.util.Locale.ROOT)+":"+task.getTargetDb()+"."+task.getTargetTable(),fingerprint,restart,retryToken[0]);
        SftpResumeCache cache=null;
        try {
            session.stopPreviousWrites(token->identities.stopCheckpointWrites(task.getConnectionId(),token));
            SyncCheckpointStore.State state=session.state;
            state.sourceType=ds.getType(); state.retryCount=retryCount;
            if(!java.util.Objects.equals(state.runId,runId)) { state.runId=runId; state.runStartRows=state.rows; state.runStartKey=state.last; state.runStartedAtMs=started; }
            trackCheckpoint(session, task);
            if(state.complete) {
                cache=new SftpResumeCache(resumeCacheRoot.resolve("snapshots"),task.getId(),state.token);
                session.completeSaved=true;
                Stats result=new Stats(); result.rowsRead=state.rows; result.rowsWritten=state.rows; return result;
            }
            inheritIncrementalBaseline(task,ds,cfg,target,null,session,restart,true);
            // 必须在打开源连接、创建快照目录和保存新断点之前检查。
            // 否则新任务接已有目标会先下载整个源表，数千万行处理完才发现没有可信起点。
            validateIncrementalTargetBeforeRead(task,session);
            preflightSnapshotTarget(task,session);
            SegmentReader rowReader=openCkRowReader(task,ds,cfg,session,runId);
            if(rowReader!=null) return transfer(task,ds.getCode(),rowReader.columns,rowReader);
            cache=new SftpResumeCache(resumeCacheRoot.resolve("snapshots"),task.getId(),state.token);
            session.save();
            if(!state.snapshotComplete) {
                SnapshotSegmentReader.resetCapture(session,cache);
                try(SnapshotSources input=openSnapshotSource(task,ds,cfg,session,cache)) {
                    SnapshotSegmentReader.capture(task,session,cache,input.columns,input.source);
                }
            }
            SnapshotSegmentReader reader=new SnapshotSegmentReader(task,session,runId,cache);
            reader.verify();
            return transfer(task,ds.getCode(),state.snapshotColumns,reader);
        } finally {
            if(cache!=null) { cache.close(); if(session.completeSaved) cache.clean(); }
            String token=session.recoveryToken(); if(token!=null) retryToken[0]=token;
            if(token!=null && session.previousToken!=null && !session.previousToken.equals(session.state.token)) {
                try { SftpResumeCache old=new SftpResumeCache(resumeCacheRoot.resolve("snapshots"),task.getId(),session.previousToken); old.close(); old.clean(); }
                catch(RuntimeException e) { log.warn("event=sync.snapshot.old_cache_cleanup_failed task_id={}",task.getId()); }
            }
            session.close();
        }
    }

    /**
     * 大型 ClickHouse 全量表优先边读边写。继续使用原快照配置指纹，先由 open() 在锁内证明配置未改；
     * 只有完全没有目标写入副作用的未完成采集能升级读取方式。已完成采集/已写入任务仍使用原缓存。
     */
    private SegmentReader openCkRowReader(SyncTask task,DataSource ds,Map<String,Object> cfg,
                                           SyncCheckpointStore.Session session,Long runId) {
        SyncCheckpointStore.State state=session.state;
        boolean continuing=CkRowSegmentReader.MODE.equals(state.readerMode);
        if(state.readerMode!=null && !continuing) throw new IllegalStateException("不支持此断点读取模式，请使用匹配的程序版本");
        if(!SyncTask.MODE_FULL.equals(task.getMode()) || !(DataSource.CLICKHOUSE.equals(ds.getType()) || DataSource.CK_TABLE.equals(ds.getType()))) {
            if(continuing) throw new IllegalStateException("分批全量断点与当前任务不兼容");
            return null;
        }
        if(!continuing && (state.snapshotComplete || state.initialized || state.rows!=0 || state.last!=null
                || state.pending!=null || state.pendingHash!=null || state.pendingAction!=null || state.targetIdentity!=null
                || state.upper!=null || state.upperCaptured)) return null;
        phase("检查源分批读取条件");
        boolean local=DataSource.CK_TABLE.equals(ds.getType());
        CkConfig source=local ? targetConfig(task.getConnectionId()) : DbSource.ckConfig(cfg);
        CkConfig target=targetConfig(task.getConnectionId());
        String table=local ? ckSourceName(cfgStr(cfg,"database",""),cfgStr(cfg,"table",""))
                : DbSource.qualified("clickhouse",cfg,task.getSourceObject());
        if(table.indexOf('.')<0) table=quoteCK(source.getDatabase())+"."+table;
        String[] names=table.replace("`", "").split("\\.");
        List<Column> columns;
        CkRowSegmentReader.Plan plan;
        try(CkClient client=openSourceClient(source)) {
            columns=describeCkTable(client,table);
            plan=CkRowSegmentReader.inspect(client,names[0],names[1],columns);
        }
        if(plan==null) {
            if(continuing) throw new IllegalStateException("源结构已变化，不能继续原分批断点");
            return null;
        }
        if(continuing && !plan.identity.equals(state.sourceIdentity))
            throw new IllegalStateException("源表已重建或结构已变化，不能继续原分批断点");
        CkRowSegmentReader reader=new CkRowSegmentReader(()->openSourceClient(source),table,columns,task,session,runId,plan);
        try {
            reader.verifyPlan();
            if(!continuing) {
                // 不更换轮次 token，不放宽配置指纹。首次目标操作之前先落库新模式；响应丢失仍可按 token 恢复。
                state.readerMode=CkRowSegmentReader.MODE; state.sourceIdentity=plan.identity; state.snapshotColumns=columns;
                state.snapshotHashes.clear(); state.snapshotRows=0; state.completedUnits=0L; state.totalUnits=null;
                state.snapshotManifestHash=null; state.snapshotPageCount=0;
                state.estimateAttempted=false; state.estimatedTotalRows=null;
                state.phase="分批读取并写入"; state.progressUnit="行";
                session.save();
                log.info("event=sync.checkpoint.row_reader_enabled task_id={} run_id={}",task.getId(),runId);
            }
            return reader;
        } catch(RuntimeException e) { reader.close(); throw e; }
    }

    /**
     * 只对新轮次继承边界。失败重试继续已落库的新状态，不再重新查询最大值。
     * 全量转增量先验证旧全量的完整指纹，再从目标中已确认写入的数据建立起点；不会读取源的当前最大值。
     */
    private void inheritIncrementalBaseline(SyncTask task,DataSource ds,Map<String,Object> cfg,CkConfig target,
                                            List<Column> columns,SyncCheckpointStore.Session session,
                                            boolean restart,boolean snapshot) {
        SyncCheckpointStore.State old=session.previousState, next=session.state;
        if(restart || !SyncTask.MODE_INCREMENTAL.equals(task.getMode()) || old==null || !old.complete || old==next) return;
        if(next.fingerprint.equals(old.fingerprint)) {
            if(snapshot) next.snapshotBaseline=old.snapshotWatermark;
            else next.last=old.last;
            next.inheritTarget(old);
            return;
        }
        if(old.snapshotComplete || CkRowSegmentReader.MODE.equals(old.readerMode)) columns=old.snapshotColumns;
        else if(columns==null && !def(task.getStableKey()).trim().isEmpty() && DbSource.isSqlSource(ds.getType()))
            columns=DbSource.describe(DbSource.engineOf(ds.getType()),cfg,task.getSourceObject());
        if(!SyncCheckpointFingerprint.completedFull(secret,task,ds,cfg,target,columns,old))
            throw new IllegalStateException("增量配置与原断点不兼容；仅支持已完成全量在来源、映射和目标不变时切换增量。请恢复原配置后继续，修改增量字段需新建任务");
        session.checkLock();
        SyncIncrementalBaseline.establish(task,ds.getCode(),columns,old,next,snapshot,targetClient(task.getConnectionId()));
        session.checkLock();
        log.info("event=sync.incremental.baseline_from_full task_id={} source_type={}",task.getId(),ds.getType());
    }

    /**
     * 无增量起点时只允许从空目标开始。恢复旧版留下的“已采集但未写入”快照也先走此检查，
     * 不再遍历缓存；保持旧断点和已有目标数据不变。写入前仍保留同样的检查以防采集期间目标发生变化。
     */
    private void validateIncrementalTargetBeforeRead(SyncTask task,SyncCheckpointStore.Session session) {
        SyncCheckpointStore.State state=session.state;
        if(!SyncTask.MODE_INCREMENTAL.equals(task.getMode()) || state.initialized || state.replaceTarget
                || state.last!=null || state.targetIdentity!=null) return;
        phase("检查增量起点");
        checkCancelled(); session.checkLock();
        CkClient client=targetClient(task.getConnectionId());
        if(ckTableExists(client,task.getTargetDb(),task.getTargetTable())
                && !client.query("SELECT 1 FROM "+quoteCK(task.getTargetDb())+"."+quoteCK(task.getTargetTable())+" LIMIT 1").getRows().isEmpty())
            throw new IllegalStateException("任务 "+task.getId()+" 的目标表已有数据，但本任务没有可信增量起点；请在已完成全量的原任务上切换增量。新建任务接管已有数据需先核验并建立起点，不会自动清空或追加数据");
    }

    /** 提前拒绝已知无法写入的目标，避免完整采集大表后才失败；正式写入仍会复检。 */
    private void preflightSnapshotTarget(SyncTask task,SyncCheckpointStore.Session session) {
        phase("检查目标写入条件"); checkCancelled(); session.checkLock();
        CkClient client=targetClient(task.getConnectionId());
        checkPhysicalTarget(task,client,session);
        if(!ckTableExists(client,task.getTargetDb(),task.getTargetTable())) {
            if(session.state.targetIdentity!=null || session.state.initialized || session.state.pending!=null)
                throw new IllegalStateException("原断点的目标表已不存在，请恢复原目标或明确重新传输");
            if(!task.isAutoCreateTable()) throw new IllegalStateException("目标表不存在，且任务未启用自动创建表");
            requireTargetDatabase(task.getConnectionId(),task.getTargetDb());
            SyncBatchWriter.requireSynchronous(client);
            return;
        }
        List<Object> metadata=SyncBatchWriter.preflight(client,task,session.state);
        validateTargetSchema(task,false,describeSyncTarget(task,client,quoteCK(task.getTargetDb())+"."+quoteCK(task.getTargetTable()),false));
        identities.requirePrivilege(task.getConnectionId(),"INSERT",task.getTargetDb(),task.getTargetTable());
        if(!session.state.initialized && (session.state.replaceTarget || (SyncTask.MODE_FULL.equals(task.getMode()) && task.isTruncate())))
            identities.requirePrivilege(task.getConnectionId(),"TRUNCATE",task.getTargetDb(),task.getTargetTable());
        // 启用去重的受控管理流程要求此权限；旧协议 pending 的删除重放也需要。
        if(SyncBatchWriter.needsDedupSetup(metadata)
                || (session.state.pending!=null && !SyncBatchWriter.MODE.equals(session.state.writerMode)))
            identities.requirePrivilege(task.getConnectionId(),"ALTER DELETE",task.getTargetDb(),task.getTargetTable());
        session.checkLock();
    }

    private SnapshotSources openSnapshotSource(SyncTask task,DataSource ds,Map<String,Object> cfg,
                                               SyncCheckpointStore.Session session,SftpResumeCache cache) {
        String type=ds.getType();
        if(DataSource.isLocalFile(type)) return SnapshotSources.file(duck,type,cfg);
        if(DataSource.MONGODB.equals(type)) return SnapshotSources.mongo(cfg,task);
        if(DataSource.HIVE.equals(type) || DataSource.MYSQL.equals(type) || DataSource.PGSQL.equals(type))
            return SnapshotSources.jdbc(DataSource.HIVE.equals(type) ? "hive" : DbSource.engineOf(type),cfg,task,session.state.snapshotBaseline);
        if(DataSource.CK_TABLE.equals(type) || DataSource.CLICKHOUSE.equals(type)) {
            boolean local=DataSource.CK_TABLE.equals(type);
            CkConfig source=local ? targetConfig(task.getConnectionId()) : DbSource.ckConfig(cfg);
            String table=local ? ckSourceName(cfgStr(cfg,"database",""),cfgStr(cfg,"table","")) : DbSource.qualified("clickhouse",cfg,task.getSourceObject());
            List<Column> columns;
            try(CkClient client=openSourceClient(source)) {
                columns=describeCkTable(client,table);
                if (SyncTask.MODE_INCREMENTAL.equals(task.getMode()) && session.state.snapshotBaseline!=null) {
                    SyncMappingTypes.validateSource(task.getMapping(),columns,task.getIncrColumn());
                    Column watermark=columns.stream().filter(c -> task.getIncrColumn().equals(c.getName())).findFirst()
                            .orElseThrow(() -> new IllegalStateException("源增量字段不存在"));
                    String w=quoteCK(watermark.getName());
                    QueryResult upper=client.query("SELECT toString("+w+") FROM "+table+" WHERE "+w+" IS NOT NULL ORDER BY "+w+" DESC LIMIT 1");
                    String value=upper.getRows().isEmpty() ? null : String.valueOf(upper.getRows().get(0).get(0));
                    com.el2ck.util.SyncWatermark.requireNotRegressed(value,session.state.snapshotBaseline,watermark.getType());
                }
            }
            String filter=SnapshotSources.incrementalWhere("clickhouse",task,columns,session.state.snapshotBaseline);
            return new SnapshotSources(columns,new CkPageSource(()->openSourceClient(source),table,columns,
                    filter.isEmpty() ? null : filter.substring(" WHERE ".length())));
        }
        if(DataSource.SFTP.equals(type)) {
            try(RemoteSourceFile.SftpSession sftp=RemoteSourceFile.openSftpSession(cfg,60000)) {
                if(session.state.snapshotRemote==null) {
                    String selected=def(task.getSourceObject()).trim();
                    RemoteSourceFile.RemoteEntry entry=sftp.stat(selected.isEmpty() ? cfgStr(cfg,"remotePath","") : selected);
                    SftpSegmentReader.Item item=new SftpSegmentReader.Item(); item.path=entry.path; item.size=entry.size; item.mtime=entry.mtime;
                    session.state.snapshotRemote=item; session.save();
                }
                SftpSegmentReader.Item item=session.state.snapshotRemote;
                String format=RemoteSourceFile.fileType(java.nio.file.Paths.get(item.path),cfgStr(cfg,"format",""));
                SftpResumeCache.FileCopy copy=cache.obtain(Integer.MAX_VALUE,item.entry(),session.state.fileHashes.get("remote"),()->sftp,n->{
                    session.checkLock(); session.state.phase="下载文件"; session.state.currentObject=item.path;
                    session.state.progressUnit="字节"; session.state.completedUnits=n; session.state.totalUnits=item.size; session.save();
                },format);
                session.state.fileHashes.put("remote",copy.hash); session.save();
                Map<String,Object> local=new LinkedHashMap<>(cfg); local.put("path",copy.path.toString());
                return SnapshotSources.file(duck,format,local);
            }
        }
        throw new IllegalStateException("不支持的快照来源: "+type);
    }

    private Stats runSftpCheckpointAttempt(SyncTask task,DataSource ds,Map<String,Object> cfg,boolean restart,
                                           Long runId,String[] retryToken,long started,int retryCount) {
        validateResumableSource(task,ds);
        if(task.getBatchSize()<100 || task.getBatchSize()>10000) throw new IllegalStateException("批次行数需为 100–10000");
        CkConfig target=targetConfig(task.getConnectionId());
        String fingerprint;
        try {
            fingerprint=SyncCheckpointStore.hash(secret+JSON.writeValueAsString(java.util.Arrays.asList(
                    "sftp-csv-v1",new java.util.TreeMap<>(cfg),target,ds.getId(),ds.getCode(),task.getRemotePath(),
                    task.getFilePattern(),task.isHasHeader(),task.getSchema(),task.getMapping(),task.getTargetDb(),
                    task.getTargetTable(),task.isOds(),task.getPhoneColumn(),task.getBatchSize(),task.getOrderKey(),
                    task.getSortingKeys(),task.getPrimaryKeys(),task.getPartitionExpr())));
            // ignoreExtraCsvColumns 不参与指纹：它只放行/拒绝尾列，所有原先成功的记录值与行号不变。
            // 读取器限制只能沿用 header-records-v1，待确认批次仍执行内容摘要校验。
        } catch(Exception e) { throw new IllegalStateException("无法计算 SFTP 断点指纹",e); }
        String targetLock=target.getHost().toLowerCase(java.util.Locale.ROOT)+":"+task.getTargetDb()+"."+task.getTargetTable();
        SyncCheckpointStore.Session session=openCheckpoint(task,targetLock,fingerprint,restart,retryToken[0]);
        SftpSegmentReader reader=null;
        try {
            session.stopPreviousWrites(token->identities.stopCheckpointWrites(task.getConnectionId(),token));
            session.state.sourceType=DataSource.SFTP;
            if(!java.util.Objects.equals(session.state.runId,runId)) {
                session.state.runId=runId; session.state.runStartRows=session.state.rows;
                session.state.runStartKey=session.state.last; session.state.runStartedAtMs=started;
            }
            session.state.retryCount=retryCount;
            trackCheckpoint(session, task);
            if(session.state.complete) {
                Stats result=new Stats(); result.rowsRead=session.state.rows; result.rowsWritten=session.state.rows;
                SftpSegmentReader.summarize(session.state,result); return result;
            }
            boolean missing=!ckTableExists(targetClient(task.getConnectionId()),task.getTargetDb(),task.getTargetTable());
            reader=new SftpSegmentReader(cfg,task,session,runId,store,duck,resumeCacheRoot,missing,restart);
            return transfer(task,ds.getCode(),task.getSchema(),reader);
        } finally {
            if(reader!=null && session.completeSaved) reader.cleanup();
            String token=session.recoveryToken(); if(token!=null) retryToken[0]=token;
            // 已接受的新轮次会替换旧断点；只清理该任务上一轮的专属缓存，不能扫公共临时目录。
            if(token!=null && session.previousToken!=null && !session.previousToken.equals(session.state.token)) {
                try {
                    SftpResumeCache old=new SftpResumeCache(resumeCacheRoot,task.getId(),session.previousToken);
                    old.close(); old.clean();
                } catch(RuntimeException e) { log.warn("event=sync.sftp.old_cache_cleanup_failed task_id={}",task.getId()); }
            }
            session.close();
        }
    }

    /**
     * 执行可恢复的分段同步。首次运行先固定源上界、目标 UUID/元数据身份和本轮 token，再允许清表或写入；
     * 每个批次严格按“读取 -> 校验源摘要 -> 保存 pending -> INSERT -> 提升 last/清 pending -> 保存”推进。
     * 恢复时先停止旧 token 的在途写入，验证源摘要后按 MySQL 子批次位置及相同去重令牌继续。
     * 旧协议留下的 pending 才按旧范围清理一次。所有分块成功前不得推进源断点。
     */
    private Stats transferCheckpointed(SyncTask task,String sourceId,List<Column> columns,SegmentReader source,
                                      CkClient client,String table,List<String> targets,List<Integer> indexes,
                                      List<SyncFieldMapping> mappings,String sourceIdColumn,Long runId) {
        SyncCheckpointStore.Session session=source.checkpoint;
        SyncCheckpointStore.State state=session.state;
        // 增量恢复也检查当前源上界，不能把被清空/回退的源当作“没有新增”。
        checkPhysicalTarget(task,client,session);
        // 不覆盖已冻结的 upper；新轮次直接复用本次查询结果，避免重复读最大水位。
        if (source instanceof JdbcSegmentReader && source.incremental()) {
            String currentUpper = source.upper();
            source.validateWatermark(state.last,currentUpper);
            if (!state.upperCaptured && state.upper == null) {
                state.upper=currentUpper;
                state.upperCaptured=true;
            }
        }
        // 所有源入口已执行；此处复核，避免以后新增读取路径绕过旧写入的持久化终止步骤。
        session.stopPreviousWrites(token->identities.stopCheckpointWrites(task.getConnectionId(),token));
        if (state.phase == null) state.phase = "同步数据";
        QueryResult async=client.query("SELECT value FROM system.settings WHERE name='async_insert'");
        if(!async.getRows().isEmpty() && !"0".equals(String.valueOf(async.getRows().get(0).get(0))))
            throw new IllegalStateException("断点续传需要目标连接关闭 async_insert，确保写入落库后再确认断点");
        QueryResult identity=client.query("SELECT engine,toString(uuid),toString(metadata_modification_time),length(dependencies_table) FROM system.tables WHERE database=? AND name=?",
                java.util.Arrays.asList((Object)task.getTargetDb(),task.getTargetTable()));
        if(identity.getRows().size()!=1 || !"MergeTree".equals(String.valueOf(identity.getRows().get(0).get(0))))
            throw new IllegalStateException("断点续传目标必须是独立的普通 MergeTree 表（不支持复制表、分布式表或聚合表）");
        if(!"0".equals(String.valueOf(identity.getRows().get(0).get(3))))
            throw new IllegalStateException("目标表存在依赖视图，未确认批次重放无法回滚视图侧结果；请使用无写入副作用的独立目标表");
        // 身份编码保持兼容既有三字段断点；依赖关系单独检查，不改变旧指纹。
        String targetIdentity=SyncBatchWriter.prepare(client,table,task,session,
                ()->identities.enableCheckpointDedup(task.getConnectionId(),task.getTargetDb(),task.getTargetTable()));
        List<Column> targetColumns=describeSyncTarget(task,client,table,source instanceof SftpSegmentReader);
        source.validateTarget(targetColumns);
        // 只有旧状态留下未确认批次时才可能有在途 INSERT 需要中止;干净重跑不做 KILL。
        if(state.pending!=null || state.pendingAction!=null || (!state.initialized && state.targetIdentity!=null && source.clearsTarget())) {
            log.warn("event=sync.checkpoint.recover task_id={} run_id={} pending={} last={} rows={}",
                    task.getId(), runId, source.describeCursor(state.pending), source.describeCursor(state.last), state.rows);
            identities.stopCheckpointWrites(task.getConnectionId(),state.token);
        }
        String writeTag="/*"+state.token+"*/ ";
        Stats stats=new Stats(); stats.rowsRead=state.rows; stats.rowsWritten=state.rows;
        if(!state.estimateAttempted) {
            state.estimateAttempted=true;
            state.estimatedTotalRows=source.estimateTotalRows();
        }
        if(!state.initialized) {
            if(source.incremental() && !source.clearsTarget() && state.last==null && state.targetIdentity==null
                    && !client.query("SELECT 1 FROM "+table+" LIMIT 1").getRows().isEmpty())
                throw new IllegalStateException("目标已有数据但没有可信增量断点，请使用新的空目标表建立同步基线；不会自动清空旧数据");
            // 已保存的空源上界也必须保持不变；兼容旧断点中已保存的非空 upper。
            if(!state.upperCaptured && state.upper==null) state.upper=source.upper();
            state.upperCaptured=true;
            state.targetIdentity=targetIdentity;
            checkCancelled(); session.checkLock();
            // 在任何清表/写入之前保存本轮 token，自动重试只能继续这一轮。
            session.save();
            if(state.upper==null) {
                // 空源没有可读区间:清空目标后直接完成,与“读到末尾”语义一致。
                if(source.clearsTarget()) truncateTarget(task,client,writeTag+"TRUNCATE TABLE "+table+" SYNC");
                state.initialized=true; source.beforeComplete(); state.complete=true; session.save();
                source.summarize(stats);
                log.info("event=sync.checkpoint.complete task_id={} run_id={} rows=0 empty_source=true",
                        task.getId(), runId);
                return stats;
            }
        }
        // 恢复运行也保存本次运行号及起始行数；页面只读取元数据库，不探测源库。
        session.save();
        // 一条结构化事件把“是不是续传/从哪继续/已确认多少行”说清楚,可直接按 task_id/run_id 检索
        log.info("event=sync.checkpoint.open task_id={} run_id={} resume={} last={} rows={} upper={} initialized={} pending={}",
                task.getId(), runId, state.rows>0 && !state.complete, source.describeCursor(state.last), state.rows, source.describeCursor(state.upper),
                state.initialized, source.describeCursor(state.pending));
        MdnCheckState phoneCheck=newMdnCheck(task,columns,null,null);
        while(true) {
            checkCancelled(); session.checkLock();
            String fromKey=state.last;
            String high=state.pending==null ? state.upper : state.pending;
            List<List<String>> rows=source.read(state.last,high);
            checkCancelled(); session.checkLock();
            if(source.exhausted(rows)) {
                if(state.pending!=null) throw new IllegalStateException("未确认批次的源数据已变化，停止续传");
                if(!state.initialized) {
                    if(source.clearsTarget()) truncateTarget(task,client,writeTag+"TRUNCATE TABLE "+table+" SYNC");
                    state.initialized=true;
                }
                source.beforeComplete(); state.complete=true; source.updateProgress(); session.save();
                source.summarize(stats);
                log.info("event=sync.checkpoint.complete task_id={} run_id={} rows={}", task.getId(), runId, state.rows);
                return stats;
            }
            String next=source.nextCursor(rows);
            String digest;
            try { digest=SyncCheckpointStore.hash(JSON.writeValueAsString(rows)); }
            catch(Exception e) { throw new IllegalStateException("无法计算批次校验值",e); }
            if(state.pending!=null && (!next.equals(state.pending) || !digest.equals(state.pendingHash)))
                throw new IllegalStateException("未确认批次的源数据已变化，停止续传；请恢复原源数据，或使用新的空目标表建立任务");
            checkMdnPage(phoneCheck,rows);
            // 固定动态默认值及入库时间。重启后必须重建相同 SQL，不能用新的 now() 重试旧令牌。
            if(state.pending!=null && SyncBatchWriter.MODE.equals(state.writerMode) && state.batchTime==null)
                throw new IllegalStateException("待确认批次缺少固定转换时刻，已停止续传");
            if(state.batchTime==null) state.batchTime=java.time.LocalDateTime.now().withNano(0).toString();
            List<List<Object>> converted=convertRows(rows,indexes,mappings,sourceId,"",java.time.LocalDateTime.parse(state.batchTime));
            checkCancelled(); session.checkLock();
            if(!state.initialized) {
                if(source.clearsTarget()) truncateTarget(task,client,writeTag+"TRUNCATE TABLE "+table+" SYNC");
                state.initialized=true; session.save();
            }
            source.beforeBatch(client,table,sourceIdColumn,sourceId);
            if(state.pending!=null && !SyncBatchWriter.MODE.equals(state.writerMode)) {
                // 仅兼容旧版本已发出的批次；需要原辅助列完成一次清理，不再创建或写入这些列。
                String range=source.targetRange(state.last,state.pending);
                client.exec(writeTag+"ALTER TABLE "+table+" DELETE WHERE "+range+" AND `"+sourceIdColumn+"` = "
                        +com.el2ck.util.Sql.quoteString(sourceId)+" SETTINGS mutations_sync=2");
            }
            // 先保存意图，再按确定的子批次令牌写入；响应丢失时重试同一条 SQL，不删业务行。
            state.writerMode=SyncBatchWriter.MODE;
            state.pending=next; state.pendingHash=digest; session.save();
            checkCancelled(); session.checkLock();
            if(!converted.isEmpty()) SyncBatchWriter.write(client,table,targets,converted,session);
            // 只有每个子批次都确认并保存后，才能确认整批并原子提升源游标。
            // pending 清空与 last/rows 更新必须一起保存；只前进 last 会把部分写入永久误判为已完成。
            state.last=next; state.pending=null; state.pendingHash=null; state.rows+=rows.size();
            state.chunkOffset=0; state.chunkHash=null; state.batchTime=null;
            source.updateProgress();
            session.save();
            stats.rowsRead=state.rows; stats.rowsWritten=state.rows;
            log.debug("event=sync.checkpoint.batch task_id={} run_id={} from={} to={} rows={} total={}",
                    task.getId(), runId, source.describeCursor(fromKey), source.describeCursor(next), rows.size(), state.rows);
        }
    }

    /** 数据库源(clickhouse/mysql/pgsql):sourceObject 为源表名,直接 SQL 分页读取。 */
    private Stats runDb(SyncTask task, DataSource ds, Map<String, Object> cfg) {
        // 读取任务中配置的源表名(sourceObject)。
        String object = def(task.getSourceObject()).trim();
        // 源表名为空时提示需在任务中填写。
        if (object.isEmpty()) {
            throw new IllegalStateException(String.format("数据库数据源 \"%s\" 需在任务中填写源表(sourceObject)", ds.getCode()));
        }
        // 由数据库类型得到对应的驱动/引擎标识。
        String engine = DbSource.engineOf(ds.getType());
        // 读取源表结构,得到源列定义。
        List<Column> cols;
        try {
            cols = DbSource.describe(engine, cfg, object);
        } catch (RuntimeException e) {
            // 结构读取失败时报错并携带根因。
            throw new IllegalStateException("读取源表结构失败: " + msg(e));
        }
        if ("clickhouse".equals(engine)) {
            return transfer(task, ds.getCode(), cols, new CkPageSource(
                    () -> openSourceClient(DbSource.ckConfig(cfg)), DbSource.qualified(engine, cfg, object), cols));
        }
        // JDBC 源必须以单列主键推进游标。同值增量键上的 OFFSET 翻页会静默漏行。
        String primaryKey = DbSource.singlePrimaryKey(engine, cfg, object);
        if (("mysql".equals(engine) || "postgres".equals(engine)) &&
                (primaryKey == null || cols.stream().noneMatch(c -> primaryKey.equals(c.getName())))) {
            throw new IllegalStateException("源表没有可用的单列主键，无法安全分页: " + object);
        }
        Column keyColumn = primaryKey == null ? null : cols.stream()
                .filter(c -> primaryKey.equals(c.getName())).findFirst().orElse(null);
        final String[] lastKey = {null};
        PageSource src = new PageSource() {
            @Override public List<List<String>> page(String where, int limit, int offset, String orderBy) {
                if (keyColumn == null) return DbSource.page(engine, cfg, object, where, limit, offset, cols, orderBy);
                String condition = where;
                if (lastKey[0] != null) {
                    condition = keysetWhere(engine, primaryKey, lastKey[0], keyColumn.getType(), condition);
                }
                List<List<String>> page = DbSource.page(engine, cfg, object, condition, limit, 0, cols, primaryKey);
                if (!page.isEmpty()) {
                    int keyIndex = cols.indexOf(keyColumn);
                    String next = page.get(page.size() - 1).get(keyIndex);
                    if (next == null || next.equals(lastKey[0])) {
                        throw new IllegalStateException("源表主键为空或分页游标未前进: " + primaryKey);
                    }
                    lastKey[0] = next;
                }
                return page;
            }
            @Override public long count(String where) {
                return DbSource.count(engine, cfg, object, where);
            }
        };
        // 复用通用落盘流程;源列标识按源数据库引擎的标识符规则引用。
        return transfer(task, ds.getCode(), cols, src, engine);
    }

    /** Reads a table through the same ClickHouse connection used by the sync target. */
    // 库内表源使用目标连接的配置与凭据；长查询使用独立客户端，避免占住写入连接。
    private Stats runCkTable(SyncTask task, DataSource ds, Map<String, Object> cfg) {
        String database = cfgStr(cfg, "database", "").trim();
        String table = cfgStr(cfg, "table", "").trim();
        String qualified = ckSourceName(database, table);
        // 前置守卫:库内表源恰好是目标表时,全量模式的 TRUNCATE 会先把源数据清空,且无法回滚
        // (源即目标,没有第二份数据),所以必须在开始读取之前就拒绝,而不是等到清表时才报错。
        if (database.equals(task.getTargetDb()) && table.equals(task.getTargetTable())) {
            throw new IllegalStateException("库内表源与目标表不能相同，避免全量清空源数据");
        }
        CkClient client = targetClient(task.getConnectionId());
        List<Column> cols = describeCkTable(client, qualified);
        // 列投影必须按类型改写(见 CkColumns):源表若正好是仲裁工作流的聚合表(业务列是
        // SimpleAggregateFunction),SELECT * 会让驱动在解析结果元数据时就抛
        // Unknown data type —— 整次同步直接失败。
        return transfer(task, ds.getCode(), cols, new CkPageSource(
                () -> openSourceClient(targetConfig(task.getConnectionId())), qualified, cols));
    }

    /** 打开同步专用读取连接，与连接池中的写入客户端分别管理生命周期。 */
    protected CkClient openSourceClient(CkConfig config) {
        return CkClient.open(config);
    }

    private static final class CkPageSource implements PageSource {
        private final java.util.function.Supplier<CkClient> factory;
        private final String table;
        private final List<Column> columns;
        private final String fixedWhere;
        private CkClient client;
        private CkClient.QueryCursor cursor;
        CkPageSource(java.util.function.Supplier<CkClient> factory, String table, List<Column> columns) {
            this(factory,table,columns,null);
        }
        CkPageSource(java.util.function.Supplier<CkClient> factory,String table,List<Column> columns,String fixedWhere) {
            this.factory = factory; this.table = table; this.columns = columns; this.fixedWhere=fixedWhere;
        }
        @Override public List<List<String>> page(String where, int limit, int offset, String orderBy) {
            if (cursor == null) {
                client = factory.get();
                cursor = client.openQueryCursor(ckSourceSelect(table, columns, fixedWhere==null ? where : fixedWhere));
            }
            return cursor.next(limit);
        }
        @Override public long count(String where) {
            if(fixedWhere!=null) where=fixedWhere;
            try (CkClient c = factory.get()) {
                QueryResult res = c.query("SELECT count() FROM " + table
                        + (where == null || where.isEmpty() ? "" : " WHERE " + where));
                if (res.getRows().isEmpty() || res.getRows().get(0).isEmpty()) return -1L;
                Object v = res.getRows().get(0).get(0);
                return v instanceof Number ? ((Number) v).longValue() : Long.parseLong(String.valueOf(v));
            }
        }
        @Override public void close() {
            try { if (cursor != null) cursor.close(); }
            finally { if (client != null) client.close(); }
        }
    }

    static String ckSourceSelect(String table, List<Column> columns, String where) {
        List<String> projection = new ArrayList<>();
        for (Column c : columns) {
            String quoted = DbSource.quoteIdent("clickhouse", c.getName());
            projection.add(SyncTime.isTimestamp(c.getType()) ? "toString(" + quoted + ") AS " + quoted
                    : CkColumns.selectExpr(quoted, c.getType()));
        }
        return "SELECT " + String.join(", ", projection) + " FROM " + table
                + (where == null || where.isEmpty() ? "" : " WHERE " + where)
                // 同名文本投影别名不能替换 WHERE 中的原始时间列，否则水位会按字符串比较。
                // max_execution_time=0 必须显式下发:HTTP 驱动(clickhouse-jdbc)会给语句带上
                // max_execution_time=3600(1 小时),而 ClickHouse 21.8 会在查询开始前按行数估算
                // 总耗时,估算超过该值就以 Code 160 中止——中止发生在响应已经开始流式返回之后,
                // 异常文本会被驱动当成行数据解析,最终表现为误导性的
                // “Failed to read boolean value ... but we got: 48”。同步的总时长由任务级
                // 超时(以及 socket 读超时)控制,不能依赖服务端的单查询上限。
                + " SETTINGS max_execution_time=0, max_result_rows=0, max_result_bytes=0, prefer_column_name_to_alias=1";
    }

    private Stats runBigData(SyncTask task, DataSource ds, Map<String,Object> cfg) {
        if (DataSource.KAFKA.equals(ds.getType())) {
            List<Column> cols = com.el2ck.service.datasource.KafkaSnapshot.columns(cfg,task.getSourceObject());
            com.el2ck.service.datasource.KafkaSnapshot snapshot = new com.el2ck.service.datasource.KafkaSnapshot(cfg,task.getSourceObject());
            PageSource source = new PageSource() {

                public List<List<String>> page(String where,int limit,int offset,String orderBy) {
                    phase("读取 Kafka 快照");
                    List<List<String>> rows = new ArrayList<>();
                    for (com.fasterxml.jackson.databind.JsonNode json : snapshot.next(limit)) {
                        List<String> row = new ArrayList<>();
                        for (Column col : cols) {
                            com.fasterxml.jackson.databind.JsonNode v = json.get(col.getName());
                            row.add(v == null || v.isNull() ? null : v.isValueNode() ? v.asText() : v.toString());
                        }
                        rows.add(row);
                    }
                    unitsProgress("位点",snapshot.scannedOffsets(),snapshot.totalOffsets());
                    return rows;
                }

                public void close() { snapshot.close(); }
            };
            return transfer(task,ds.getCode(),cols,source);
        }
        List<Column> cols = com.el2ck.service.datasource.BigDataSource.columns(cfg,task.getSourceObject());
        // 读取连接/游标先打开，避免连接失败后才发现目标已清空。
        try (java.sql.Connection conn = com.el2ck.service.datasource.BigDataSource.hive(cfg);
             java.sql.Statement statement = conn.createStatement()) {
            statement.setQueryTimeout(60);
            statement.setFetchSize(1000);
            List<String> projection = new ArrayList<>();
            for (Column col : cols) projection.add("`" + col.getName().replace("`","``") + "`");
            try (java.sql.ResultSet result = statement.executeQuery("SELECT " + String.join(",",projection)
                    + " FROM `" + task.getSourceObject() + "`")) {
                PageSource source = (where,limit,offset,orderBy) -> {
                    phase("读取 Hive 数据");
                    List<List<String>> rows = new ArrayList<>();
                    try {
                        while (rows.size() < limit && result.next()) {
                            checkCancelled();
                            List<String> row = new ArrayList<>();
                            for (int i=1;i<=cols.size();i++) row.add(result.getString(i));
                            rows.add(row);
                        }
                    } catch (java.sql.SQLException e) { throw new IllegalStateException("Hive 读取失败",e); }
                    return rows;
                };
                return transfer(task,ds.getCode(),cols,source);
            }
        } catch (java.sql.SQLException e) { throw new IllegalStateException("Hive 同步失败",e); }
    }

    private Stats runMongo(SyncTask task, DataSource ds, Map<String, Object> cfg) {
        MongoSyncSource mongo = MongoSyncSource.open(cfg, task.getSourceObject(), task.getMapping());
        PageSource source = new PageSource() {
            @Override public void watermark(String column, String value) { mongo.start(column, value); }
            @Override public List<List<String>> page(String where, int limit, int offset, String orderBy) { return mongo.next(limit); }
            @Override public void close() { mongo.close(); }
        };
        return transfer(task, ds.getCode(), mongo.columns(), source, "mongodb");
    }

    /** Validate mapping columns against the actual source table at task save time. */
    public List<Column> describeCkSource(long dataSourceId, long connectionId) {
        DataSource source = store.getDataSource(dataSourceId);
        if (source == null) throw new IllegalStateException("数据源不存在");
        if (!DataSource.CK_TABLE.equals(source.getType())) {
            throw new IllegalStateException("数据源不是库内表类型");
        }
        Map<String, Object> cfg = decryptConfig(source);
        String qualified = ckSourceName(cfgStr(cfg, "database", "").trim(),
                cfgStr(cfg, "table", "").trim());
        return describeCkTable(targetClient(connectionId), qualified);
    }

    static String ckSourceName(String database, String table) {
        if (!validIdent(database) || !validIdent(table)) {
            throw new IllegalStateException("源库/源表名非法");
        }
        return quoteCK(database) + "." + quoteCK(table);
    }

    static List<Column> describeCkTable(CkClient client, String qualified) {
        return columnsFromDescribe(describeCkResult(client, qualified), qualified);
    }

    private static QueryResult describeCkResult(CkClient client, String qualified) {
        try {
            return client.query("DESCRIBE TABLE " + qualified);
        } catch (RuntimeException error) {
            throw new IllegalStateException("读取表结构失败 " + qualified + ": " + msg(error), error);
        }
    }

    /** 共用一次 DESCRIBE 同时检查实际类型、计算列和未映射列；不额外查询数据或扫描大表。 */
    private List<Column> describeSyncTarget(SyncTask task, CkClient client, String qualified, boolean directory) {
        QueryResult result = describeCkResult(client, qualified);
        List<Column> columns = columnsFromDescribe(result, qualified);
        List<String> notes = SyncTargetLayout.inspect(result, task, directory);
        if (targetWriteNotes.get() != null) targetWriteNotes.set(notes);
        return columns;
    }

    private static List<Column> columnsFromDescribe(QueryResult result, String qualified) {
        if (result == null || result.getRows() == null || result.getRows().isEmpty())
            throw new IllegalStateException("读取表结构失败 " + qualified + ": 未返回字段");
        List<Column> columns = new ArrayList<>();
        // 只取 DESCRIBE 的前两列(name/type),与 DuckDB 侧 describeFile 保持同一约定;
        // 列顺序即 SELECT * 的列顺序,因此 transfer 里按 cols 下标取值的 srcIdx 才能和分页
        // 返回的行位置对齐——源改为按名取列时这里也必须同步改。
        for (List<Object> row : result.getRows()) {
            if (row == null || row.size() < 2 || row.get(0) == null || row.get(1) == null
                    || String.valueOf(row.get(0)).trim().isEmpty() || String.valueOf(row.get(1)).trim().isEmpty())
                throw new IllegalStateException("读取表结构失败 " + qualified + ": 字段名或类型缺失");
            Column column = new Column();
            column.setName(String.valueOf(row.get(0)));
            column.setType(String.valueOf(row.get(1)));
            columns.add(column);
        }
        return columns;
    }

    /** SFTP 单文件先下载到本机，再复用文件解析和映射入库链路。 */
    private Stats runRemoteFile(SyncTask task, DataSource ds, Map<String, Object> cfg) {
        phase("下载远端文件");
        // 本机临时文件路径;在 finally 中统一清理。
        // 这条链路的价值在于“只写一次下载/导出代码”:结构探测、类型推断、分页读取全部复用
        // 本地文件实现;代价是必须能落盘,且远端数据要先完整落到本机(大文件会占临时空间)。
        Path local = null;
        try {
            // 数据源 remotePath 可以是目录；真正执行时必须使用任务中选择的具体文件。
            // 不能直接把数据源目录传给 channel.get，否则 JSch 会报 not supported to get directory。
            String selectedFile = def(task.getSourceObject()).trim();
            Map<String, Object> sftpCfg = new LinkedHashMap<>(cfg);
            if (!selectedFile.isEmpty()) sftpCfg.put("remotePath", selectedFile);
            local = RemoteSourceFile.downloadSftp(sftpCfg);
            String fileType = RemoteSourceFile.fileType(local, cfgStr(cfg, "format", ""));
            // 在配置副本中写入本地文件路径,再复用文件解析入库。
            // 用副本而不是改写入参 cfg:该 Map 是解密后的数据源配置,同一任务的重试/其它分支
            // 还会用到,写入临时路径会污染后续解析(例如格式推断又回到远端语义)。
            Map<String, Object> localCfg = new LinkedHashMap<>(cfg);
            localCfg.put("path", local.toString());
            return runFile(task, ds, localCfg, fileType);
        } finally {
            // 无论成败,删除临时文件。
            RemoteSourceFile.deleteTemp(local);
        }
    }

    /** SFTP 目录同步中单个文件的处置决定(对去重记录比对后的判定)。 */
    enum SftpFileAction { NEW, SKIP, CHANGED }

    /**
     * 判断某远端文件该如何处理(纯逻辑,便于单测)。对齐 Go RunSFTP 的去重语义:
     * force=true 一律 NEW(全量重入);无记录 → NEW;有记录且 size+mtime 均未变 → SKIP;否则 CHANGED。
     */
    static SftpFileAction sftpFileAction(com.el2ck.entity.sync.SyncFile record, long size, String mtime, boolean force) {
        if (force || record == null) {
            return SftpFileAction.NEW;
        }
        String m = mtime == null ? "" : mtime;
        if (record.getSize() == size && def(record.getMtime()).equals(m)) {
            return SftpFileAction.SKIP;
        }
        return SftpFileAction.CHANGED;
    }

    /**
     * SFTP 目录同步:把远端目录下匹配的文件逐个同步进目标表(追加)。移植 Go engine_sftp.go 的核心语义:
     * <ul>
     *   <li>文件级去重(sync_files):只处理新出现 / 已变化 / force 重建的文件;未变化的跳过;</li>
     *   <li>ODS 任务的目标表带 {@code path} 列,每行标记来源文件完整路径;已变化文件按 path 删旧再重灌;</li>
     *   <li>force=true:先 TRUNCATE 目标表 + 清空去重记录,再重入全部文件。</li>
     * </ul>
     * 尚未移植跨请求 sftpcli 连接池；单次目录同步内复用会话。
     */
    private Stats runSftpDirectory(SyncTask task, DataSource ds, Map<String, Object> cfg, boolean force) {
        if (!duck.isReady()) {
            throw new IllegalStateException("duckdb 未初始化");
        }
        String targetDb = def(task.getTargetDb());
        String targetTable = def(task.getTargetTable());
        if (!validIdent(targetDb)) {
            throw new IllegalStateException(String.format("目标数据源 \"%s\" 非法", targetDb));
        }
        if (!validIdent(targetTable)) {
            throw new IllegalStateException(String.format("目标表名 \"%s\" 非法", targetTable));
        }
        String sourceIdentifier = def(ds.getCode());
        if (sourceIdentifier.trim().isEmpty()) {
            throw new IllegalStateException("数据源唯一标识字段不能为空");
        }
        boolean ods = task.isOds();
        SyncMappingTypes.validateSource(task.getMapping(), task.getSchema(), null);

        // ---------- 目标连接与建表 ----------
        CkClient client = targetClient(task.getConnectionId());
        String qualified = quoteCK(targetDb) + "." + quoteCK(targetTable);
        List<SyncFieldMapping> mapping = task.getMapping() == null ? new ArrayList<>() : task.getMapping();
        // 运行期映射 = 用户映射 + 溯源列(source_id/create_time 或 ods_*)+ ODS 目录同步的 path 列。
        String sourceIdCol = ods ? "ods_source_id" : "source_id";
        String insertAtCol = ods ? "ods_insert_at" : "create_time";
        boolean needPath = true; // 所有目录任务均按来源文件定位更新。
        List<SyncFieldMapping> runtimeMappings = new ArrayList<>(mapping);
        runtimeMappings.add(constMapping(sourceIdCol, "String"));
        runtimeMappings.add(constMapping(insertAtCol, "DateTime"));
        if (needPath) {
            runtimeMappings.add(constMapping("path", "String"));
        }

        String orderKey = def(task.getOrderKey()).trim();
        if (orderKey.isEmpty() && !runtimeMappings.isEmpty()) {
            orderKey = def(runtimeMappings.get(0).getTargetColumn());
        }
        boolean tableExists = ckTableExists(client, targetDb, targetTable);
        if (tableExists) validateTargetSchema(task, true, describeSyncTarget(task, client, qualified, true));
        if (task.isAutoCreateTable()) requireTargetDatabase(task.getConnectionId(), targetDb);
        if (!tableExists) {
            if (!task.isAutoCreateTable()) {
                throw new IllegalStateException("目标表不存在，且任务未启用自动创建表");
            }
            // 去重记录属于已删除的旧表。必须在建表前清理，避免建表后连接失败或进程中断，
            // 下次重试仍把未写入新表的文件判为已同步；清理失败时也不应创建空表。
            store.clearSyncedFiles(task.getId());
            client = createManagedTable(task, client, qualified, runtimeMappings, orderKey,
                    def(task.getPartitionExpr()).trim(), task.getSortingKeys(), task.getPrimaryKeys(),
                    def(task.getPhoneColumn()).trim());
        } else if (task.isAutoCreateTable()) {
            ensureFixedColumns(client, qualified, sourceIdCol, insertAtCol);
            if (needPath) {
                try {
                    client.exec("ALTER TABLE " + qualified + " ADD COLUMN IF NOT EXISTS " + quoteCK("path") + " String");
                } catch (RuntimeException e) {
                    throw new IllegalStateException("补充目标 path 列失败: " + msg(e));
                }
            }
        }

        // ---------- 列远端目录 + 逐文件处理 ----------
        String pattern = def(task.getFilePattern()).trim();
        if (pattern.isEmpty()) {
            pattern = cfgStr(cfg, "filePattern", "*.csv");
        }
        Map<String, com.el2ck.entity.sync.SyncFile> synced = store.listSyncedFiles(task.getId());
        boolean header = task.isHasHeader();
        // 与 Go 一致:describe 只按表头解析,不传手动列名(选定结构仅用于保存期映射校验)。
        List<String> names = null;
        String delimiter = SourceFile.directoryDelimiter(cfg);

        if (force) {
            truncateTarget(task,client,"TRUNCATE TABLE IF EXISTS " + qualified + " SYNC");
            store.clearSyncedFiles(task.getId());
        }
        Stats stats = new Stats();
        // 一次目录同步只握手一次:同一 SFTP 会话完成「列目录 + 逐文件下载」,用完关闭
        if(task.isIgnoreExtraCsvColumns()) stats.noteIgnoredCsvTail();
        // (对齐 Go 复用 sftpcli 连接池的意图,免每个文件各握手一次)。
        try (RemoteSourceFile.SftpSession sftp = RemoteSourceFile.openSftpSession(cfg)) {
            List<Column> referenceColumns = task.getSchema();
            phase("列出远端文件");
            List<RemoteSourceFile.RemoteEntry> files = sftp.list(def(task.getRemotePath()).trim(), pattern);
            int processedFiles=0;
            unitsProgress("文件",0,files.size());
            for (RemoteSourceFile.RemoteEntry f : files) {
                checkCancelled();
                objectProgress(f.path);
                try {
                SftpFileAction action = sftpFileAction(synced.get(f.path), f.size, f.mtime, force);
                if (action == SftpFileAction.SKIP) {
                    phase("跳过未变化文件");
                    stats.skipped++;
                    continue;
                }
                boolean updating = action == SftpFileAction.CHANGED;
                if (updating) stats.changed++;
                Path local = null;
                try {
                    phase("下载远端文件");
                    local = sftp.download(f.path);
                    phase("解析与校验文件");
                    List<Column> columns;
                    try (Duck.FileCursor structure = duck.scanDirectoryCsv(local.toString(), header, delimiter, task.isIgnoreExtraCsvColumns(), task.getCsvEncoding())) {
                        columns = structure.columns();
                    }
                    if (columns == null || columns.isEmpty()) throw new IllegalStateException("源文件没有可识别字段");
                    if (referenceColumns == null || referenceColumns.isEmpty()) referenceColumns = columns;
                    String schemaDifference = sftpColumnDifference(referenceColumns, columns);
                    if (schemaDifference != null) {
                        stats.excluded++;
                        stats.errors.add("文件 " + f.path + " 结构不一致：" + schemaDifference + "；已排除，保留旧数据");
                        continue;
                    }
                    SyncMappingTypes.validateSource(mapping, columns, null);
                    if (needPath) deleteRowsByPath(client, qualified, f.path);
                    long n = ingestSftpFile(client, task, mapping, runtimeMappings, sourceIdentifier, f.path,
                            local.toString(), header, names, delimiter, needPath, columns);
                    store.upsertSyncedFile(task.getId(), f.path, f.size, f.mtime);
                    stats.rowsRead += n;
                    stats.rowsWritten += n;
                    if (updating) {
                        stats.updated++;
                    } else {
                        stats.newFiles++;
                    }
                } catch (RuntimeException e) {
                    noteFailure(stats, f.path, "处理", e);
                } finally {
                    RemoteSourceFile.deleteTemp(local);
                }
                } finally { unitsProgress("文件",++processedFiles,files.size()); }
            }
        }
        return stats;
    }

    /** 删除目标表中某来源文件(path 列)的旧数据,同步等待 mutation 完成后再重灌。 */
    private void deleteRowsByPath(CkClient client, String qualified, String remotePath) {
        String sql = "ALTER TABLE " + qualified + " DELETE WHERE " + quoteCK("path") + " = "
                + quoteStr(remotePath) + " SETTINGS mutations_sync = 1";
        try {
            client.exec(sql);
        } catch (RuntimeException e) {
            throw new IllegalStateException("删除来源文件旧数据失败: " + msg(e));
        }
    }

    /** 只比较列名及顺序，允许不同文件的样本推断类型不同。 */
    static boolean sameSftpColumns(List<Column> expected, List<Column> actual) {
        return sftpColumnDifference(expected, actual) == null;
    }

    /**
     * 返回首个结构差异；只比较列数、列名和位置，不比较样本推断类型。
     * 不自动去空格、忽略大小写或重排列，以免固定字段映射写错位置。
     * 限制输出长度，并转义不可见字符，使错误提示可以定位肉眼难辨的差异。
     */
    static String sftpColumnDifference(List<Column> expected, List<Column> actual) {
        String countDifference = expected.size() == actual.size() ? ""
                : "列数不同，预期 " + expected.size() + " 列，实际 " + actual.size() + " 列；";
        for (int i = 0; i < Math.min(expected.size(), actual.size()); i++) {
            String wanted = expected.get(i).getName(), found = actual.get(i).getName();
            if (!java.util.Objects.equals(wanted, found)) {
                return countDifference + "第 " + (i + 1) + " 列预期 " + visibleColumnName(wanted)
                        + "，实际 " + visibleColumnName(found);
            }
        }
        if (!countDifference.isEmpty()) {
            int index = Math.min(expected.size(), actual.size());
            return countDifference + "第 " + (index + 1) + " 列预期 "
                    + (index < expected.size() ? visibleColumnName(expected.get(index).getName()) : "<无此列>")
                    + "，实际 " + (index < actual.size() ? visibleColumnName(actual.get(index).getName()) : "<缺列>");
        }
        return null;
    }

    private static String visibleColumnName(String name) {
        if (name == null) return "<null>";
        StringBuilder out = new StringBuilder("\"");
        int offset = 0, length = 0;
        while (offset < name.length() && length++ < 100) {
            int cp = name.codePointAt(offset);
            offset += Character.charCount(cp);
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)
                    || Character.isISOControl(cp) || Character.getType(cp) == Character.FORMAT) {
                out.append(String.format(java.util.Locale.ROOT, "\\u%04X", cp));
            } else if (cp == '"' || cp == '\\') {
                out.append('\\').appendCodePoint(cp);
            } else {
                out.appendCodePoint(cp);
            }
        }
        if (offset < name.length()) out.append("…");
        return out.append('"').toString();
    }

    /**
     * 校验普通字段映射中不得出现由系统自动维护的列:保存与运行两条路径共用本方法。
     *
     * <p>被禁用的列:source_id、create_time、sync_time,ODS 模式下的 ods_source_id/ods_insert_at,
     * 以及目录同步下的 path。它们的常量占位映射由 {@link #constMapping} 在运行时另行构造,并追加到
     * 映射末尾(见 transferOpen 的负下标约定);若用户映射里再出现同名目标列,会与自动注入的列撞名,
     * 因此在这里的入口直接拒绝并提示改映射,而不是等到写入时才报列数不符。
     */
    public static void validateMappingSystemColumns(List<SyncFieldMapping> mappings, boolean ods, boolean directory) {
        if (mappings == null) return;
        for (SyncFieldMapping mapping : mappings) {
            if (mapping == null) throw new IllegalArgumentException("字段映射不能为空");
            String column = def(mapping.getTargetColumn()).trim();
            String name = column.toLowerCase(java.util.Locale.ROOT);
            if ("source_id".equals(name) || "create_time".equals(name) || "sync_time".equals(name)
                    || KafkaSegmentReader.PARTITION.equals(name) || KafkaSegmentReader.OFFSET.equals(name)
                    || SftpSegmentReader.ROW.equals(name)
                    || SnapshotSegmentReader.ROUND.equals(name) || SnapshotSegmentReader.ROW.equals(name)
                    || (ods && ("ods_source_id".equals(name) || "ods_insert_at".equals(name)))
                    || (directory && "path".equals(name)))
                throw new IllegalArgumentException("目标列 \"" + column
                        + "\" 为系统自动维护字段，请从普通字段映射中删除该项；源字段需要保留时请映射到其他目标列名");
        }
    }

    private static SyncFieldMapping constMapping(String targetCol, String type) {
        SyncFieldMapping m = new SyncFieldMapping();
        m.setSourceColumn("__el2ck_const_" + targetCol + "__");
        m.setTargetColumn(targetCol);
        m.setType(type);
        m.setNullable(Boolean.FALSE);
        return m;
    }

    /**
     * 把单个已下载到本机的 CSV 文件按映射写入目标表(追加)。返回写入行数。
     * 溯源列(source_id/create_time 或 ods_*)与 path 列由 convertRows 按占位下标填充。
     */
    private long ingestSftpFile(CkClient client, SyncTask task, List<SyncFieldMapping> mapping,
                                List<SyncFieldMapping> runtimeMappings, String sourceIdentifier, String remotePath,
                                String localPath, boolean header, List<String> names, String delimiter,
                                boolean needPath, List<Column> cols) {
        if (cols == null || cols.isEmpty()) {
            throw new IllegalStateException("源文件没有可识别字段: " + remotePath);
        }
        // 源列名 → 下标
        Map<String, Integer> colIdx = new LinkedHashMap<>();
        for (int i = 0; i < cols.size(); i++) {
            colIdx.put(cols.get(i).getName(), i);
        }
        // targetCols / srcIdx 与 runtimeMappings 一一对齐(见 transfer 的不变量说明)。
        List<String> targetCols = new ArrayList<>();
        List<Integer> srcIdx = new ArrayList<>();
        for (SyncFieldMapping m : mapping) {
            String sc = def(m.getSourceColumn());
            Integer idx = colIdx.get(sc);
            if (idx == null) {
                throw new IllegalStateException(String.format("源列 \"%s\" 不存在(可用列: %s)", sc, colNameList(cols)));
            }
            String tc = def(m.getTargetColumn());
            if (!validIdent(tc)) {
                throw new IllegalStateException(String.format("目标列名 \"%s\" 非法", tc));
            }
            targetCols.add(tc);
            srcIdx.add(idx);
        }
        // 溯源列 + path 列:占位下标 -2 / -1 / -3(与 convertRows 约定一致)。
        String sourceIdCol = task.isOds() ? "ods_source_id" : "source_id";
        String insertAtCol = task.isOds() ? "ods_insert_at" : "create_time";
        targetCols.add(sourceIdCol);
        srcIdx.add(-2);
        targetCols.add(insertAtCol);
        srcIdx.add(-1);
        if (needPath) {
            targetCols.add("path");
            srcIdx.add(-3);
        }

        String qualified = quoteCK(def(task.getTargetDb())) + "." + quoteCK(def(task.getTargetTable()));
        String orderKey = def(task.getOrderKey()).trim();
        if (orderKey.isEmpty() && !targetCols.isEmpty()) {
            orderKey = targetCols.get(0);
        }
        List<SyncFieldMapping> insertMapping =
                nonNullableOrderKeyMapping(runtimeMappings, orderKey, task.isAutoCreateTable());
        // 目录同步按文件逐份校验 mdn;单文件行数未知时用“先全量后抽样”策略。
        MdnCheckState mdnState = newMdnCheck(task, cols, null, null);
        long written = 0;
        try (Duck.FileCursor cursor = duck.scanDirectoryCsv(localPath, header, delimiter, task.isIgnoreExtraCsvColumns(), task.getCsvEncoding())) {
            while (true) {
                checkCancelled();
                List<List<String>> rows;
                try {
                    phase("读取文件");
                    rows = cursor.next(PAGE_SIZE);
                } catch (RuntimeException e) {
                    throw new IllegalStateException("读取源数据失败: " + msg(e));
                }
                int n = rows.size();
                if (n == 0) break;
                readProgress(n);
                phase("校验与转换数据");
                // 写入该文件当前批次前校验 mdn。
                checkMdnPage(mdnState, rows);
                List<List<Object>> converted = convertRows(rows, srcIdx, insertMapping,
                        sourceIdentifier, remotePath);
                try {
                    phase("写入目标表");
                    client.insertBatch(qualified, targetCols, converted);
                } catch (RuntimeException e) {
                    throw new IllegalStateException("写入目标表失败: "
                            + com.el2ck.util.PartitionRule.explainPartitionInsertError(msg(e)));
                }
                written += n;
                writtenProgress(n);
                if (n < PAGE_SIZE) break;
            }
        }
        return written;
    }

    /** 明文 11 位手机号:1 开头、第二位 3-9、共 11 位(含 16x/17x/19x 等新号段)。 */
    private static final java.util.regex.Pattern MDN_PLAIN_RE =
            java.util.regex.Pattern.compile("^1[3-9]\\d{9}$");

    /** 一次同步内的 mdn 校验状态(计数与抽样进度跨页累计)。 */
    static final class MdnCheckState {
        int colIndex = -1;
        String colName;
        long total = -1;
        long maxRows = 100_000;
        long sampleSize = 10_000;
        long stride = 1;
        long maxChecks = Long.MAX_VALUE;
        long rowsSeen;
        long checked;
        /** 源行数未知(文件/流式源):先全量看前 maxRows 行,之后按 maxRows/sampleSize 抽样。 */
        boolean dynamic;
    }

    /**
     * 归一化并判断是否为明文手机号:去掉空白/分隔符、去掉国际区号 86 后必须是 11 位手机号。
     * 识别到明文返回归一化号码，否则返回 null；不剥离字母，避免将哈希误识别为手机号。
     */
    static String plainMdn(String raw) {
        if (raw == null) return null;
        String digits = raw.replaceAll("[\\s\\p{Z}()（）-]", "");
        if (!digits.matches("\\+?[0-9]+")) return null;
        if (digits.startsWith("+")) digits = digits.substring(1);
        // 去掉前导 0(Excel/文本导出的 0 前缀)与国际区号 86。
        digits = digits.replaceFirst("^0+", "");
        if (digits.length() > 11 && digits.startsWith("86")) digits = digits.substring(2);
        return MDN_PLAIN_RE.matcher(digits).matches() ? digits : null;
    }

    /** 构造本次同步的 mdn 校验器;未配置手机号列/关闭校验时返回 null。 */
    MdnCheckState newMdnCheck(SyncTask task, List<Column> cols, PageSource src, String where) {
        if (mdnCheck == null || !mdnCheck.isEnabled()) return null;
        // 优先校验映射到目标 mdn/phone/mobile 的源列 —— 用户关心的是最终落库的号码列,
        // 它与 phoneColumn(源侧标注)不一致时以映射为准;旧任务没配 phoneColumn 也靠这一步兜底。
        String phone = "";
        for (SyncFieldMapping m : task.getMapping()) {
            String target = def(m.getTargetColumn()).trim().toLowerCase(java.util.Locale.ROOT);
            if ("mdn".equals(target) || "phone".equals(target) || "mobile".equals(target)) {
                phone = def(m.getSourceColumn()).trim();
                break;
            }
        }
        if (phone.isEmpty()) phone = def(task.getPhoneColumn()).trim();
        // 统一写入入口会逐行转换此列，源行和断点游标保持原值。
        for (SyncFieldMapping mapping : task.getMapping()) {
            if (phone.equalsIgnoreCase(def(mapping.getSourceColumn()).trim())
                    && Boolean.TRUE.equals(mapping.getAutoMdnMd5())) return null;
        }
        if (phone.isEmpty()) {
            log.info("[sync-mdn] 跳过:任务 {} 未配置手机号列", task.getId());
            return null;
        }
        int idx = -1;
        for (int i = 0; i < cols.size(); i++) {
            if (phone.equals(cols.get(i).getName())) { idx = i; break; }
        }
        if (idx < 0) {
            for (int i = 0; i < cols.size(); i++) {
                if (phone.equalsIgnoreCase(cols.get(i).getName())) { idx = i; break; }
            }
        }
        if (idx < 0) {
            // 不能静默跳过:手机号列对不上时继续写入会把非法 mdn 放进去。
            throw new IllegalStateException("同步任务的手机号列「" + phone
                    + "」不在源列中,无法执行 mdn 明文校验;可用列:"
                    + cols.stream().map(Column::getName).collect(java.util.stream.Collectors.joining("、")));
        }
        MdnCheckState st = new MdnCheckState();
        st.colIndex = idx;
        st.colName = cols.get(idx).getName();
        st.maxRows = Math.max(1, mdnCheck.getFullScanMaxRows());
        st.sampleSize = Math.max(1, mdnCheck.getSampleSize());
        try {
            st.total = src == null ? -1 : src.count(where);
        } catch (RuntimeException e) {
            // 总行数拿不到不影响同步本身:降级为“先全量后抽样”。
            st.total = -1;
        }
        if (st.total < 0) {
            st.dynamic = true;
        } else if (st.total <= st.maxRows) {
            st.stride = 1;
            st.maxChecks = st.total;
        } else {
            // 超过阈值:等距抽样 sampleSize 行。
            st.stride = Math.max(1, st.total / st.sampleSize);
            st.maxChecks = Math.min(st.sampleSize, st.total);
        }
        log.info("[sync-mdn] 任务 {} 校验列={} 源行数={} 策略={} (每 {} 行检查 1 行,最多 {} 行)",
                task.getId(), st.colName, st.total,
                st.dynamic ? "未知行数:先全量后抽样" : (st.stride == 1 ? "全量" : "抽样"),
                st.stride, st.maxChecks);
        return st;
    }

    /** 第 n 行(=0 起)是否需要校验:全量时逐行,抽样时按步长命中。 */
    static boolean mdnShouldCheck(MdnCheckState st, long n) {
        if (st == null) return false;
        if (st.dynamic) {
            long stride = Math.max(1, st.maxRows / st.sampleSize);
            return n < st.maxRows || ((n - st.maxRows) % stride == 0);
        }
        if (st.checked >= st.maxChecks) return false;
        return n % st.stride == 0;
    }

    /** 在写入当前批次之前校验 mdn;发现非法值立即抛错中止本次同步。 */
    static void checkMdnPage(MdnCheckState st, List<List<String>> rows) {
        if (st == null) return;
        for (List<String> row : rows) {
            long n = st.rowsSeen++;
            if (!mdnShouldCheck(st, n)) continue;
            st.checked++;
            String raw = st.colIndex < row.size() ? row.get(st.colIndex) : null;
            if (plainMdn(raw) != null) {
                log.warn("[sync-mdn] 拦截:第 {} 行 源列「{}」包含明文手机号「{}」",
                        n + 1, st.colName, mdnSample(raw));
                throw new IllegalStateException("mdn 校验未通过:第 " + (n + 1) + " 行(源列「" + st.colName
                        + "」)包含明文手机号「" + mdnSample(raw) + "」,本次同步已中止。"
                        + "请先将手机号处理为 MD5 等非明文值后再同步。");
            }
        }
    }

    /** 错误信息里展示号码时打码,避免把完整号码写进运行历史。 */
    private static String mdnSample(String raw) {
        String v = raw == null ? "" : raw.trim();
        if (v.isEmpty()) return "(空值)";
        String phone = plainMdn(v);
        if (phone != null) return phone.substring(0, 3) + "****" + phone.substring(7);
        String digits = v.replaceAll("[^0-9]", "");
        if (digits.length() == v.length() && v.length() >= 11) {
            return v.substring(0, 3) + "****" + v.substring(v.length() - 4);
        }
        return v.length() > 12 ? v.substring(0, 12) + "…" : v;
    }

    /** 通用落盘:校验映射 → 准备目标表 → 分页读源 → 类型转换 → batch 写入 ClickHouse。 */
    private Stats transfer(SyncTask task, String sourceIdentifier, List<Column> cols, PageSource src) {
        return transfer(task, sourceIdentifier, cols, src, "clickhouse");
    }

    private Stats transfer(SyncTask task, String sourceIdentifier, List<Column> cols, PageSource src,
                           String sourceEngine) {
        try (PageSource owned = src) {
            return transferOpen(task, sourceIdentifier, cols, owned, sourceEngine);
        }
    }

    /**
     * 同步主体的实现,自「校验映射」一路做到「batch 写入 ClickHouse」;本方法【不负责关闭】传入的源。
     *
     * <p>与 {@code transfer} 的分工:{@code transfer} 只做一件事——用 try-with-resources 包住 source,
     * 保证无论成功、异常还是中断都恰好关闭一次,然后把活儿委托到这里。把关闭动作收敛在唯一的一层,
     * 就是当初单独拆出本方法的原因:否则主体里散落 close 调用,极易漏关或重复关(Closeable 重复关
     * 虽多数无副作用,但三处源实现未必都幂等)。因此凡是要自己掌控源生命周期的调用方,都应直接调本
     * 方法并自行 close。
     *
     * <p>游标与批次:按 PAGE_SIZE 从 offset=0 逐页读取,每页「读→转→写」后立即丢弃,内存里始终只有
     * 一页;读到 0 行、或不满一页即视为末尾。增量模式下目标水位(目标表该列 max)只在进循环【前】取
     * 一次并全程固定,整轮同步以同一水位过滤源端,避免边同步边抬水位导致漏行。
     *
     * <p>中断:每页读取前与每行水位校验时都会调用 {@link #checkCancelled()},任务超时/取消时在最近的
     * 边界抛出终止,响应延迟上界约为一页。
     *
     * <p>失败语义:任何一步出错都抛异常向上传播,绝不返回「半份」统计;能返回的 Stats 只统计已成功
     * 写入的部分。因此调用方要么拿到完整结果,要么收到异常。
     */
    private Stats transferOpen(SyncTask task, String sourceIdentifier, List<Column> cols, PageSource src,
                               String sourceEngine) {
        phase("校验目标结构");
        SyncMappingTypes.validateSource(task.getMapping(), cols, incrementalColumn(task));
        // ---------- 2. 源列存在性 ----------
        // 记录源表中实际存在的列名(值为占位)。
        // 用 Map 当 Set 只是为保留插入顺序:校验失败时要按源表原始列序提示可用列,
        // 顺序稳定用户才能一眼看出缺的是哪一列。
        Map<String, Boolean> srcCols = new LinkedHashMap<>();
        for (Column c : cols) {
            srcCols.put(c.getName(), Boolean.TRUE);
        }

        // ---------- 3. 校验字段映射并定义目标列 ----------
        // 取任务字段映射;为空则初始化为空列表。
        List<SyncFieldMapping> mapping = task.getMapping() == null ? new ArrayList<SyncFieldMapping>() : task.getMapping();
        // 依次收集目标列名、对应源列索引与目标类型。
        List<String> targetCols = new ArrayList<>();
        List<Integer> srcIdx = new ArrayList<>();
        for (SyncFieldMapping m : mapping) {
            // 源列名不能为空,且必须真实存在于源表。
            String srcCol = def(m.getSourceColumn());
            if (!srcCols.containsKey(srcCol)) {
                throw new IllegalStateException(String.format("源列 \"%s\" 不存在(可用列: %s)", srcCol, colNameList(cols)));
            }
            // 目标列名需合法(字母/数字/下划线,不以数字开头)。
            String tc = def(m.getTargetColumn());
            if (!validIdent(tc)) {
                throw new IllegalStateException(String.format("目标列名 \"%s\" 非法(仅允许字母/数字/下划线,不以数字开头)", tc));
            }
            // 在源列中查找该列下标;默认 -1。
            // 下标在这里一次性解析并随 srcIdx 固化:行列定位属于“配置期”工作,不该按行重做
            // (每页 10000 行 × 每行 N 列的字符串比较是纯浪费);代价是映射与源结构必须匹配,
            // 源结构变了要在任务保存时重新校验(见 describeCkSource)。
            int idx = -1;
            for (int i = 0; i < cols.size(); i++) {
                if (srcCol.equals(cols.get(i).getName())) {
                    idx = i;
                    break;
                }
            }
            // 记录该映射的源列下标、目标列名与目标类型。
            srcIdx.add(idx);
            targetCols.add(tc);
        }

        // ---------- 4. 目标连接与表 ----------
        // 读取目标表名与目标库名,去掉首尾空白。
        // 系统固定字段不进入用户映射：source_id 取数据源唯一标识值，create_time 取本次创建时间。
        // 这两个列是平台级的溯源字段(哪条数据来自哪个源、何时写入),不依赖用户在映射里配置,
        // 因此在这里无条件追加到映射尾部;dataSourceIdentifier 为空说明数据源本身无唯一标识,
        // 补出来的 source_id 会是空值,与其写脏不如直接拒绝。
        if (def(sourceIdentifier).trim().isEmpty()) {
            throw new IllegalStateException("数据源唯一标识字段不能为空");
        }
        // ODS 标准源表:溯源列改用 ods_source_id / ods_insert_at(对齐 Go 贴源表口径);
        // 非 ODS 任务沿用既有的 source_id / create_time,保持向后兼容。两者语义一致:
        // 前者写数据源唯一标识,后者写本次入库时间,只是列名不同。
        boolean ods = task.isOds();
        String sourceIdCol = ods ? "ods_source_id" : "source_id";
        String insertAtCol = ods ? "ods_insert_at" : "create_time";
        SyncFieldMapping sourceIdMapping = new SyncFieldMapping();
        sourceIdMapping.setSourceColumn("__el2ck_source_identifier__");
        sourceIdMapping.setTargetColumn(sourceIdCol);
        sourceIdMapping.setType("String");
        sourceIdMapping.setNullable(Boolean.FALSE);
        SyncFieldMapping createTimeMapping = new SyncFieldMapping();
        createTimeMapping.setSourceColumn("__el2ck_create_time__");
        createTimeMapping.setTargetColumn(insertAtCol);
        createTimeMapping.setType("DateTime");
        createTimeMapping.setNullable(Boolean.FALSE);
        List<SyncFieldMapping> runtimeMappings = new ArrayList<>(mapping);
        runtimeMappings.add(sourceIdMapping);
        runtimeMappings.add(createTimeMapping);
        // 系统列用负下标占位(不来自真实源列):-2=source_id/ods_source_id、-1=create_time/ods_insert_at,
        // 由 convertRows 按约定填充。
        // 下标对齐是本方法的核心不变量:runtimeMappings(类型/可空性)、targetCols(insertBatch 的
        // 列顺序)、srcIdx(convertRows 的取值来源)三者必须一一对应且等长——增删任何一列都要三处
        // 同时改;用负数占位而不是把这两列排除在映射外,就是为了让三者长度天然一致。
        targetCols.add(sourceIdCol);
        srcIdx.add(-2);
        targetCols.add(insertAtCol);
        srcIdx.add(-1);

        if(src instanceof SegmentReader) ((SegmentReader)src).appendMappings(targetCols,srcIdx,runtimeMappings);
        String targetTable = def(task.getTargetTable());
        String targetDb = def(task.getTargetDb());
        // 目标表名需合法。
        if (!validIdent(targetTable)) {
            throw new IllegalStateException(String.format("目标表名 \"%s\" 非法", targetTable));
        }
        // 目标库名需合法。
        if (!validIdent(targetDb)) {
            throw new IllegalStateException(String.format("目标数据源 \"%s\" 非法", targetDb));
        }
        // 从连接池取目标库客户端。
        CkClient client = targetClient(task.getConnectionId());
        // 组装带库名的限定表名(反引号引用,如 `db`.`table`)。
        String qualified = quoteCK(targetDb) + "." + quoteCK(targetTable);

        boolean tableExists = ckTableExists(client, targetDb, targetTable);
        List<Column> actualColumns = tableExists ? describeSyncTarget(task, client, qualified, src instanceof SftpSegmentReader) : java.util.Collections.emptyList();
        if (src instanceof SegmentReader) checkPhysicalTarget(task,client,((SegmentReader)src).checkpoint);
        if (tableExists) validateTargetSchema(task, false, actualColumns);

        // 平台不创建业务库:共用 ods_data/dwd_data,库不存在直接报错,不静默建空库。
        if (task.isAutoCreateTable()) requireTargetDatabase(task.getConnectionId(), targetDb);
        // 目标表结构已在修改前检查。
        // 读取排序键(orderKey);为空则用首个目标列兜底。
        String orderKey = def(task.getOrderKey()).trim();
        if (orderKey.isEmpty()) {
            orderKey = targetCols.isEmpty() ? "" : targetCols.get(0);
        }
        // 表不存在时:未开启自动建表直接报错,否则按映射建表。
        if (!tableExists) {
            if (!task.isAutoCreateTable()) {
                throw new IllegalStateException("目标表不存在，且任务未启用自动创建表");
            }
            // 依据映射自动创建 MergeTree 表(分区/排序键/主键取自任务的表级配置)。
            client = createManagedTable(task, client, qualified, runtimeMappings, orderKey,
                    def(task.getPartitionExpr()).trim(), task.getSortingKeys(), task.getPrimaryKeys(),
                    def(task.getPhoneColumn()).trim());
        } else if (task.isAutoCreateTable()
                && (!hasColumn(actualColumns, sourceIdCol) || !hasColumn(actualColumns, insertAtCol))) {
            // 表已存在:只在缺固定列时补齐,否则本批 insertBatch 的列数会对不上。
            // ⚠️ 不能无条件执行:ClickHouse 的 ADD COLUMN IF NOT EXISTS 即使列已存在
            // 也会刷新 metadata_modification_time,断点续传的目标身份校验会因此在
            // 第二次运行时误判“目标表已被重建或修改”。
            ensureFixedColumns(client, qualified, sourceIdCol, insertAtCol);
        }

        if(src instanceof SegmentReader) {
            // 读取器仅校验所需业务字段，不再追加轮次、行号或消息位点列。
            ((SegmentReader)src).prepareTarget(client,qualified,describeSyncTarget(task,client,qualified,src instanceof SftpSegmentReader),task.isAutoCreateTable());
            // 大任务独占写连接，避免页面查询触发池内连接到期回收，将仍在运行的任务断开。
            try(CkClient owned=CkClient.open(targetConfig(task.getConnectionId()))) {
                return transferCheckpointed(task, sourceIdentifier, cols, (SegmentReader)src, owned, qualified,
                    targetCols, srcIdx, nonNullableOrderKeyMapping(runtimeMappings, orderKey, task.isAutoCreateTable()), sourceIdCol,
                    ((SegmentReader)src).runId);
            }
        }

        // ---------- 5. 策略:全量 TRUNCATE / 增量 max 过滤 ----------
        // 两种模式互斥:增量模式绝不清表(空了就谈不上“增量”),全量模式则可能清表。
        // 本段落只决定“写入前对目标表做什么”,行级的值处理一律在 convertRows 里完成。
        // 目标表当前增量键最大值(增量模式用)。
        // 增量列(源列名)与是否增量模式的判定。
        String incrColSrc = def(task.getIncrColumn()).trim();
        boolean incremental = SyncTask.MODE_INCREMENTAL.equals(def(task.getMode()));
        String incrMax = null;
        String incrSourceType = null;
        String incrTargetType = null;
        String incrMappingType = null;
        if (incremental) {
            if (incrColSrc.isEmpty()) throw new IllegalStateException("增量模式必须配置增量键");
            Column incrSourceColumn = cols.stream().filter(c -> incrColSrc.equals(c.getName()))
                    .findFirst().orElseThrow(() -> new IllegalStateException("源表缺少增量列: " + incrColSrc));
            incrSourceType = incrSourceColumn.getType();
            // 找到增量源列对应的目标列名，旧任务也必须通过运行时校验。
            String incrTarget = null;
            for (SyncFieldMapping m : mapping) {
                if (incrColSrc.equals(def(m.getSourceColumn()))) {
                    incrTarget = def(m.getTargetColumn());
                    incrTargetType = mappingType(m);
                    incrMappingType = incrTargetType;
                    if (!isIncrementalType(incrTargetType)) {
                        throw new IllegalStateException("增量字段必须为数值、日期或时间戳类型: " + incrColSrc);
                    }
                    break;
                }
            }
            if (incrTarget == null) throw new IllegalStateException("增量键必须是已映射的源列: " + incrColSrc);
            if (!compatibleIncrementalTypes(incrSourceType, incrTargetType)) {
                throw new IllegalStateException("源增量列与目标映射类型不兼容: " + incrSourceType + " -> " + incrTargetType);
            }
            if (!validIdent(incrTarget)) throw new IllegalStateException("增量键目标列非法: " + incrTarget);
            if (tableExists) {
                try {
                    final String targetName = incrTarget;
                    String actualType = actualColumns.stream()
                            .filter(c -> targetName.equals(c.getName()))
                            .map(Column::getType).findFirst()
                            .orElseThrow(() -> new IllegalStateException("目标表缺少增量字段"));
                    if (!compatibleIncrementalTypes(incrSourceType, actualType))
                        throw new IllegalStateException("实际目标增量字段类型不兼容: " + actualType);
                    incrTargetType = actualType;
                    incrMax = ckMax(client, qualified, incrTarget);
                } catch (RuntimeException e) {
                    // 查询失败时报错并携带根因。
                    throw new IllegalStateException("查询目标表增量键最大值失败: " + msg(e));
                }
            }
        }

        // ---------- 6. 分页读取 → 转换 → batch 写入 ----------
        // 统计对象与当前偏移量。
        // 每页最多 PAGE_SIZE 行驻留内存,读一批→转一批→写一批,不在内存里缓存全量数据。
        // rowsRead/rowsWritten 都只统计“已成功”的部分:写入抛错时异常向上抛,不会返回半份统计。
        src.watermark(incremental ? incrColSrc : null, incrMax);
        Stats stats = new Stats();
        int offset = 0;
        final String watermark = incrMax;
        final String sourceType = incrSourceType;
        final String watermarkTargetType = incrTargetType;
        int watermarkIndex = -1;
        for (int i = 0; incremental && i < cols.size(); i++)
            if (incrColSrc.equals(cols.get(i).getName())) { watermarkIndex = i; break; }
        // 增量过滤条件全程不变,提前算出来:既给每页读取用,也给 mdn 校验先拿总行数。
        String where = incremental && !"mongodb".equals(sourceEngine)
                ? watermarkWhere(sourceEngine, incrColSrc, watermark, sourceType) : null;
        // mdn 明文校验器:行数 ≤ 阈值全量检查,超过阈值抽样;校验发生在写库之前。
        MdnCheckState mdnState = newMdnCheck(task, cols, src, where);
        if(mdnState!=null && mdnState.total>=0) totalProgress(mdnState.total);
        String orderBy = incremental && !incrColSrc.isEmpty() ? incrColSrc : null;
        // 小数据量(≤ 阈值):先把源完整读一遍并校验,通过后才清表/写库 —— 避免校验失败时目标已被清空。
        List<List<List<String>>> mdnBuffered = null;
        if (mdnState != null && (mdnState.dynamic || (mdnState.total >= 0
                && mdnState.total <= mdnState.maxRows))) {
            mdnBuffered = new ArrayList<>();
            int preOffset = 0;
            while (true) {
                checkCancelled();
                List<List<String>> page;
                try {
                    phase("预读与校验数据");
                    page = src.page(where, PAGE_SIZE, preOffset, orderBy);
                } catch (RuntimeException e) {
                    throw new IllegalStateException("读取源数据失败: " + msg(e));
                }
                readProgress(page.size());
                if (page.isEmpty()) {
                    if(progress.get()!=null) totalProgress(progress.get().readRows());
                    mdnState = null; break;
                }
                checkMdnPage(mdnState, page);
                mdnBuffered.add(page);
                if (page.size() < PAGE_SIZE) {
                    if(progress.get()!=null) totalProgress(progress.get().readRows());
                    mdnState = null; break;
                }
                preOffset += PAGE_SIZE;
                // 流式源预检到阈值后继续逐批校验，避免无限缓存。
                if (mdnState.dynamic && preOffset >= mdnState.maxRows) break;
            }
            // 完整预检时 mdnState 已清空；流式源继续保留已检查行数。
        }
        if (!incremental && task.isTruncate()) {
            phase("清理目标表");
            truncateTarget(task,client,"TRUNCATE TABLE IF EXISTS " + qualified + " SYNC");
        }
        // 循环分页读取源数据,直到取完为止。
        while (true) {
            // 协作式检查:任务超时/被取消则及时中断。
            checkCancelled();
            // 拉取当前页源数据:小数据量走已校验过的缓冲,其余从源按页读取。
            List<List<String>> rows;
            boolean prechecked = mdnBuffered != null && offset / PAGE_SIZE < mdnBuffered.size();
            if (prechecked) {
                int pageIndex = offset / PAGE_SIZE;
                rows = mdnBuffered.get(pageIndex);
            } else if (mdnBuffered != null && mdnState == null) {
                rows = java.util.Collections.emptyList();
            } else {
                try {
                    phase("读取源数据");
                    rows = src.page(where, PAGE_SIZE, offset, orderBy);
                    readProgress(rows.size());
                } catch (RuntimeException e) {
                    // 源读取失败时报错并携带根因。
                    throw new IllegalStateException("读取源数据失败: " + msg(e));
                }
            }
            // 本页行数。
            int n = rows.size();
            // 无更多数据则结束循环。
            // 结束条件有两个:取到 0 行,或不满一页(见下方 break)。0 行既表示读到末尾,也
            // 可能是源在同步过程中被清空/删除——两种情形都按“结束”处理,不会写成空批次。
            if (n == 0) {
                break;
            }
            // 累计已读行数。
            stats.rowsRead += n;
            phase("校验与转换数据");
            // mdn 明文校验:必须在写入当前批次之前;非法则整次同步中止。
            if (!prechecked) checkMdnPage(mdnState, rows);

            if (incremental && watermarkIndex >= 0)
                for (List<String> row : rows) {
                    String value = row.get(watermarkIndex);
                    SyncWatermark.requireExact(value, watermarkTargetType);
                    // 映射转换也可能先收窄范围或精度，实际目标列更宽时仍须校验。
                    SyncWatermark.requireExact(value, incrMappingType);
                }

            // 将源行按映射转换成目标类型的值。
            // 自动建表时排序键已声明为非 Nullable，排序键空值按类型默认值落库。
            List<SyncFieldMapping> insertMapping = nonNullableOrderKeyMapping(runtimeMappings, orderKey, task.isAutoCreateTable());
            List<List<Object>> converted = convertRows(rows, srcIdx, insertMapping, sourceIdentifier);
            // 以 batch 方式写入目标表。
            try {
                phase("写入目标表");
                client.insertBatch(qualified, targetCols, converted);
            } catch (RuntimeException e) {
                // 写入失败时报错并携带根因。
                throw new IllegalStateException("写入目标表失败: "
                        + com.el2ck.util.PartitionRule.explainPartitionInsertError(msg(e)));
            }
            // 累计已写行数。
            stats.rowsWritten += n;
            writtenProgress(n);

            // 不足一页说明已到末尾。
            if (n < PAGE_SIZE) {
                break;
            }
            // 否则偏移量前进一页继续。
            offset += PAGE_SIZE;
        }
        // 返回统计结果。
        return stats;
    }

    // =====================================================================
    // 目标连接/表工具
    // =====================================================================

    /** 当前任务所有者的目标配置(只读连接拒绝同步写入)。 */
    private CkConfig targetConfig(long id) {
        Connection c = store.getConnection(id);
        if (c == null) throw new IllegalStateException("记录不存在");
        CkConfig cfg = identities.config(id);
        // 只读连接禁止同步写入 —— 这是同步侧独有的语义(查询/工作流不拒绝)。
        if (cfg.isReadOnly()) {
            throw new IllegalStateException(String.format("目标连接 \"%s\" 为只读模式,禁止同步写入", c.getName()));
        }
        return cfg;
    }

    private CkClient targetClient(long id) {
        try {
            return identities.client(id);
        } catch (RuntimeException e) {
            throw new IllegalStateException(msg(e),e);
        }
    }

    /** 保存只读取目标结构，不创建库表或补列。 */
    public void validateTargetSchema(SyncTask task, boolean directory) {
        CkClient client = targetClient(task.getConnectionId());
        if (!ckTableExists(client, task.getTargetDb(), task.getTargetTable())) {
            if (!task.isAutoCreateTable()) throw new IllegalArgumentException("目标表不存在，且任务未启用自动创建表");
            return;
        }
        List<Column> columns=describeSyncTarget(task,client,quoteCK(task.getTargetDb()) + "." + quoteCK(task.getTargetTable()),directory);
        validateTargetSchema(task,directory,columns);
        DataSource source=store.getDataSource(task.getDataSourceId());
        if(task.isResumable() && source!=null && DataSource.KAFKA.equals(source.getType()))
            KafkaSegmentReader.validateColumns(columns,task.isAutoCreateTable());
        if(task.isResumable() && source!=null && DataSource.SFTP.equals(source.getType()) && !def(task.getRemotePath()).trim().isEmpty())
            SftpSegmentReader.validateColumns(columns,task.isAutoCreateTable());
    }

    private static String incrementalColumn(SyncTask task) {
        return SyncTask.MODE_INCREMENTAL.equals(task.getMode()) ? def(task.getIncrColumn()).trim() : null;
    }

    private static void validateTargetSchema(SyncTask task, boolean directory, List<Column> columns) {
        List<SyncFieldMapping> mappings = new ArrayList<>(task.getMapping());
        String sourceId = task.isOds() ? "ods_source_id" : "source_id";
        String createdAt = task.isOds() ? "ods_insert_at" : "create_time";
        mappings.add(constMapping(sourceId, "String"));
        mappings.add(constMapping(createdAt, "DateTime"));
        if (directory) mappings.add(constMapping("path", "String"));
        java.util.Set<String> addable = new java.util.HashSet<>();
        if (task.isAutoCreateTable()) {
            addable.add(sourceId); addable.add(createdAt);
            if (directory) addable.add("path");
        }
        SyncMappingTypes.validateTarget(mappings, columns, incrementalColumn(task), addable);
        Map<String, String> types = SyncMappingTypes.columnTypes(columns);
        for (String name : directory ? new String[]{sourceId, "path"} : new String[]{sourceId})
            if (types.containsKey(name) && !SyncTime.baseType(types.get(name)).matches("string|fixedstring\\(.*\\)"))
                throw new IllegalArgumentException("系统字段「" + name + "」必须为字符串类型，实际为 " + types.get(name));
        if (types.containsKey(createdAt) && !SyncTime.isTimestamp(types.get(createdAt)))
            throw new IllegalArgumentException("系统字段「" + createdAt + "」必须为时间戳类型，实际为 " + types.get(createdAt));

    }

    /** 通过 system.tables 查询目标表是否存在。 */
    private boolean ckTableExists(CkClient client, String db, String table) {
        try {
            // 参数化查询 system.tables 中匹配库名与表名的计数。
            QueryResult res = client.query("SELECT count() FROM system.tables WHERE database = ? AND name = ?",
                    java.util.Arrays.asList((Object) db, table));
            // 结果非空且首个值非 null 时取出该计数值。
            if (!res.getRows().isEmpty() && !res.getRows().get(0).isEmpty() && res.getRows().get(0).get(0) != null) {
                Object v = res.getRows().get(0).get(0);
                try {
                    // 计数值大于 0 说明表存在。
                    return Long.parseLong(String.valueOf(v).trim()) > 0;
                } catch (NumberFormatException e) {
                    // 数值解析失败视为表不存在。
                    return false;
                }
            }
            // 无结果说明表不存在。
            return false;
        } catch (RuntimeException e) {
            // 保留异常链；网络故障进入本轮自动恢复，不能因只剩文案而直接终止。
            throw new IllegalStateException(msg(e),e);
        }
    }

    /** 目标表已存在且开启自动建表时,用 ALTER 补齐系统固定字段(按 ODS 与否为 source_id/create_time 或 ods_source_id/ods_insert_at),防止写入缺列报错。 */
    private void ensureFixedColumns(CkClient client, String qualified, String sourceIdCol, String insertAtCol) {
        try {
            client.exec("ALTER TABLE " + qualified + " ADD COLUMN IF NOT EXISTS " + quoteCK(sourceIdCol) + " String");
            client.exec("ALTER TABLE " + qualified + " ADD COLUMN IF NOT EXISTS " + quoteCK(insertAtCol) + " DateTime");
        } catch (RuntimeException e) {
            throw new IllegalStateException("补充目标固定字段失败: " + msg(e));
        }
    }

    /** 目标表是否已有某列(忽略大小写;ClickHouse 列名区分大小写,但历史任务可能存在大小写差异)。 */
    private static boolean hasColumn(List<Column> columns, String name) {
        if (columns == null || name == null) return false;
        for (Column c : columns) {
            if (c.getName() != null && c.getName().equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    /** 目标库必须已存在;平台不代用户建库。存在性用管理身份检查,业务用户不需要库级可见权限。 */
    private void requireTargetDatabase(long connectionId, String db) {
        try {
            CkClient management = identities.managementClient(connectionId);
            QueryResult res = management.query("SELECT count() FROM system.databases WHERE name = ?",
                    java.util.Collections.<Object>singletonList(db));
            long count = 0;
            if (!res.getRows().isEmpty() && !res.getRows().get(0).isEmpty() && res.getRows().get(0).get(0) != null) {
                // HTTP 驱动可能把 count()/UInt64 返回为字符串;按文本解析,避免 Number 强转失败。
                count = Long.parseLong(String.valueOf(res.getRows().get(0).get(0)).trim());
            }
            if (count == 0) {
                throw new IllegalStateException("目标库不存在: " + db + "（平台仅允许 ods_data / dwd_data）");
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IllegalStateException("检查目标库失败: " + msg(e),e);
        }
    }

    /**
     * 以任务所有者身份自动建表:先登记 provisioning,临时授予精确 CREATE TABLE,执行后立即收回,
     * 建表成功再授予所有者读写并转 active。失败不认领已有表。
     */
    private CkClient createManagedTable(SyncTask task, CkClient client, String qualified,
                                    List<SyncFieldMapping> mappings, String orderKey,
                                    String partitionExpr, List<String> sortingKeys, List<String> primaryKeys,
                                    String phoneColumn) {
        String db = def(task.getTargetDb());
        String table = def(task.getTargetTable());
        if (lifecycle.physicalExists(task.getConnectionId(), db, table)) {
            throw new IllegalStateException("目标表已存在但尚未完成平台授权，请联系管理员核对归属");
        }
        long assetId = lifecycle.reserveSyncTarget(task.getConnectionId(), db, table, task.getId());
        boolean granted = false;
        try {
            lifecycle.grantCreate(task.getConnectionId(), db, table);
            granted = true;
            createTable(client, qualified, mappings, orderKey, partitionExpr, sortingKeys, primaryKeys,
                    phoneColumn, task.getDescription());
            lifecycle.revokeCreate(task.getConnectionId(), db, table);
            granted = false;
            // 授予所有者权限会让该用户的旧连接失效,必须重新取一条带默认角色的会话继续写入。
            lifecycle.activateTarget(assetId);
            return identities.client(task.getConnectionId());
        } catch (RuntimeException e) {
            String error = msg(e);
            // CREATE 响应丢失或出现同名表时无法证明物理对象属于本次请求，绝不能按名称补偿 DROP。
            // 即使建表成功后授权失败，也保留表和 failed 资产，供核对恢复，避免误删业务数据。
            try { lifecycle.failTarget(assetId, error); }
            catch (RuntimeException failure) { e.addSuppressed(failure); }
            throw new IllegalStateException("自动建表或授权未完成，已保留现有物理表，请核对表归属与授权: " + error,e);
        } finally {
            if (granted) {
                try { lifecycle.revokeCreate(task.getConnectionId(), db, table); } catch (RuntimeException ignored) { }
            }
        }
    }

    private void createTable(CkClient client, String qualified, List<SyncFieldMapping> mappings, String orderKey,
                             String partitionExpr, List<String> sortingKeys, List<String> primaryKeys,
                             String phoneColumn, String tableComment) {
        // 拼接 DDL(纯逻辑,可单测),再执行。
        String sql = buildCreateTableSql(qualified, mappings, orderKey, partitionExpr, sortingKeys, primaryKeys,
                phoneColumn, tableComment);
        try {
            client.exec(sql);
        } catch (RuntimeException e) {
            throw new IllegalStateException("自动建表失败: " + msg(e));
        }
    }

    /**
     * 拼接自动建表的 CREATE TABLE 语句但不执行，便于单元测试。
     * PARTITION BY + 多列 ORDER BY + 可选 PRIMARY KEY(须为排序键前缀);排序键列强制非 Nullable。
     *
     * @param qualified     已限定的目标表名
     * @param mappings      字段映射；同时决定列类型、可空性和号码列信息
     * @param orderKey      单列排序键；sortingKeys 为空时作为回退，仍为空时使用首个目标列
     * @param partitionExpr 分区表达式；空值表示不分区
     * @param sortingKeys   多列排序键，优先于 orderKey
     * @param primaryKeys   可选主键，必须是最终排序键的前缀
     * @param phoneColumn   源侧号码列，用于分区键校验
     * @param tableComment  任务说明；有内容时作为新建目标表的表注释
     * @return 可直接交给 ClickHouse 执行的 CREATE TABLE DDL
     */
    static String buildCreateTableSql(String qualified, List<SyncFieldMapping> mappings, String orderKey,
                                      String partitionExpr, List<String> sortingKeys, List<String> primaryKeys,
                                      String phoneColumn, String tableComment) {
        // 最终排序键列表:多列 sortingKeys 优先;否则回退单列 orderKey;仍为空则用首个目标列兜底。
        List<String> sortCols = new ArrayList<>();
        if (sortingKeys != null) {
            for (String k : sortingKeys) {
                String v = def(k).trim();
                if (!v.isEmpty()) sortCols.add(v);
            }
        }
        if (sortCols.isEmpty() && orderKey != null && !orderKey.trim().isEmpty()) {
            sortCols.add(orderKey.trim());
        }
        if (sortCols.isEmpty() && !mappings.isEmpty()) {
            sortCols.add(def(mappings.get(0).getTargetColumn()).trim());
        }
        // 主键列表(可选);须为排序键前缀(MergeTree 约束),否则拒绝。
        List<String> primCols = new ArrayList<>();
        if (primaryKeys != null) {
            for (String k : primaryKeys) {
                String v = def(k).trim();
                if (!v.isEmpty()) primCols.add(v);
            }
        }
        if (!primCols.isEmpty()) {
            if (primCols.size() > sortCols.size()) {
                throw new IllegalStateException("主键必须是排序键的前缀");
            }
            for (int i = 0; i < primCols.size(); i++) {
                if (!primCols.get(i).equals(sortCols.get(i))) {
                    throw new IllegalStateException("主键必须是排序键的前缀");
                }
            }
        }
        // 排序键集合(用于把这些列强制成非 Nullable)。
        java.util.Set<String> sortSet = new java.util.LinkedHashSet<>(sortCols);
        // 分区键先校验:所有写入路径都经过这里,防界面/API 之外的路子绕过去(拿号码列当分区键会把表写废)。
        String pe = def(partitionExpr).trim();
        if (!pe.isEmpty()) {
            String perr = com.el2ck.util.PartitionRule.validatePartitionExpr(
                    pe, def(phoneColumn).trim(), com.el2ck.util.PartitionRule.partitionTypesOfMapping(mappings, false));
            if (perr != null) {
                throw new IllegalStateException(perr);
            }
        }
        // 同名竞争必须报错；IF NOT EXISTS 会把他人刚创建的表误当成本任务创建成功。
        StringBuilder sb = new StringBuilder("CREATE TABLE " + qualified + " (");
        // 逐条映射拼接列定义。
        for (int i = 0; i < mappings.size(); i++) {
            SyncFieldMapping m = mappings.get(i);
            if (i > 0) {
                sb.append(", ");
            }
            String colType = ckType(mappingType(m));
            // MergeTree 不允许 Nullable 排序键;自动建表时排序键列强制非 Nullable。
            boolean orderColumn = sortSet.contains(def(m.getTargetColumn()).trim());
            if (m.isNullable() && !orderColumn) {
                colType = "Nullable(" + colType + ")";
            }
            sb.append(quoteCK(def(m.getTargetColumn()))).append(' ').append(colType);
            if (def(m.getDefaultValue()) != null && !def(m.getDefaultValue()).isEmpty()) {
                sb.append(" DEFAULT ").append(defaultSql(m));
            }
            if (m.getComment() != null && !m.getComment().isEmpty()) {
                sb.append(" COMMENT ").append(com.el2ck.util.Sql.quoteString(m.getComment()));
            }
        }
        // 收尾:引擎 + 分区 + 排序键 + 主键。
        sb.append(") ENGINE = MergeTree");
        if (!pe.isEmpty()) {
            sb.append(" PARTITION BY ").append(pe);
        }
        sb.append(" ORDER BY ").append(keyList(sortCols));
        if (!primCols.isEmpty()) {
            sb.append(" PRIMARY KEY ").append(keyList(primCols));
        }
        // 创建时记录任务说明；统一转义引号和反斜杠，保证说明按文本写入表注释。
        if (tableComment != null && !tableComment.trim().isEmpty()) {
            sb.append(" COMMENT ").append(com.el2ck.util.Sql.quoteString(tableComment));
        }
        return sb.toString();
    }

    /** 排序键/主键列表渲染:单列直接引用,多列用括号包裹。 */
    private static String keyList(List<String> cols) {
        if (cols.size() == 1) {
            return quoteCK(cols.get(0));
        }
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(quoteCK(cols.get(i)));
        }
        return sb.append(')').toString();
    }

    /** 判断排序键对应目标列是否声明为可空(MergeTree 排序键不允许 Nullable)。 */
    static boolean hasNullableOrderKey(List<SyncFieldMapping> mappings, String orderKey) {
        // 映射或排序键为空时无需处理。
        if (mappings == null || orderKey == null || orderKey.trim().isEmpty()) {
            return false;
        }
        // 遍历映射,判断排序键对应目标列是否可空。
        for (SyncFieldMapping mapping : mappings) {
            if (mapping != null && orderKey.trim().equals(def(mapping.getTargetColumn()).trim())
                    && (mapping.isNullable() || mappingType(mapping).toUpperCase().startsWith("NULLABLE("))) {
                return true;
            }
        }
        return false;
    }

    /** 查询目标表某列最大值(空表返回 null)。 */
    private String ckMax(CkClient client, String qualified, String col) {
        // 执行 SELECT max(col) 聚合查询。
        QueryResult res = client.query("SELECT toString(maxOrNull(" + quoteCK(col) + ")) FROM " + qualified);
        // 有结果行时取首行首列。
        if (!res.getRows().isEmpty() && !res.getRows().get(0).isEmpty()) {
            Object v = res.getRows().get(0).get(0);
            // 值为空返回 null,否则转字符串返回。
            return v == null ? null : String.valueOf(v);
        }
        // 无结果行返回 null。
        return null;
    }

    /** 自动建表的排序键必须非空；复制映射对象，避免修改任务中保存的原始配置。 */
    // 建表时排序键被强制成非 Nullable(见 createTable),写入时就必须给非空值,否则同一列
    // “建表非空、写入补 NULL”会直接报错;这里把排序键的映射标记为非可空,让 convertCell 走
    // 类型默认值分支。必须复制:原 mappings 属于任务配置,改了会被持久化/污染后续执行。
    private static List<SyncFieldMapping> nonNullableOrderKeyMapping(List<SyncFieldMapping> mappings,
                                                                      String orderKey, boolean autoCreateTable) {
        if (!autoCreateTable || mappings == null || orderKey == null || orderKey.trim().isEmpty()) {
            return mappings;
        }
        List<SyncFieldMapping> result = new ArrayList<>();
        for (SyncFieldMapping source : mappings) {
            SyncFieldMapping copy = new SyncFieldMapping();
            copy.setSourceColumn(source.getSourceColumn());
            copy.setTargetColumn(source.getTargetColumn());
            copy.setType(source.getType());
            copy.setNullable(orderKey.trim().equals(def(source.getTargetColumn()).trim()) ? Boolean.FALSE : source.getNullable());
            copy.setDefaultValue(source.getDefaultValue());
            copy.setComment(source.getComment());
            copy.setAutoMdnMd5(source.getAutoMdnMd5());
            result.add(copy);
        }
        return result;
    }

    /** 将目标水位转换为源查询可用的字面量，数值类型不加字符串引号。 */
    // 保留给“源侧按水位过滤”的写法使用:数值列若被引号包成字符串,在源库里会退化成
    // 字符串比较(或触发隐式转换告警),故数值不加引号、其余类型统一转义引号。
    // 用于固定水位过滤和 JDBC 主键键集条件，保持源值不变。
    static String incrementLiteral(String value, String type) {
        String v = value == null ? "" : value.trim();
        boolean numeric = compatibleIncrementalTypes(type, "Int64");
        if (numeric) {
            if (!v.matches("[-+]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][-+]?[0-9]+)?"))
                throw new IllegalStateException("数值水位不是有效的有限数值");
            return v;
        }
        return quoteStr(v);
    }

    // =====================================================================
    // 值转换
    // =====================================================================

    /** 将 DuckDB 结果行按映射转成目标类型:逐行逐列校验并转换。 */
    private List<List<Object>> convertRows(List<List<String>> rows, List<Integer> srcIdx,
                                           List<SyncFieldMapping> mappings) {
        return convertRows(rows, srcIdx, mappings, "");
    }

    static String watermarkWhere(String engine, String column, String watermark, String type) {
        if (watermark == null || watermark.trim().isEmpty()) return null;
        return DbSource.quoteIdent(engine, column) + " > " + incrementLiteral(watermark, type);
    }

    static String keysetWhere(String engine, String key, String last, String type, String watermarkWhere) {
        String after = DbSource.quoteIdent(engine, key) + " > " + incrementLiteral(last, type);
        return watermarkWhere == null || watermarkWhere.isEmpty()
                ? after : "(" + watermarkWhere + ") AND " + after;
    }

    private List<List<Object>> convertRows(List<List<String>> rows, List<Integer> srcIdx,
                                           List<SyncFieldMapping> mappings,
                                           String sourceIdentifier) {
        return convertRows(rows, srcIdx, mappings, sourceIdentifier, "");
    }

    /** 转换,并支持注入来源文件路径常量列(SFTP 目录同步的 path 列,占位源列下标 -3)。 */
    private List<List<Object>> convertRows(List<List<String>> rows, List<Integer> srcIdx,
                                           List<SyncFieldMapping> mappings,
                                           String sourceIdentifier, String sourceFilePath) {
        return convertRows(rows,srcIdx,mappings,sourceIdentifier,sourceFilePath,java.time.LocalDateTime.now().withNano(0));
    }

    private List<List<Object>> convertRows(List<List<String>> rows,List<Integer> srcIdx,List<SyncFieldMapping> mappings,
                                           String sourceIdentifier,String sourceFilePath,java.time.LocalDateTime batchTime) {
        // 预取每条映射的目标类型(用于报错提示)。
        // 同时缓存 ckType 归一化结果:类型串在“每行 × 每列”里都要用到,放在行循环外算一次,
        // 避免 10000 行 × N 列次重复的字符串判空与 Bool→UInt8 归一。
        List<String> types = new ArrayList<>(mappings.size());
        for (SyncFieldMapping mapping : mappings) types.add(mappingType(mapping));
        // 转换结果容器。
        List<List<Object>> out = new ArrayList<>(rows.size());
        // 逐行转换。
        for (int ri = 0; ri < rows.size(); ri++) {
            // 每行边界也做取消检查。
            checkCancelled();
            List<String> row = rows.get(ri);
            // 存放本行转换后的值。
            List<Object> vals = new ArrayList<>(srcIdx.size());
            // 逐列转换。
            for (int ci = 0; ci < srcIdx.size(); ci++) {
                int idx = srcIdx.get(ci);
                // 下标 + 目标列名 双重判定:负下标只可能来自上面注入的系统列,但仍校验列名,
                // 这样即便将来有人按位置硬塞进一条 -1 的映射,也不会被误当成 create_time 填时间。
                if (idx == -2 && (("source_id".equalsIgnoreCase(def(mappings.get(ci).getTargetColumn())))
                        || "ods_source_id".equalsIgnoreCase(def(mappings.get(ci).getTargetColumn())))) {
                    // 系统列 source_id / ods_source_id(占位源列下标 -2):直接写入数据源唯一标识值。
                    vals.add(sourceIdentifier);
                    continue;
                }
                if (idx == -1 && (("create_time".equalsIgnoreCase(def(mappings.get(ci).getTargetColumn())))
                        || "ods_insert_at".equalsIgnoreCase(def(mappings.get(ci).getTargetColumn())))) {
                    // 系统列 create_time / ods_insert_at(占位源列下标 -1):写入本次同步的当前时间。
                    vals.add(SyncTime.forTarget(Timestamp.valueOf(batchTime), types.get(ci)));
                    continue;
                }
                if (idx == -3 && "path".equalsIgnoreCase(def(mappings.get(ci).getTargetColumn()))) {
                    // 系统列 path(占位源列下标 -3):SFTP 目录同步时写入该行来源文件的远端完整路径。
                    vals.add(sourceFilePath);
                    continue;
                }
                // 源列下标越界说明源结构异常。
                if (idx < 0 || idx >= row.size()) {
                    throw new IllegalStateException(String.format("第 %d 行取值越界(列索引 %d)", ri + 1, idx));
                }
                // 转换单个单元格并记录错误位置。
                try {
                    String raw = row.get(idx);
                    vals.add(convertCell(raw, mappings.get(ci),batchTime));
                } catch (RuntimeException e) {
                    throw new IllegalStateException(String.format("第 %d 行第 %d 列(%s)转换失败: %s", ri + 1, ci + 1, types.get(ci), e.getMessage()));
                }
            }
            // 行转换完成加入结果。
            out.add(vals);
        }
        // 返回转换后的全部行。
        return out;
    }

    /** 判定类型(剥掉外层 Nullable 后)是否为可按数值比较的整型/浮点/Decimal。 */
    private static boolean isNumericType(String type) {
        String t = SyncTime.baseType(type);
        return t.matches("u?int(8|16|32|64|128|256)|float(32|64)|decimal.*|numeric.*");
    }

    /**
     * 判断一个类型本身能否充当增量键:数值、日期(date/date32)或时间戳。
     *
     * <p>只回答「这一列能不能按大小排序、与既有水位比较」,不检查源列与目标列是否互相配对——配对规则
     * 见 {@link #compatibleIncrementalTypes}。判定前经 {@link SyncTime#baseType} 剥掉外层
     * Nullable(...),故 Nullable(DateTime) 同样算数。
     */
    public static boolean isIncrementalType(String type) {
        if (isNumericType(type)) return true;
        String t = SyncTime.baseType(type);
        return t.matches("date|date32") || SyncTime.isTimestamp(t);
    }

    /**
     * 判断增量源列与目标列的类型能否配对做增量:源值必须能在目标列上按大小与既有 max 水位比较。
     *
     * <p>判定以【目标类型】分支:(1) 目标为数值 → 源须是任意数值写法,下面的正则刻意覆盖 MySQL/
     * PostgreSQL/ClickHouse 各家的拼法(int/integer/bigint/…/float/double/real/decimal/number/serial/
     * ".* unsigned"),只要落到同一数值语义即放行;(2) 目标为时间戳 → 源也须是时间戳;(3) 其余(目标
     * 为日期)→ 源须是 date/date32 且目标本身可当增量键。两侧都先经 {@link SyncTime#baseType} 剥掉
     * Nullable。
     *
     * <p>⚠️ 结果取决于【映射后的目标类型】而非源列“看起来像数值”:同一源列若改映射为 String,就会从
     * 兼容变成不兼容。因此映射一旦变更,这道校验必须重跑,否则会放行一个无法与水位比较的增量键。
     */
    public static boolean compatibleIncrementalTypes(String sourceType, String targetType) {
        String source = SyncTime.baseType(sourceType);
        if (isNumericType(targetType)) {
            return source.matches("u?int(2|4|8|16|32|64|128|256)?|integer|u?bigint|smallint|tinyint|mediumint|hugeint|"
                    + "float.*|double.*|real|decimal.*|numeric.*|number.*|serial.*|.* unsigned");
        }
        if (SyncTime.isTimestamp(targetType)) return SyncTime.isTimestamp(sourceType);
        return source.matches("date|date32") && isIncrementalType(targetType);
    }

    /** 按目标类型转换单个源单元格:空串或 "null" 走可空/默认值/类型缺省,真实值按目标类型解析。 */
    static Object convertCell(String raw, SyncFieldMapping mapping) {
        return convertCell(raw,mapping,java.time.LocalDateTime.now().withNano(0));
    }

    private static Object convertCell(String raw,SyncFieldMapping mapping,java.time.LocalDateTime batchTime) {
        raw = autoHashMdn(raw, mapping);
        // 取该列目标类型(小写规范化)。
        String type = mappingType(mapping);
        // 空值/空串/字符串 "null" 视为缺省,走默认值或类型默认。
        // 下面三段是严格的优先级:可空列 → NULL;非可空列 → 配置的默认值;再不行 → 类型默认值。
        // 与工作流 ck_write 的“一律补默认值”不同,这里对可空列保留了真正的 NULL 语义,因为同步
        // 任务的目标表由字段映射显式声明可空性,契约更强。
        if (raw == null || raw.trim().isEmpty() || "null".equalsIgnoreCase(raw.trim())) {
            String fallback = def(mapping == null ? null : mapping.getDefaultValue()).trim();
            if (!fallback.isEmpty()) return convertedDefault(mapping,batchTime);
            if (mapping != null && mapping.isNullable()) return null;
            String t = type.toLowerCase();
            if (t.startsWith("uint") || t.startsWith("int") || t.startsWith("float") || t.startsWith("decimal")) {
                return convertValue("0", type);
            }
            // 布尔缺省 false。
            if ("bool".equals(t) || "boolean".equals(t)) return Boolean.FALSE;
            // 日期缺省 1970-01-01。
            if ("date".equals(t) || "date32".equals(t)) return Date.valueOf("1970-01-01");
            // 日期时间缺省 1970-01-01 00:00:00。
            if (SyncTime.isTimestamp(t)) return Timestamp.valueOf("1970-01-01 00:00:00");
            // 其余类型缺省空字符串。
            return "";
        }
        // 有真实值则直接按目标类型转换。
        return convertValue(raw, type);
    }

    /** 逐行识别明文号码；已有哈希和空值保持原样，重试不会重复哈希。 */
    private static String autoHashMdn(String raw, SyncFieldMapping mapping) {
        if (mapping == null || !Boolean.TRUE.equals(mapping.getAutoMdnMd5())) return raw;
        String phone = plainMdn(raw);
        if (phone == null) return raw;
        return org.springframework.util.DigestUtils.md5DigestAsHex(
                phone.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** 默认值与源值使用相同转换，避免补值和建表默认值写入明文手机号。 */
    private static Object convertedDefault(SyncFieldMapping mapping) {
        return convertedDefault(mapping,java.time.LocalDateTime.now().withNano(0));
    }

    private static Object convertedDefault(SyncFieldMapping mapping,java.time.LocalDateTime batchTime) {
        Object value = evaluateDefault(mapping,batchTime);
        return value instanceof String ? autoHashMdn((String) value, mapping) : value;
    }

    /** 默认值只接受类型字面量及 now()/today()，保存校验、DDL 与写入共用契约。 */
    public static String defaultSql(SyncFieldMapping mapping) {
        String raw = def(mapping.getDefaultValue()).trim();
        if (raw.isEmpty()) return "";
        Object value = convertedDefault(mapping);
        if ("now()".equalsIgnoreCase(raw) || "today()".equalsIgnoreCase(raw)) return raw.toLowerCase();
        if (value == null) return "NULL";
        if (value instanceof Number) return value.toString();
        if (value instanceof Boolean) return Boolean.TRUE.equals(value) ? "1" : "0";
        return quoteStr(value instanceof Timestamp
                ? SyncTime.format(((Timestamp) value).toLocalDateTime()) : value.toString());
    }

    /**
     * 解析映射配置的默认值,返回可直接写入目标表的 Java 值(供补空值)或拼 DDL 用。
     *
     * <p>保存校验、建表 DDL({@link #defaultSql})与运行时补值({@link #convertCell})共用同一份实现,
     * 目的是让「校验能过、DDL 能建、写入能塞」三处判定完全一致,避免只在某一路生效。
     *
     * <p>只认四类写法:类型字面量(数值/字符串/日期时间)、now()(仅 DateTime)、today()(仅 Date/
     * Date32)、以及 null(仅可空列)。字面量外的裸值若含 ( ) ; 一律拒绝——⚠️ 默认值是配置项而非 SQL
     * 片段,放行函数调用或分号等于给配置开了注入/建表失败的开口,故宁可在此报错。
     *
     * <p>数值默认值会按目标整数类型做范围校验、浮点须有限,越界在建表前就报错而不是留到写入时才炸。
     * 任何解析失败都统一包成带目标列名的 IllegalArgumentException,便于界面直接指认哪一列配错。
     */
    private static Object evaluateDefault(SyncFieldMapping mapping) {
        return evaluateDefault(mapping,java.time.LocalDateTime.now().withNano(0));
    }

    private static Object evaluateDefault(SyncFieldMapping mapping,java.time.LocalDateTime batchTime) {
        String raw = def(mapping.getDefaultValue()).trim();
        String type = mappingType(mapping).toLowerCase(java.util.Locale.ROOT);
        if (type.startsWith("nullable(") && type.endsWith(")")) type = type.substring(9, type.length()-1);
        try {
            if ("now()".equalsIgnoreCase(raw)) {
                if (!type.startsWith("datetime")) throw new IllegalArgumentException("now() 仅适用于 DateTime 类型");
                return Timestamp.valueOf(batchTime.withNano(0));
            }
            if ("today()".equalsIgnoreCase(raw)) {
                if (!"date".equals(type) && !"date32".equals(type)) throw new IllegalArgumentException("today() 仅适用于 Date/Date32 类型");
                return Date.valueOf(batchTime.toLocalDate());
            }
            if ("null".equalsIgnoreCase(raw)) {
                if (!mapping.isNullable()) throw new IllegalArgumentException("非空列不能默认 NULL");
                return null;
            }
            boolean quoted = raw.length() >= 2 && raw.startsWith("'") && raw.endsWith("'");
            if (quoted) raw = raw.substring(1, raw.length()-1).replace("''", "'");
            else if (raw.indexOf('(') >= 0 || raw.indexOf(')') >= 0 || raw.indexOf(';') >= 0) {
                throw new IllegalArgumentException("仅支持字面量、now() 或 today()");
            }
            if (type.startsWith("datetime")) return convertValue(raw, type);
            if ("date".equals(type) || "date32".equals(type)) return Date.valueOf(java.time.LocalDate.parse(raw));
            Object result = convertValue(raw, type);
            if (type.matches("u?int(8|16|32|64|128|256)")) {
                boolean unsigned = type.startsWith("u");
                int bits = Integer.parseInt(type.substring(unsigned ? 4 : 3));
                java.math.BigInteger number = new java.math.BigInteger(result.toString());
                java.math.BigInteger limit = java.math.BigInteger.ONE.shiftLeft(unsigned ? bits : bits - 1);
                if (number.compareTo(unsigned ? java.math.BigInteger.ZERO : limit.negate()) < 0 || number.compareTo(limit) >= 0)
                    throw new IllegalArgumentException("整数超出 " + type + " 范围");
            }
            if (result instanceof Double && !Double.isFinite((Double) result)) throw new IllegalArgumentException("数值必须有限");
            if (result instanceof Float && !Float.isFinite((Float) result)) throw new IllegalArgumentException("数值必须有限");
            return result;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("列「" + mapping.getTargetColumn() + "」默认值无效: " + e.getMessage(), e);
        }
    }

    /** 取映射的目标类型并规范化为 ClickHouse 写法(空映射视为无类型)。 */
    static String mappingType(SyncFieldMapping mapping) {
        // 映射为空视为无类型,否则取目标类型并规范化为 ClickHouse 类型写法。
        return mapping == null ? "" : ckType(def(mapping.getType()));
    }

    /** 协作式中断检查:任务超时或被取消时线程被标记中断,此处抛异常以在分页/逐行边界及时终止。 */
    // 用 isInterrupted() 而非 Thread.interrupted():不消费中断标志,后续检查点仍能持续中止。
    // 检查点:每页读取前 + 每行转换时,因此响应延迟上界约为“一批(10000 行)”。
    private static void checkCancelled() {
        Runnable fence = EXECUTION_FENCE.get();
        if (fence != null) fence.run();
        // 当前线程被标记中断即视为任务已超时或被取消。
        if (Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("同步已超时或被中断");
        }
    }

    /** 字符串 → 目标 ClickHouse 类型的 Java 值,供 batch 写入使用。 */
    static Object convertValue(String s, String ckType) {
        // 目标类型转小写并去空白。
        String t = ckType == null ? "" : ckType.trim().toLowerCase();
        // 剥掉外层 Nullable(...)。
        if (t.startsWith("nullable(") && t.endsWith(")")) {
            t = t.substring("nullable(".length(), t.length() - 1).trim();
        }
        // boolean 与 bool 等价。
        if ("boolean".equals(t)) t = "bool";
        // Decimal 家族(decimal / decimal32/64/128/256(P,S))用 BigDecimal 承载,避免 double 丢精度。
        // 放在 switch 之前:类型带括号参数,无法用等值匹配。
        if (t.startsWith("decimal")) {
            try {
                return new java.math.BigDecimal(s == null ? "" : s.trim());
            } catch (NumberFormatException e) {
                throw new IllegalStateException("无法解析 Decimal \"" + s + "\"");
            }
        }
        if (SyncTime.isTimestamp(t)) {
            try { return SyncTime.forTarget(SyncTime.parse(s), t); }
            catch (RuntimeException e) { throw new IllegalStateException("无法解析时间 \"" + s + "\"", e); }
        }
        // 按类型分发转换。
        switch (t) {
            case "string":
            case "lowcardinality(string)":
                // 字符串直接返回;空值给空串。
                return s == null ? "" : s;
            case "int8":
                // 8 位有符号整数。
                return Byte.parseByte(s.trim());
            case "int16":
                // 16 位有符号整数。
                return Short.parseShort(s.trim());
            case "int32":
                // 32 位有符号整数。
                return Integer.parseInt(s.trim());
            case "uint8":
                // 8 位无符号整数(兼容 true/false 等写法)。
                return parseUInt8(s);
            case "uint16":
                // 16 位无符号整数(Java 用 int 容纳)。
                return Integer.parseInt(s.trim());
            case "uint32":
                // 32 位无符号整数(Java 用 long 容纳)。
                return Long.parseLong(s.trim());
            case "int64":
                // 64 位有符号整数。
                return Long.parseLong(s.trim());
            case "uint64":
                // 64 位无符号整数(用 BigInteger 容纳)。
                return new java.math.BigInteger(s.trim());
            case "int128":
            case "int256":
            case "uint128":
            case "uint256":
                // 128/256 位大整型(有/无符号):统一用 BigInteger 承载,避免落回字符串被驱动拒绝。
                return new java.math.BigInteger(s.trim());
            case "bfloat16":
                // bfloat16:JDK 无对应原生类型,按 float 解析(驱动按浮点写入)。
                return Float.parseFloat(s.trim());
            case "float32":
                // 32 位浮点数。
                return Float.parseFloat(s.trim());
            case "float64":
                // 64 位浮点数。
                return Double.parseDouble(s.trim());
            case "bool": {
                // 布尔值解析:小写化处理。
                String v = s == null ? "" : s.trim().toLowerCase();
                switch (v) {
                    // 真值写法。
                    case "true":
                    case "1":
                    case "yes":
                    case "y":
                        return Boolean.TRUE;
                    // 假值写法。
                    case "false":
                    case "0":
                    case "no":
                    case "n":
                    case "":
                        return Boolean.FALSE;
                    default:
                        // 无法识别时报错并带上原文。
                        throw new IllegalStateException("无法解析布尔值 \"" + s + "\"");
                }
            }
            case "date32":
            case "date": {
                try {
                    String d = s == null ? "" : s.trim();
                    // 兼容纯日期与带时刻的来源(ClickHouse 原生 Date 会带 00:00:00)。
                    // 若长度超过 10,截取前 10 位去掉时刻部分。
if (d.length() > 10) {
                        d = d.substring(0, 10);
                    }
                    return Date.valueOf(d);
                } catch (IllegalArgumentException e) {
                    throw new IllegalStateException("无法解析日期 \"" + s + "\"");
                }
            }
            case "datetime": {
                try {
                    // 规范化时间串后按 Timestamp 解析。
                    return Timestamp.valueOf(normalizeDateTime(s));
                } catch (IllegalArgumentException e) {
                    throw new IllegalStateException("无法解析时间 \"" + s + "\"");
                }
            }
            default:
                // 未知类型原样透传。
                return s == null ? "" : s;
        }
    }

    /** 时间字符串规范化:兼容 "yyyy-MM-dd" 与 "yyyy-MM-dd HH:mm:ss[.fff]"(T 分隔也兼容)。 */
    static String normalizeDateTime(String s) {
        // 空串直接返回空。
        if (s == null) return "";
        String v = s.trim();
        // 纯日期(长度 10)补上 00:00:00 时刻。
        if (v.length() == 10 && v.indexOf('-') == 4) return v + " 00:00:00";
        return SyncTime.format(SyncTime.parse(v).toLocalDateTime());
    }

    /** 目标类型:空则默认 String。 */
    static String ckType(String t) {
        // 去首尾空白。
        String v = t == null ? "" : t.trim();
        // 未配置类型时返回默认 String。
        if (v.isEmpty()) return "String";
        // native-jdbc 2.6.x/部分 ClickHouse 版本对 Bool 元数据支持不一致,
        // 映射读取阶段统一落为 UInt8,true/false 由转换逻辑写成 1/0。
        // 布尔类型统一归一化为 UInt8 存储。
        // 归一化只在这一个入口做:建表 DDL、插入列类型、convertValue 分支三处最终都取自
        // 本方法,因此不会出现“建表 Bool、绑值 UInt8”的错配;代价是回读时表现为 0/1 数字。
if ("bool".equalsIgnoreCase(v) || "boolean".equalsIgnoreCase(v)) return "UInt8";
        return v;
    }

    /** 解析 UInt8:识别 true/yes/y 与 false/no/n,其余按短整数解析。 */
    private static Short parseUInt8(String value) {
        // 转小写并去空白。
        String v = value == null ? "" : value.trim().toLowerCase();
        // 真值写法映射为 1。
        if ("true".equals(v) || "yes".equals(v) || "y".equals(v)) return (short) 1;
        // 假值写法映射为 0。
        if ("false".equals(v) || "no".equals(v) || "n".equals(v)) return (short) 0;
        // 其余按 8 位无符号整数字面量解析。
        return Short.parseShort(v);
    }

    // =====================================================================
    // 工具
    // =====================================================================

    /** 解密数据源配置为参数 Map。 */
    private Map<String, Object> decryptConfig(DataSource d) {
        // 无密文配置时返回空 Map。
        if (d.getConfigEnc() == null || d.getConfigEnc().isEmpty()) {
            return new LinkedHashMap<>();
        }
        try {
            // 用密钥解密配置密文。
            String raw = Crypto.decrypt(secret, d.getConfigEnc());
            // 将 JSON 反序列化为 Map。
            Map<String, Object> m = JSON.readValue(raw, Map.class);
            // 结果为 null 时兜底为空 Map。
            return m == null ? new LinkedHashMap<String, Object>() : m;
        } catch (Exception e) {
            // 解密失败时报错并携带根因。
            throw new IllegalStateException(String.format("数据源 \"%s\" 配置解密失败: %s", d.getCode(), msg(e)));
        }
    }

    /** 读取配置项:值为非空字符串则返回,否则返回缺省 def。 */
    private static String cfgStr(Map<String, Object> cfg, String key, String def) {
        // 读取配置项的值。
        Object v = cfg.get(key);
        // 值为非空字符串时返回它。
        if (v instanceof String && !((String) v).isEmpty()) {
            return (String) v;
        }
        // 否则返回缺省值。
        return def;
    }

    /** 解析源文件是否首行为表头(header 未配置时默认 true)。 */
    private static boolean headerFromConfig(Map<String, Object> cfg) {
        // 读取是否首行为表头。
        Object h = cfg.get("header");
        // 布尔值直用,否则默认 true。
        return h instanceof Boolean ? (Boolean) h : true;
    }

    /** 从 config 读取 names(逗号分隔字符串或 JSON 数组);无则返回 null。 */
    private static List<String> namesFromConfig(Map<String, Object> cfg) {
        // 读取 names 配置项;未配置返回 null。
        Object raw = cfg.get("names");
        if (raw == null) {
            return null;
        }
        // 解析结果列表。
        List<String> out = new ArrayList<>();
        if (raw instanceof String) {
            // 逗号分隔字符串:逐段解析并去空白,跳过空段。
            for (String p : ((String) raw).split(",")) {
                String s = p.trim();
                if (!s.isEmpty()) {
                    out.add(s);
                }
            }
        } else if (raw instanceof List) {
            // JSON 数组:取其中非空的字符串元素。
            for (Object it : (List<?>) raw) {
                if (it instanceof String && !((String) it).isEmpty()) {
                    out.add((String) it);
                }
            }
        }
        // 空列表视为未配置返回 null。
        return out.isEmpty() ? null : out;
    }

    /** 用反引号引用 ClickHouse 标识符(内部反引号翻倍转义)。 */
    private static String quoteCK(String s) {
        return "`" + (s == null ? "" : s.replace("`", "``")) + "`";
    }

    /** 生成 SQL 字符串字面量(单引号翻倍转义)。 */
    private static String quoteStr(String s) {
        return "'" + (s == null ? "" : s.replace("'", "''")) + "'";
    }

    /** 名称是否合法标识符:字母或下划线开头,后跟字母/数字/下划线。 */
    private static boolean validIdent(String name) {
        // 用合法模式校验标识符。
        return name != null && IDENT_RE.matcher(name).matches();
    }

    /** 把源列名列表拼成逗号分隔文本(用于报错时提示可用列)。 */
    private static String colNameList(List<Column> cols) {
        // 收集所有列名。
        List<String> names = new ArrayList<>(cols.size());
        for (Column c : cols) {
            names.add(c.getName());
        }
        // 以逗号拼接成列表文本。
        return String.join(", ", names);
    }

    /** 空值取缺省(连接参数未配置时回退到全局设置)。 */
    private static long deref(Long v, long def) {
        // 空值取缺省,否则取原值。
        return v == null ? def : v;
    }

    /** null 安全取值:null 统一归一为空字符串。 */
    private static String def(String s) {
        // 空串兜底为空字符串。
        return s == null ? "" : s;
    }

    /** 提取异常链条中最内层的有效消息。 */
    private static String msg(Throwable e) {
        // 从最外层异常开始。
        Throwable t = e;
        // 只要当前层无消息就继续往更内层找。
        while (t.getCause() != null && (t.getMessage() == null || t.getMessage().isEmpty())) {
            t = t.getCause();
        }
        // 取该层消息。
        String m = t.getMessage();
        // 仍无消息则退化为打印整个异常对象。
        return (m == null || m.isEmpty()) ? String.valueOf(e) : m;
    }
}
