/**
 * 【文件职责】本文件定义数据同步模块的业务服务 SyncService。承载该模块的核心业务规则，协调仓储、连接器或外部服务，并统一转换业务异常和操作状态。
 */

package com.el2ck.service.sync;

import com.el2ck.service.datasource.DataSourceService;
import com.el2ck.service.datasource.DbSource;

import com.el2ck.exception.ApiException;
import com.el2ck.ckpool.CkPool;
import com.el2ck.duck.Duck;
import com.el2ck.entity.connection.Connection;
import com.el2ck.entity.common.Column;
import com.el2ck.entity.datasource.DataSource;
import com.el2ck.entity.sync.SyncFieldMapping;
import com.el2ck.entity.sync.SyncRun;
import com.el2ck.entity.sync.SyncTask;
import com.el2ck.entity.sync.SyncTaskDTO;
import com.el2ck.entity.sync.SyncTaskInput;
import com.el2ck.entity.sync.SyncTaskListDetails;
import com.el2ck.entity.sync.QuickSyncInput;
import com.el2ck.sync.SyncEngine;
import com.el2ck.sync.SyncCheckpointStore;
import com.el2ck.repository.metadata.MetadataRepository;
import com.el2ck.util.PartitionRule;
import com.el2ck.util.SyncMappingTypes;
import org.springframework.scheduling.support.CronExpression;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

/**
 * 同步任务业务逻辑(对应 Go internal/service/sync_service.go)。
 *
 * 任务把「本地文件数据源(CSV/JSON)」按字段映射与策略(full/incremental)写入
 * 「ClickHouse 目标连接 + 库表」。本类负责任务 CRUD 与校验,以及触发一次后台运行
 * (引擎在 {@link SyncEngine} 里执行，默认不限总时长；配置非零超时后由异步线程中断)。
 * 运行历史存 sync_runs:StartRun 创建 running 记录 → 后台执行 → finishSyncRun 写结果。
 */
public class SyncService {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(SyncService.class);
    @org.springframework.beans.factory.annotation.Autowired
    private SyncExecutionGuard executionGuard;
    @org.springframework.beans.factory.annotation.Autowired
    private SyncScheduleQueue scheduleQueue;
    private com.el2ck.sync.SyncCheckpointStore checkpointStore;

    /** 引擎由本服务显式创建，不能依赖 Spring 自动注入引擎内部字段。 */
    @org.springframework.beans.factory.annotation.Autowired
    public void setCheckpointStore(com.el2ck.sync.SyncCheckpointStore checkpointStore) {
        this.checkpointStore = checkpointStore;
        engine.setCheckpointStore(checkpointStore);
    }
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.el2ck.service.datasource.SourceObservationService sourceObservations;
    @org.springframework.beans.factory.annotation.Autowired
    private com.el2ck.service.auth.SyncAccessService access;
    @org.springframework.beans.factory.annotation.Autowired
    private com.el2ck.service.auth.ExecutionIdentityService identities;

    /** JVM 内全部同步任务共享；定时触发先落库排队，只有真正派发才占用执行名额。 */
    private static final java.util.concurrent.Semaphore RUN_SLOTS = new java.util.concurrent.Semaphore(3);
    private final ConcurrentMap<Long, SyncWorker> runningWorkers = new ConcurrentHashMap<>();
    // 只保护登记/启动与关闭快照，不能在此监视器内执行数据库或远程服务调用。
    private final Object workerLifecycle = new Object();
    private volatile boolean stopping;
    // 只由单个 fixedDelay 派发器推进；游标仅影响扫描公平性，不是任务执行或续传断点。
    private long scheduledScanAfterId, scheduledScanThroughId;

    // 元数据仓库(任务 CRUD / 运行历史)
    private final MetadataRepository repository;
    // 同步引擎(执行 full / incremental 同步)
    private final SyncEngine engine;
    // 数据源服务(读取源结构字段,用于保存前校验映射)
    private final DataSourceService dataSourceService;

    /**
     * 注入任务仓储、CK 连接池、DuckDB、数据源服务和身份/生命周期依赖，并创建同步引擎。
     *
     * @param repository 任务和运行历史仓储
     * @param pool CK 客户端池
     * @param duck 本地转换组件
     * @param secret 数据源配置解密密钥
     * @param dataSourceService 源字段读取服务
     * @param identities CK 执行身份服务
     * @param lifecycle 目标表生命周期服务
     * @param appConfig 应用配置
     */
    public SyncService(MetadataRepository repository, CkPool pool, Duck duck, String secret,
                       DataSourceService dataSourceService,
                       com.el2ck.service.ck.CkIdentityService identities,
                       com.el2ck.service.ck.TableLifecycleService lifecycle,
                       com.el2ck.config.AppConfig appConfig) {
        // 注入元数据仓库
        this.repository = repository;
        // 构造同步引擎实例
        this.engine = new SyncEngine(repository, pool, duck, secret, identities, lifecycle, appConfig);
        this.dataSourceService = dataSourceService;
    }

    /** 合法目标标识符(表/列):字母或下划线开头,仅字母数字下划线。 */
    private static final Pattern IDENT_RE = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    /** 源对象(表名)校验:允许 db.table / schema.table 形式的点分标识符。 */
    private static final Pattern OBJ_RE = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*$");

    // =====================================================================
    // 对外 DTO / 列表
    // =====================================================================

    /** 任务实体 → DTO(回填数据源/目标连接名等展示字段)。 */
    private SyncTaskDTO toDTO(SyncTask t) {
        DataSource source = repository.getDataSource(t.getDataSourceId());
        return toDTO(t, source == null ? "" : source.getName(), source == null ? "" : source.getType(),
                nameOfConnection(t.getConnectionId()), checkpointStore == null ? null : checkpointStore.read(t.getId()));
    }

    /** 展示转换只消费已读快照；列表调用不得在此逐条触发 SQL。 */
    private SyncTaskDTO toDTO(SyncTask t, String sourceName, String sourceType, String connectionName,
                             com.el2ck.sync.SyncCheckpointStore.State state) {
        // 构造 DTO,先回填任务的基础字段
        SyncTaskDTO dto = new SyncTaskDTO();
        String adoptionReason=SyncEngine.baselineAdoptionBlockedReason(t,sourceType,state);
        dto.setCanAdoptBaseline(adoptionReason==null);dto.setBaselineAdoptionBlockedReason(adoptionReason);
        dto.setId(t.getId());
        dto.setTimeoutMinutes(t.getTimeoutMinutes());
        dto.setResumable(true);
        dto.setStableKey(t.getStableKey());
        dto.setBatchSize(t.getBatchSize());
        if (state != null && !state.complete) {
            dto.setCheckpointRows(state.rows); dto.setCheckpointKey(SyncCheckpointStore.positionLabel(state,state.last));
            dto.setCheckpointPending(true);
        }
        dto.setName(t.getName());
        dto.setDescription(t.getDescription());
        dto.setDataSourceId(t.getDataSourceId());
        // 数据源名称和类型来自同一条记录，避免列表显示空类型标签。
        dto.setDataSourceName(def(sourceName));
        dto.setDataSourceType(def(sourceType));
        dto.setSourceObject(t.getSourceObject());
        dto.setMysqlDateTables(t.getMysqlDateTables());
        dto.setDateTableBaselineReady(SyncEngine.dateTableBaselineReady(state));
        dto.setRemotePath(t.getRemotePath());
        dto.setFilePattern(t.getFilePattern());
        dto.setHasHeader(t.isHasHeader());
        dto.setIgnoreExtraCsvColumns(DataSource.SFTP.equals(sourceType) && t.isIgnoreExtraCsvColumns());
        dto.setSchema(t.getSchema());
        dto.setCsvEncoding(t.getCsvEncoding());
        dto.setAllowedTargetDefaults(t.getAllowedTargetDefaults());
        dto.setConnectionId(t.getConnectionId());
        // 目标连接名称按 ID 反查,可能已删除则回填空串
        dto.setConnectionName(def(connectionName));
        dto.setTargetDb(t.getTargetDb());
        dto.setTargetTable(t.getTargetTable());
        dto.setMapping(t.getMapping());
        dto.setMode(t.getMode());
        dto.setScheduleType(t.getScheduleType());
        dto.setCronExpression(t.getCronExpression());
        dto.setIncrColumn(t.getIncrColumn());
        dto.setOrderKey(t.getOrderKey());
        dto.setOds(t.isOds());
        dto.setPartitionExpr(t.getPartitionExpr());
        dto.setSortingKeys(t.getSortingKeys());
        dto.setPrimaryKeys(t.getPrimaryKeys());
        dto.setPhoneColumn(t.getPhoneColumn());
        dto.setEnabled(t.isEnabled());
        dto.setTruncate(t.isTruncate());
        dto.setAutoCreateTable(t.isAutoCreateTable());
        dto.setCreatedAt(t.getCreatedAt());
        dto.setUpdatedAt(t.getUpdatedAt());
        return dto;
    }

    /** 表名规则预览只连接当前用户拥有的 MySQL 源，返回目录与参考结构，不扫描业务数据。 */
    public com.el2ck.service.datasource.MysqlDateTables.Preview previewMysqlDateTables(SyncTaskInput in) {
        if (in == null || in.getDataSourceId() == null || in.getMysqlDateTables() == null)
            throw ApiException.validation("请选择 MySQL 数据源并填写日期分表规则");
        DataSource source = repository.getDataSource(in.getDataSourceId());
        if (source == null || !DataSource.MYSQL.equals(source.getType())) throw ApiException.validation("日期分表仅支持 MySQL 数据源");
        access.source(source, 0);
        try { return com.el2ck.service.datasource.MysqlDateTables.preview(dataSourceService.decryptConfig(source), in.getMysqlDateTables(), in.getSourceObject()); }
        catch (RuntimeException e) { throw ApiException.validation("预览日期分表失败: " + e.getMessage()); }
    }

    /** 同步任务列表。 */
    public List<SyncTaskDTO> list() {
        return listDTOs(repository.listSyncTasks());
    }

    /** 兼容旧列表接口，页面使用独立分页接口；非法页码规整，宽页限制为 100 条。 */
    public com.el2ck.entity.sync.SyncTaskPage page(int page, int size, String keyword) {
        String query = def(keyword).trim().toLowerCase(java.util.Locale.ROOT);
        if (query.length() > 200) throw ApiException.validation("搜索内容不能超过 200 个字符");
        List<String> words = query.isEmpty() ? java.util.Collections.emptyList()
                : java.util.Arrays.asList(query.split("\\s+"));
        if (words.size() > 20) throw ApiException.validation("搜索词不能超过 20 个");
        int limit = Math.max(1, Math.min(100, size));
        long total = repository.countSyncTaskPage(words);
        int actualPage = (int)Math.min(Math.max(1, page), Math.max(1L, (total + limit - 1) / limit));
        List<SyncTask> tasks = total == 0 ? java.util.Collections.emptyList()
                : repository.pageSyncTasks(words, ((long)actualPage - 1) * limit, limit);
        // 两次读取之间可能有删除，最多再修正一次页码，避免删除最后一条后落在空白末页。
        if (tasks.isEmpty() && actualPage > 1) {
            total = repository.countSyncTaskPage(words);
            actualPage = (int)Math.min(actualPage, Math.max(1L, (total + limit - 1) / limit));
            tasks = repository.pageSyncTasks(words, ((long)actualPage - 1) * limit, limit);
        }
        com.el2ck.entity.sync.SyncTaskPage result = new com.el2ck.entity.sync.SyncTaskPage();
        result.setRows(listDTOs(tasks)); result.setTotal(total); result.setPage(actualPage); result.setSize(limit);
        return result;
    }

    /** 日志抽屉可跨页保留任务；重新执行前按 ID 读取最新配置与运行态，仍校验所有权。 */
    public SyncTaskDTO detail(long id) {
        List<SyncTaskDTO> rows = listDTOs(java.util.Collections.singletonList(requireTask(id)));
        if (rows.isEmpty()) throw ApiException.notFound("记录不存在");
        return rows.get(0);
    }

    private List<SyncTaskDTO> listDTOs(List<SyncTask> tasks) {
        List<SyncTaskDTO> out = new ArrayList<>();
        if (tasks.isEmpty()) return out;
        List<Long> ids = new ArrayList<>();
        for (SyncTask task : tasks) ids.add(task.getId());
        Map<Long, SyncTaskListDetails> details = new HashMap<>();
        for (SyncTaskListDetails detail : repository.listSyncTaskDetails(ids)) details.put(detail.getTaskId(), detail);
        // 一次性取出各任务的最近一次运行并建索引,避免在下面循环里逐条查库(N+1)
        Map<Long, SyncRun> latest = new HashMap<>();
        for (SyncRun run : repository.listLatestSyncRuns(ids)) {
            normalizeRunMessage(run);
            latest.put(run.getTaskId(), run);
        }
        // 三次批量查询均在 SQL 中限定当前用户；转换阶段不再读取连接、源或断点。
        for (SyncTask t : tasks) {
            SyncTaskListDetails detail = details.get(t.getId());
            if (detail == null) continue; // 并发删除/转移所有权后不再展示旧快照。
            SyncTaskDTO dto = toDTO(t, detail.getDataSourceName(), detail.getDataSourceType(), detail.getConnectionName(),
                    com.el2ck.sync.SyncCheckpointStore.decodeState(detail.getCheckpointJson()));
            SyncRun run = latest.get(t.getId());
            dto.setQueued(detail.isQueued());
            if(detail.isQueued() || (run!=null && "running".equals(run.getStatus()))) {
                dto.setCanAdoptBaseline(false);
                dto.setBaselineAdoptionBlockedReason(detail.isQueued() ? "任务已排队，请先取消排队后再接管" : "任务正在运行，请结束后再接管");
            }
            if (run != null) {
                dto.setLastRun(runSummary(run));
            }
            out.add(dto);
        }
        return out;
    }

    /** 周期发现只接受当前页 ID，SQL 再校验所有权，不读取配置和断点。 */
    public List<com.el2ck.entity.sync.SyncTaskRuntime> runtime(List<Long> taskIds) {
        if (taskIds == null || taskIds.size() > 100 || taskIds.stream().anyMatch(id -> id == null || id <= 0))
            throw ApiException.validation("状态查询需提供最多 100 个有效任务 ID");
        List<com.el2ck.entity.sync.SyncTaskRuntime> out = new ArrayList<>();
        if (taskIds.isEmpty()) return out;
        List<Long> ids = new ArrayList<>(new LinkedHashSet<>(taskIds));
        Map<Long, SyncRun> latest = new HashMap<>();
        for (SyncRun run : repository.listLatestSyncRuns(ids)) latest.put(run.getTaskId(), normalizeRunMessage(run));
        for (SyncTaskListDetails detail : repository.listSyncTaskRuntime(ids)) {
            com.el2ck.entity.sync.SyncTaskRuntime row = new com.el2ck.entity.sync.SyncTaskRuntime();
            row.setId(detail.getTaskId()); row.setQueued(detail.isQueued());
            if (latest.containsKey(row.getId())) row.setLastRun(runSummary(latest.get(row.getId())));
            out.add(row);
        }
        return out;
    }

    private static SyncTaskDTO.LastRun runSummary(SyncRun run) {
        SyncTaskDTO.LastRun summary = new SyncTaskDTO.LastRun();
        summary.setId(run.getId()); summary.setStatus(run.getStatus());
        summary.setStartedAt(run.getStartedAt()); summary.setFinishedAt(run.getFinishedAt());
        summary.setRowsRead(run.getRowsRead()); summary.setRowsWritten(run.getRowsWritten());
        summary.setError(run.getError()); summary.setProgress(run.getProgress()); summary.setNote(run.getNote());
        summary.setResumed(run.isResumed()); summary.setCheckpointFrom(run.getCheckpointFrom());
        summary.setCheckpointRows(run.getCheckpointRows());
        return summary;
    }

    // =====================================================================
    // CRUD
    // =====================================================================

    /** 校验同步任务入参:名称/数据源类型与源表/目标连接库表/字段映射/策略与 Cron。 */
    private void validate(SyncTaskInput in) {
        // 请求对象为空直接拒绝
        if (in == null) {
            throw ApiException.validation("请求内容不能为空");
        }
        // null 只留给未重新保存的历史任务；所有新增/编辑请求都生成明确的补值策略。
        List<String> allowedDefaults = in.getAllowedTargetDefaults();
        if (allowedDefaults == null) allowedDefaults = Collections.emptyList();
        Set<String> normalizedDefaults = new LinkedHashSet<>();
        for (String name : allowedDefaults) {
            if (name == null || name.trim().isEmpty())
                throw ApiException.validation("允许补默认值的目标列名不能为空");
            normalizedDefaults.add(name.trim());
        }
        in.setAllowedTargetDefaults(new ArrayList<>(normalizedDefaults));
        try { if (in.getCsvEncoding() != null) in.setCsvEncoding(com.el2ck.util.CsvEncoding.normalize(in.getCsvEncoding())); }
        catch (IllegalArgumentException e) { throw ApiException.validation(e.getMessage()); }
        if (in.getTimeoutMinutes() != null && (in.getTimeoutMinutes() < 0 || in.getTimeoutMinutes() > 1440))
            throw ApiException.validation("超时需为 0–1440 分钟，0 表示不限制总时长");
        if (in.getBatchSize() != null && (in.getBatchSize() < 100 || in.getBatchSize() > 10000))
            throw ApiException.validation("每批行数需为 100–10000");
        if (!def(in.getStableKey()).trim().isEmpty() && !IDENT_RE.matcher(in.getStableKey().trim()).matches())
            throw ApiException.validation("分段键名称不合法");
        if (in.getName() != null) {
            // 任务名称先去除首尾空白
            in.setName(in.getName().trim());
        }
        // 任务名称必填
        if (in.getName() == null || in.getName().isEmpty()) {
            throw ApiException.validation("任务名称不能为空");
        }
        // 必须选择数据源
        if (in.getDataSourceId() == null || in.getDataSourceId() <= 0) {
            throw ApiException.validation("请选择数据源");
        }
        // 按 ID 取数据源实体
        DataSource ds = repository.getDataSource(in.getDataSourceId());
        // 数据源必须存在,否则拒绝
        if (ds == null) {
            throw ApiException.validation("数据源不存在");
        }
        in.setCsvEncoding(DataSource.SFTP.equals(ds.getType()) ? com.el2ck.util.CsvEncoding.normalize(in.getCsvEncoding()) : null);
        if (in.getMysqlDateTables() != null) {
            if (!DataSource.MYSQL.equals(ds.getType())) throw ApiException.validation("按日期分表仅支持 MySQL 数据源");
            try { in.getMysqlDateTables().validate(); }
            catch (IllegalArgumentException e) { throw ApiException.validation(e.getMessage()); }
        }
        // 默认值只适用于目录 CSV；其他源忽略此选项，避免通用表单默认值阻止保存。
        if (!DataSource.SFTP.equals(ds.getType())) in.setIgnoreExtraCsvColumns(false);
        // 本地文件源(sourceObject 非必须);其余源走远程源校验
        if (!DataSource.isLocalFile(ds.getType())) {
            // 远程数据库源(clickhouse/mysql/pgsql)可作同步源,需填 sourceObject 源表名。
            // 仅数据库 / SFTP / MongoDB 类数据源可作同步源
            if (!DbSource.isSqlSource(ds.getType()) && !DataSource.CK_TABLE.equals(ds.getType())
                    && !DataSource.SFTP.equals(ds.getType())
                    && !DataSource.MONGODB.equals(ds.getType())
                    && !com.el2ck.service.datasource.BigDataSource.supports(ds.getType())) {
                throw ApiException.validation(String.format("暂不支持 \"%s\" 类型数据源作为同步源", ds.getType()));
            }
            // 取源对象名并去除首尾空白
            String so = def(in.getSourceObject()).trim();
            if (com.el2ck.service.datasource.BigDataSource.supports(ds.getType())) {
                if (so.isEmpty()) throw ApiException.validation("请选择源表或 Topic");
                if (!SyncTask.MODE_FULL.equals(in.getMode()))
                    throw ApiException.validation("Hive、Kafka 当前仅支持全量快照同步");
                in.setIncrColumn("");
            }
            // 数据库源必须填写源表名 sourceObject
            if (in.getMysqlDateTables() == null && DbSource.isSqlSource(ds.getType()) && so.isEmpty()) {
                throw ApiException.validation("数据库源需填写源表名(sourceObject)");
            }
            // 源表名须符合点分标识符规范(如 db.table / schema.table)
            if (in.getMysqlDateTables() == null && DbSource.isSqlSource(ds.getType()) && !OBJ_RE.matcher(so).matches()) {
                throw ApiException.validation("源表名仅允许字母数字下划线,可带点分段(如 db.table)");
            }
        }
        if (DataSource.SFTP.equals(ds.getType())) {
            // 目录任务按文件变化同步，不使用行水位。
            in.setMode(SyncTask.MODE_FULL);
            in.setIncrColumn("");
            in.setRemotePath(def(in.getRemotePath()).trim());
            if (in.getRemotePath().isEmpty()) {
                throw ApiException.validation("请选择远端目录(数据源仅承载连接,目录在任务中配置)");
            }
            String pattern = def(in.getFilePattern()).trim();
            in.setFilePattern(pattern.isEmpty() ? "*.csv" : pattern);
        }
        // 必须指定目标连接
        if (in.getConnectionId() == null || in.getConnectionId() <= 0) {
            throw ApiException.validation("请选择目标连接");
        }
        // 目标连接必须存在
        if (repository.getConnection(in.getConnectionId()) == null) {
            throw ApiException.validation("目标连接不存在");
        }
        access.source(ds,in.getConnectionId());
        access.target(in.getConnectionId(),def(in.getTargetDb()).trim(),def(in.getTargetTable()).trim(),!Boolean.FALSE.equals(in.getAutoCreateTable()));
        List<Column> sourceColumns = validateSourceColumns(ds, in);
        // 目标库名先去空白
        if (in.getTargetDb() != null) {
            in.setTargetDb(in.getTargetDb().trim());
        }
        // 目标表名先去空白
        if (in.getTargetTable() != null) {
            in.setTargetTable(in.getTargetTable().trim());
        }
        // 目标库与目标表均必填
        if (in.getTargetDb() == null || in.getTargetDb().isEmpty()
                || in.getTargetTable() == null || in.getTargetTable().isEmpty()) {
            throw ApiException.validation("目标数据库与表名不能为空");
        }
        // 目标库/表名必须为合法标识符(不以数字开头)
        if (!IDENT_RE.matcher(in.getTargetDb()).matches() || !IDENT_RE.matcher(in.getTargetTable()).matches()) {
            throw ApiException.validation("目标数据库/表名仅允许字母数字下划线,且不以数字开头");
        }
        // 取字段映射列表
        List<SyncFieldMapping> mapping = in.getMapping();
        if (mapping == null) {
            mapping = new ArrayList<>();
            in.setMapping(mapping);
        }
        // 归一化后仍需拒绝空列表，任务必须至少写入一个源字段。
        if (mapping.isEmpty()) {
            throw ApiException.validation("至少配置一个字段映射");
        }
        // 记录已使用的目标列,用于重名检测
        Set<String> seen = new LinkedHashSet<>();
        Set<String> seenSource = new LinkedHashSet<>();
        // 逐条校验字段映射
        for (SyncFieldMapping m : mapping) {
            // 空映射条目拒绝
            if (m == null) {
                throw ApiException.validation("字段映射不能为空");
            }
            // 映射的源列与目标列均不能为空
            if (def(m.getSourceColumn()).trim().isEmpty() || def(m.getTargetColumn()).trim().isEmpty()) {
                throw ApiException.validation("映射的源列与目标列不能为空");
            }
            String sourcePath = m.getSourceColumn().trim();
            if (!seenSource.add(sourcePath)) throw ApiException.validation("源字段重复映射: " + sourcePath);
            if (DataSource.MONGODB.equals(ds.getType())) {
                try { com.el2ck.util.MongoFields.validatePath(sourcePath); }
                catch (IllegalArgumentException e) { throw ApiException.validation(e.getMessage()); }
            }
            // 取目标列名
            String tc = def(m.getTargetColumn()).trim();
            // 目标列名非法则拒绝
            if (!IDENT_RE.matcher(tc).matches()) {
                throw ApiException.validation(String.format("目标列名 \"%s\" 非法", tc));
            }
            if (seen.contains(tc)) {
                throw ApiException.validation(String.format("目标列 \"%s\" 重复", tc));
            }
            // 登记该目标列
            seen.add(tc);
        }
        // 先归一映射类型(大小写及 Bool→UInt8)
        normalizeMappings(mapping);
        try {
            SyncEngine.validateMappingSystemColumns(mapping, Boolean.TRUE.equals(in.getOds()), DataSource.SFTP.equals(ds.getType()));
            SyncMappingTypes.validateSource(mapping, sourceColumns,
                    SyncTask.MODE_INCREMENTAL.equals(in.getMode()) ? def(in.getIncrColumn()).trim() : null);
        } catch (IllegalArgumentException e) { throw ApiException.validation(e.getMessage()); }
        for (SyncFieldMapping m : mapping) {
            if (Boolean.TRUE.equals(m.getAutoMdnMd5())
                    && def(m.getSourceColumn()).trim().equals(def(in.getStableKey()).trim()))
                throw ApiException.validation("开启手机号转 MD5 的字段不能作为稳定分段键，请留空或选择其他字段");
            try { SyncEngine.defaultSql(m); }
            catch (IllegalArgumentException e) { throw ApiException.validation(e.getMessage()); }
        }
        // 取同步策略
        String mode = def(in.getMode());
        // 策略只能是 full / incremental
        if (!SyncTask.validMode(mode)) {
            throw ApiException.validation("同步策略不合法");
        }
        // 增量任务必须有已映射的数值、日期或时间戳水位列，避免重复追加整张源表。
        if (SyncTask.MODE_INCREMENTAL.equals(mode)) {
            // 增量键去除首尾空白
            String incr = def(in.getIncrColumn()).trim();
            if (incr.isEmpty()) {
                throw ApiException.validation("增量模式必须选择增量键");
            }
            boolean mapped = false;
            for (SyncFieldMapping m : mapping) {
                if (incr.equals(def(m.getSourceColumn()).trim())) {
                    mapped = true;
                    if (!isIncrementalType(m.getType())) {
                        throw ApiException.validation("增量字段必须为数值、日期或时间戳类型: " + incr);
                    }
                    Column sourceColumn = sourceColumns.stream().filter(c -> incr.equals(c.getName()))
                            .findFirst().orElseThrow(() -> ApiException.validation("源增量字段不存在: " + incr));
                    if (!SyncEngine.compatibleIncrementalTypes(sourceColumn.getType(), m.getType()))
                        throw ApiException.validation("源增量列与目标映射类型不兼容: " + sourceColumn.getType() + " -> " + m.getType());
                    break;
                }
            }
            if (!mapped) throw ApiException.validation("增量键必须是已配置映射中的源列: " + incr);
        }
        // 排序键为可选;填写时须为合法标识符
        if (!def(in.getOrderKey()).isEmpty() && !IDENT_RE.matcher(def(in.getOrderKey())).matches()) {
            throw ApiException.validation(String.format("排序键 \"%s\" 非法", def(in.getOrderKey())));
        }
        // 排序键 / 主键(多列)可选,填写时逐个须为合法标识符;主键须为排序键前缀。
        List<String> sortKeys = cleanKeys(in.getSortingKeys());
        List<String> primKeys = cleanKeys(in.getPrimaryKeys());
        for (String k : sortKeys) {
            if (!IDENT_RE.matcher(k).matches()) {
                throw ApiException.validation(String.format("排序键 \"%s\" 非法", k));
            }
        }
        for (String k : primKeys) {
            if (!IDENT_RE.matcher(k).matches()) {
                throw ApiException.validation(String.format("主键 \"%s\" 非法", k));
            }
        }
        // 主键必须是排序键的前缀(MergeTree 约束)。有效排序键取:多列 sortingKeys > 单列 orderKey。
        List<String> effectiveSort = sortKeys.isEmpty()
                ? (def(in.getOrderKey()).isEmpty() ? new ArrayList<>() : java.util.Collections.singletonList(def(in.getOrderKey()).trim()))
                : sortKeys;
        if (!primKeys.isEmpty()) {
            if (primKeys.size() > effectiveSort.size()) {
                throw ApiException.validation("主键必须是排序键的前缀");
            }
            for (int i = 0; i < primKeys.size(); i++) {
                if (!primKeys.get(i).equals(effectiveSort.get(i))) {
                    throw ApiException.validation("主键必须是排序键的前缀");
                }
            }
        }
        // 分区表达式:字符 + 形状 + 语义(不含号码列、须含日期列)。为空表示不分区。
        // 目标列类型表由映射推导;ODS 任务额外带 ods_insert_at(DateTime)。
        String partitionExpr = def(in.getPartitionExpr()).trim();
        if (!partitionExpr.isEmpty()) {
            Map<String, String> types = PartitionRule.partitionTypesOfMapping(mapping, Boolean.TRUE.equals(in.getOds()));
            String err = PartitionRule.validatePartitionExpr(partitionExpr, def(in.getPhoneColumn()).trim(), types);
            if (err != null) {
                throw ApiException.validation(err);
            }
        }
        // 执行方式缺省 manual:兼容未携带该字段的旧请求
        String scheduleType = def(in.getScheduleType()).trim();
        if (scheduleType.isEmpty()) scheduleType = "manual";
        if ("cron".equals(scheduleType)) scheduleType = "scheduled";
        in.setScheduleType(scheduleType);
        if (!"manual".equals(scheduleType) && !"scheduled".equals(scheduleType)) {
            throw ApiException.validation("执行方式仅支持手动执行或定时执行");
        }
        String cron = normalizeCronExpression(in.getCronExpression());
        if ("scheduled".equals(scheduleType) && cron.isEmpty()) {
            throw ApiException.validation("定时执行必须填写 Cron 表达式");
        }
        if ("scheduled".equals(scheduleType)) {
            try {
                CronExpression.parse(cron);
            } catch (IllegalArgumentException e) {
                throw ApiException.validation("Cron 表达式格式无效: " + e.getMessage());
            }
        }
        SyncTask validated = new SyncTask();
        applyInput(validated, in);
        // 保存和运行使用同一主键、水位非空及索引校验；不把配置问题拖到后台任务执行后。
        try { engine.validateResumableSource(validated, ds); }
        catch (RuntimeException e) { throw ApiException.validation("源分段配置校验失败，无法保存同步任务: " + e.getMessage()); }
        try { engine.validateTargetSchema(validated, DataSource.SFTP.equals(ds.getType())); }
        catch (RuntimeException e) { throw ApiException.validation("目标字段校验失败，无法保存同步任务: " + e.getMessage()); }
    }

    /** 保存前校验源结构:确认源唯一字段及普通映射字段确实存在。 */
    private List<Column> validateSourceColumns(DataSource ds, SyncTaskInput in) {
        List<Column> columns;
        try {
            if (in.getMysqlDateTables() != null) {
                com.el2ck.service.datasource.MysqlDateTables.Preview preview = previewMysqlDateTables(in);
                if (preview.getTables().isEmpty()) throw ApiException.validation("当前日期范围尚无匹配表，需先有一张参考表才能配置映射");
                if (in.getSchema() != null && !in.getSchema().isEmpty() && !in.getSchema().equals(preview.getColumns()))
                    throw ApiException.validation("参考表结构已变化，请重新预览并核对字段映射后保存");
                in.setSourceObject(preview.getReferenceTable());
                in.setSchema(preview.getColumns());
            }
            // SFTP 结构由目录检测选出，数据源只保存连接，不能按单文件重新探测。
            columns = DataSource.SFTP.equals(ds.getType()) || in.getMysqlDateTables() != null ? in.getSchema()
                    : DataSource.CK_TABLE.equals(ds.getType())
                    ? engine.describeCkSource(ds.getId(), in.getConnectionId())
                    : dataSourceService.sourceColumns(ds.getId(), in.getSourceObject(),true);
        } catch (ApiException e) {
            // 已带明确提示的业务异常(如目标连接缺失)原样上抛
            throw e;
        } catch (RuntimeException e) {
            // 连接/权限/表不存在等底层错误统一转成可读校验提示,不把栈信息透给前端
            throw ApiException.validation("读取源结构失败，无法保存同步任务: " + e.getMessage());
        }
        Set<String> available = new LinkedHashSet<>();
        if (columns != null) {
            for (Column column : columns) {
                if (column != null && column.getName() != null) {
                    available.add(column.getName());
                }
            }
        }
        if (in.getMapping() != null) {
            for (SyncFieldMapping mapping : in.getMapping()) {
                if (mapping == null) {
                    continue;
                }
                String sourceColumn = def(mapping.getSourceColumn()).trim();
                if (!available.contains(sourceColumn)) {
                    throw ApiException.validation("映射源字段不存在: " + sourceColumn);
                }
            }
        }
        return columns == null ? new ArrayList<>() : columns;
    }

    /** 新增同步任务。 */
    public synchronized SyncTaskDTO create(SyncTaskInput in) {
        // 先校验入参
        validate(in);
        applyFileColumnComments(in);
        checkTargetOwner(0, in.getConnectionId(), in.getTargetDb(), in.getTargetTable());
        // 构造同步任务实体
        SyncTask t = new SyncTask();
        // 把入参应用到实体
        applyInput(t, in);
        // 落库并取回自增主键
        long id = access.persist(t,()->repository.createSyncTask(t));
        t.setId(id);
        return toDTO(t);
    }

    /** 新任务默认保留文件的原始表头；旧任务更新不补写，避免改变有效续传配置。 */
    private void applyFileColumnComments(SyncTaskInput in) {
        if (in.getMapping() == null || in.getMapping().stream().noneMatch(m -> def(m.getComment()).trim().isEmpty())) return;
        DataSource source = repository.getDataSource(in.getDataSourceId());
        Map<String, String> comments;
        if (source != null && DataSource.isLocalFile(source.getType())) {
            comments = dataSourceService.fileColumnComments(source.getId());
        } else if (source != null && DataSource.SFTP.equals(source.getType()) && !Boolean.FALSE.equals(in.getHasHeader())) {
            comments = new java.util.LinkedHashMap<>();
            if (in.getSchema() != null) for (Column column : in.getSchema()) {
                if (column != null && column.getName() != null) comments.put(column.getName(), column.getName());
            }
        } else return;
        for (SyncFieldMapping mapping : in.getMapping()) {
            if (!def(mapping.getComment()).trim().isEmpty()) continue;
            String comment = comments.get(mapping.getSourceColumn());
            if (comment == null || comment.trim().isEmpty()) continue;
            // 号码识别依赖“手机号”标记，保留原表头的同时维持既有识别规则。
            if (mapping.getSourceColumn().equals(in.getPhoneColumn()) && !comment.contains("手机号")) comment += "（手机号）";
            mapping.setComment(comment);
        }
    }

    /** 从数据源预览结果自动创建同步任务并立即启动，供数据源页一键入库使用。 */
    public java.util.Map<String, Object> quickSync(long dataSourceId, QuickSyncInput quick) {
        // 一键同步参数为空则拒绝
        if (quick == null) {
            throw ApiException.validation("一键同步参数不能为空");
        }
        // 按数据源 ID 取数据源实体
        DataSource source = repository.getDataSource(dataSourceId);
        // 数据源必须存在
        if (source == null) {
            throw ApiException.notFound("数据源不存在");
        }
        // 依据数据源预览信息自动拼装同步任务入参
        SyncTaskInput input = new SyncTaskInput();
        // 任务名以“一键同步-数据源名”命名,便于识别来源
        input.setName("一键同步-" + source.getName());
        input.setDescription("由数据源预览自动创建");
        input.setDataSourceId(dataSourceId);
        input.setConnectionId(quick.getConnectionId());
        input.setTargetDb(quick.getTargetDb());
        input.setTargetTable(quick.getTargetTable());
        input.setMapping(quick.getMapping());
        // 一键入库沿用页面选择的同步策略;未选择时默认全量
        String mode = def(quick.getMode()).trim();
        input.setMode(mode.isEmpty() ? SyncTask.MODE_FULL : mode);
        input.setIncrColumn(def(quick.getIncrColumn()).trim());
        // 创建后立即启用
        input.setEnabled(true);
        // 截断开关缺省为 true(先清空目标再写入)
        input.setTruncate(quick.getTruncate() == null || quick.getTruncate());
        // 自动建表开关缺省为 true
        input.setAutoCreateTable(quick.getAutoCreateTable() == null || quick.getAutoCreateTable());
        // 复用 create 完成校验与落库
        SyncTaskDTO task = create(input);
        // 创建成功随即触发一次后台同步
        long runId = startRun(task.getId());
        // 组装返回给调用方的结果信息
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("taskId", task.getId());
        result.put("runId", runId);
        // 固定返回 running,实际进度由 runId 轮询获取
        result.put("status", "running");
        return result;
    }

    /** 更新同步任务。 */
    public SyncTaskDTO update(long id, SyncTaskInput in) {
        // 锁外只做权限/存在性预检；真正使用的配置必须在与执行共用的锁内重新读取。
        requireTask(id);
        // 源结构与目标字段探测在执行锁外完成，慢外部连接不能占住任务执行锁。
        validate(in);
        try (SyncExecutionGuard.Lease lease = executionGuard.reserve(id)) {
            SyncTask old = requireTask(id);
            checkTargetOwner(id, in.getConnectionId(), in.getTargetDb(), in.getTargetTable());
            SyncTask candidate = new SyncTask();
            org.springframework.beans.BeanUtils.copyProperties(old, candidate);
            applyInput(candidate, in);
            final SyncEngine.DateTableModeChange transition;
            try { transition = engine.validateDateTableModeChange(old, candidate); }
            catch (IllegalArgumentException e) { throw ApiException.validation(e.getMessage()); }
            applyInput(old, in);
            lease.check();
            // persist 的事务提交后才返回，因此释放执行锁时新配置已经提交。
            access.persist(old,()->{
                if (transition == null) repository.updateSyncTask(old);
                else repository.updateSyncTaskWithDateBaseline(old, transition.previousFingerprint,
                        transition.previousToken, transition.nextFingerprint);
                // 与任务更新同事务提交，防止刚按新配置入队的请求被迟到的取消误清除。
                scheduleQueue.cancel(id,"任务配置已修改，已取消旧排队请求；后续按新配置调度");
                return null;
            });
            return toDTO(old);
        }
    }

    /** 启停只修改一个字段，保留完整业务配置。 */
    public synchronized SyncTaskDTO setEnabled(long id, boolean enabled) {
        requireTask(id);
        try (SyncExecutionGuard.Lease lease = executionGuard.reserve(id)) {
            SyncTask task = requireTask(id);
            lease.check();
            repository.setSyncEnabled(id, enabled);
            if(!enabled) scheduleQueue.cancel(id,"任务已停用，已取消排队");
            task.setEnabled(enabled);
            return toDTO(task);
        }
    }

    /** 接管预览与确认和执行/编辑共用任务锁，避免定时器或其他实例并发修改断点。 */
    public com.el2ck.entity.sync.SyncBaselinePreview adoptBaseline(long id,String confirmationToken,boolean confirm) {
        long userId=com.el2ck.config.AuthContext.userId();
        requireTask(id);
        try(SyncExecutionGuard.Lease lease=executionGuard.reserve(id)) {
            SyncTask task=requireTask(id);
            access.source(repository.getDataSource(task.getDataSourceId()),task.getConnectionId());
            access.target(task.getConnectionId(),task.getTargetDb(),task.getTargetTable(),false);
            checkTargetOwner(id,task.getConnectionId(),task.getTargetDb(),task.getTargetTable());
            if(repository.hasRunningSyncRun(id)) throw ApiException.validation("任务正在运行，请结束后再接管");
            if(scheduleQueue.hasQueued(id)) throw ApiException.validation("任务已排队，请先取消排队后再接管");
            lease.check();
            return engine.adoptBaseline(task,userId,confirmationToken,confirm,lease::check);
        }
    }

    /** 删除同步任务。 */
    public synchronized void delete(long id) {
        requireTask(id);
        try (SyncExecutionGuard.Lease lease = executionGuard.reserve(id)) {
            requireTask(id);
            lease.check();
            repository.deleteSyncTask(id);
        }
    }

    private SyncTask requireTask(long id) {
        SyncTask task = repository.getSyncTask(id);
        if (task == null) throw ApiException.notFound("记录不存在");
        return task;
    }

    /** 把入参应用到任务实体(含手动/定时任务 enabled 的语义归一)。 */
    private void applyInput(SyncTask t, SyncTaskInput in) {
        t.setMysqlDateTables(in.getMysqlDateTables());
        t.setTimeoutMinutes(in.getTimeoutMinutes() == null ? 0 : in.getTimeoutMinutes());
        t.setResumable(true);
        t.setStableKey(def(in.getStableKey()).trim());
        t.setBatchSize(in.getBatchSize() == null ? 10000 : in.getBatchSize());
        // 把入参逐字段拷贝到任务实体
        t.setName(in.getName());
        t.setDescription(def(in.getDescription()));
        t.setDataSourceId(in.getDataSourceId());
        t.setSourceObject(def(in.getSourceObject()));
        t.setRemotePath(def(in.getRemotePath()).trim());
        String pattern = def(in.getFilePattern()).trim();
        t.setFilePattern(pattern.isEmpty() ? "*.csv" : pattern);
        // hasHeader 缺省 true:仅当显式传 false 才视为无表头(兼容旧请求未携带该字段)
        t.setHasHeader(!Boolean.FALSE.equals(in.getHasHeader()));
        if (in.getIgnoreExtraCsvColumns() != null)
            t.setIgnoreExtraCsvColumns(Boolean.TRUE.equals(in.getIgnoreExtraCsvColumns()));
        t.setSchema(in.getSchema() == null ? new ArrayList<>() : new ArrayList<>(in.getSchema()));
        t.setCsvEncoding(in.getCsvEncoding());
        t.setAllowedTargetDefaults(in.getAllowedTargetDefaults() == null ? null : new ArrayList<>(in.getAllowedTargetDefaults()));
        t.setConnectionId(in.getConnectionId());
        t.setTargetDb(def(in.getTargetDb()));
        t.setTargetTable(def(in.getTargetTable()));
        t.setMapping(in.getMapping());
        t.setMode(def(in.getMode()));
        t.setScheduleType(def(in.getScheduleType()).trim().isEmpty() ? "manual" : in.getScheduleType().trim());
        t.setCronExpression(normalizeCronExpression(in.getCronExpression()));
        t.setIncrColumn(def(in.getIncrColumn()));
        t.setOrderKey(def(in.getOrderKey()));
        // ODS 标准源表标记 + 目标自动建表的表级配置(分区/排序键/主键/号码列)
        t.setOds(Boolean.TRUE.equals(in.getOds()));
        t.setPartitionExpr(def(in.getPartitionExpr()).trim());
        t.setSortingKeys(cleanKeys(in.getSortingKeys()));
        t.setPrimaryKeys(cleanKeys(in.getPrimaryKeys()));
        t.setPhoneColumn(def(in.getPhoneColumn()).trim());
        // 手动与定时任务共用启停语义，未传时默认启用。
        t.setEnabled(!Boolean.FALSE.equals(in.getEnabled()));
        t.setTruncate(in.getTruncate() == null || in.getTruncate()); // 默认 true。
        // autoCreateTable 缺省为 true(首次同步自动建表)
        t.setAutoCreateTable(in.getAutoCreateTable() == null || in.getAutoCreateTable());
    }

    /** 清洗排序键/主键列表:去空白、去空项、保持顺序(null 视作空列表)。 */
    private static List<String> cleanKeys(List<String> keys) {
        List<String> out = new ArrayList<>();
        if (keys == null) {
            return out;
        }
        for (String k : keys) {
            String v = def(k).trim();
            if (!v.isEmpty()) {
                out.add(v);
            }
        }
        return out;
    }

    /** 在保存任务前统一映射类型，避免驱动/ClickHouse 对 Bool 类型的不兼容。 */
    private static void normalizeMappings(List<SyncFieldMapping> mappings) {
        // 逐条归一字段映射
        for (SyncFieldMapping mapping : mappings) {
            // 源列 / 目标列统一去除首尾空白
            mapping.setSourceColumn(def(mapping.getSourceColumn()).trim());
            mapping.setTargetColumn(def(mapping.getTargetColumn()).trim());
            // 读取映射类型并去除首尾空白
            String type = def(mapping.getType()).trim();
            // 未填类型或 String 大小写变体:统一规范为 String
            if (type.isEmpty() || "String".equalsIgnoreCase(type)) {
                mapping.setType("String");
            // Bool / Boolean 统一改写为 UInt8,规避驱动与 ClickHouse 布尔类型差异
            } else if ("Bool".equalsIgnoreCase(type) || "Boolean".equalsIgnoreCase(type)) {
                mapping.setType("UInt8");
            // 其余类型原样保留
            } else {
                mapping.setType(type);
            }
            if (mapping.getNullable() == null) {
                mapping.setNullable(Boolean.TRUE);
            }
        }
    }

    /** 接受常见的五段 Cron，并补齐调度器使用的秒字段。 */
    public static String normalizeCronExpression(String value) {
        String cron = def(value).trim();
        // "@" 开头是 Spring 内置预设(如 @daily),本身已含调度语义,不补秒位
        if (cron.isEmpty() || cron.startsWith("@")) {
            return cron;
        }
        if (cron.split("\\s+").length == 5) {
            return "0 " + cron;
        }
        return cron;
    }

    // =====================================================================
    // 运行
    // =====================================================================

    /** 启动一次同步(异步执行,返回 runId 供轮询历史)。 */
    public long startRun(long taskId) {
        return startRun(taskId, false);
    }

    /** 默认从断点继续；force=true 创建新轮次并全量重建，不删除任务定义或运行历史。 */
    public long startRun(long taskId, boolean force) {
        return startRun(taskId, force, null);
    }

    /** expectedCron 仅供应用内定时器使用；配置复核与执行快照必须由同一任务锁保护。 */
    private synchronized long startRun(long taskId, boolean force, String expectedCron) {
        return startRun(taskId,force,expectedCron,null);
    }

    /** queuedRunId 只来自服务端持久队列，继续使用原运行记录，不再额外创建一条 running。 */
    private synchronized long startRun(long taskId, boolean force, String expectedCron,Long queuedRunId) {
        com.el2ck.config.AuthContext.userId();
        requireAcceptingRuns();
        requireTask(taskId);
        final long runId;
        final SyncTask snapshot;
        final SyncExecutionGuard.Lease lease = queuedRunId==null ? executionGuard.reserve(taskId)
                : executionGuard.tryReserveForRun(taskId);
        if(lease==null) throw new StartDeferredException();
        try {
            // 其他实例可能在本请求取锁前修改/删除任务；锁外对象不能作为执行快照。
            SyncTask task = requireTask(taskId);
            if (!task.isEnabled()) {
                throw ApiException.validation("任务未启用,请先启用");
            }
            if(queuedRunId==null && scheduleQueue.hasQueued(taskId))
                throw ApiException.validation("任务已有定时排队请求，将自动执行；如需手动运行或重新传输，请先取消排队");
            // 定时器最多每 30 秒重排一次。期间改为手动或修改 Cron，旧回调必须在建运行记录前退出。
            if (expectedCron != null && (!"scheduled".equals(task.getScheduleType())
                    || !expectedCron.equals(normalizeCronExpression(task.getCronExpression())))) {
                throw new ApiException(409, "任务调度配置已变更，已跳过旧定时触发");
            }
            access.source(repository.getDataSource(task.getDataSourceId()), task.getConnectionId());
            // 删除后的目标允许按配置重建；已有物理表和其他用户资产仍须校验归属。
            access.target(task.getConnectionId(), task.getTargetDb(), task.getTargetTable(), task.isAutoCreateTable());
            checkTargetOwner(taskId, task.getConnectionId(), task.getTargetDb(), task.getTargetTable());
            // 与新建任务及列表的默认续传语义一致，兼容历史库中 resumable=0 的任务。
            // 仅规范本次执行快照，普通执行保留断点；只有独立 restart 入口传入 force=true。
            task.setResumable(true);
            snapshot = task;
            lease.check();
            // 正常路径 reserve 已恢复旧 running；此处仅防御未遵守锁契约的旧实例，互斥仍靠会话锁。
            if (repository.hasRunningSyncRun(taskId)) {
                throw ApiException.validation("任务正在运行中,请等待完成");
            }
            requireAcceptingRuns();
            if (!RUN_SLOTS.tryAcquire()) {
                if(queuedRunId!=null) throw new StartDeferredException();
                throw ApiException.validation("同步任务并发已达上限(3)，请等待其他任务结束后重试");
            }
            // 构造运行记录实体
            SyncRun run = new SyncRun();
            // 绑定所属同步任务 ID
            run.setTaskId(taskId);
            // 先落一条 running 记录,取回 runId 供轮询
            try {
                if(queuedRunId!=null) {
                    if(!scheduleQueue.claim(queuedRunId,taskId)) throw new StartDeferredException();
                    runId=queuedRunId;
                } else runId = repository.createSyncRun(run);
            }
            catch (RuntimeException | Error e) { RUN_SLOTS.release(); throw e; }
        } catch (RuntimeException | Error e) { lease.close(); throw e; }
        SyncWorker worker = null;
        try {
            worker = new SyncWorker("sync-run-" + runId,
                    control -> runWorker(runId, snapshot, force, lease, control));
            synchronized (workerLifecycle) {
                // 关闭可发生在元数据预检/运行记录写入期间；登记和 start 必须与关闭快照互斥。
                requireAcceptingRuns();
                runningWorkers.put(taskId, worker);
                worker.start();
            }
        } catch (RuntimeException | Error e) {
            SyncRun failed = new SyncRun(); failed.setId(runId); failed.setTaskId(taskId); failed.setStatus("failed");
            failed.setError(stopping ? "服务正在关闭，未启动同步" : "启动同步线程失败");
            try { repository.finishSyncRun(failed); }
            catch (RuntimeException cleanup) { e.addSuppressed(cleanup); }
            finally {
                // 保持任务锁直到启动失败记录已尝试收尾，避免与下一次运行的恢复交错。
                try { lease.close(); }
                finally {
                    RUN_SLOTS.release();
                    if (worker != null) runningWorkers.remove(taskId, worker);
                }
            }
            throw e;
        }
        return runId;
    }

    /**
     * 校验目标表没有被其他同步任务占用。
     *
     * @param id 当前任务 ID
     * @param connectionId 目标连接 ID
     * @param db 目标数据库
     * @param table 目标表
     */
    private void checkTargetOwner(long id, long connectionId, String db, String table) {
        access.uniqueTarget(id,connectionId,db,table);
    }

    /** XXL-JOB 按任务 ID 调用，触发时间由调度中心管理；始终使用持久化所有者身份。 */
    public long startScheduledRun(long taskId) {
        return identities.asOwner("sync-task",taskId,()->startRun(taskId));
    }

    /** 应用内 Cron 回调携带排程时的表达式，取执行锁后与最新配置比较，拒绝过期触发。 */
    long startScheduledRun(long taskId, String expectedCron) {
        String cron = normalizeCronExpression(expectedCron);
        if (cron.isEmpty()) throw ApiException.validation("定时触发缺少 Cron 配置");
        return identities.asOwner("sync-task", taskId, () -> startRun(taskId, false, cron));
    }

    /** 到点先落库，不受本机执行名额或当前任务长时间运行影响。 */
    void enqueueScheduledRun(long taskId,String expectedCron,long scheduledAtMs) {
        String cron=normalizeCronExpression(expectedCron);
        if(cron.isEmpty()) throw ApiException.validation("定时触发缺少 Cron 配置");
        scheduleQueue.enqueue(taskId,cron,scheduledAtMs);
    }

    /** 多实例重复扫描也只会在任务锁内条件认领同一记录；满载和同任务忙碌均保留排队。 */
    void dispatchScheduledQueue() {
        if(stopping || RUN_SLOTS.availablePermits()==0) return;
        if(scheduledScanThroughId==0) {
            scheduledScanThroughId=scheduleQueue.lastQueuedId();
            if(scheduledScanThroughId==0) return;
        }
        List<SyncScheduleQueue.Entry> pending=scheduleQueue.pending(scheduledScanAfterId,scheduledScanThroughId);
        if(pending.isEmpty()) {
            scheduledScanAfterId=0; scheduledScanThroughId=0;
            return;
        }
        for(SyncScheduleQueue.Entry entry:pending) {
            if(stopping || RUN_SLOTS.availablePermits()==0) break;
            // 无论本条启动、忙碌或失败，都推进扫描；容量不足而尚未尝试的请求留在游标之后。
            scheduledScanAfterId=entry.runId;
            try {
                if(entry.cron==null || normalizeCronExpression(entry.cron).isEmpty())
                    throw ApiException.validation("排队记录缺少调度配置，请取消后重新配置任务");
                identities.asOwner("sync-task",entry.taskId,()->startRun(entry.taskId,false,entry.cron,entry.runId));
            } catch(StartDeferredException busy) {
                // 任务仍在运行或名额被同时到来的手动请求占用，下轮继续。
            } catch(RuntimeException e) {
                if(stopping) return;
                // 瞬时元数据库/远端故障保留 queued；明确的权限、配置错误才终结请求。
                if(e instanceof ApiException && ((ApiException)e).getStatus()<500)
                    scheduleQueue.fail(entry.runId,"排队任务未能启动: "+msg(e));
                LOG.warn("event=sync.queued_start_failed task_id={} run_id={} reason={}",entry.taskId,entry.runId,msg(e));
            }
        }
        if(scheduledScanAfterId>=scheduledScanThroughId) {
            scheduledScanAfterId=0; scheduledScanThroughId=0;
        }
    }

    private static final class StartDeferredException extends RuntimeException { }

    public boolean cancelQueued(long taskId) {
        requireTask(taskId);
        return scheduleQueue.cancel(taskId,"用户已取消排队");
    }

    /** Cancel a run started by this service instance. */
    public boolean cancel(long taskId) {
        requireTask(taskId);
        SyncWorker worker = runningWorkers.get(taskId);
        // 收尾线程仍保留登记以供关闭等待，但已不再接受中断请求。
        return worker != null && worker.requestStop("已取消");
    }

    private void requireAcceptingRuns() {
        if (stopping) throw new ApiException(503, "服务正在关闭，请稍后重试同步");
    }

    /** 先禁止新工作线程，再通知执行中的任务退出；收尾和执行锁释放仍由工作线程负责。 */
    @javax.annotation.PreDestroy
    public void stopWorkers() {
        final List<SyncWorker> workers;
        synchronized (workerLifecycle) {
            stopping = true;
            workers = new ArrayList<>(runningWorkers.values());
        }
        workers.forEach(worker -> worker.requestStop("服务正在关闭，同步已中断"));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        for (SyncWorker worker : workers) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) break;
            try { worker.thread().join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining))); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
    }

    /** Local-time next fire times, matching Go's cron-next response format. */
    public List<String> cronNext(long taskId, int requestedCount) {
        SyncTask task = repository.getSyncTask(taskId);
        if (task == null) throw ApiException.notFound("记录不存在");
        List<String> next = new ArrayList<>();
        // 非定时任务或未填 Cron:返回空列表,由前端提示“未配置定时”
        if (!"scheduled".equals(task.getScheduleType())
                || def(task.getCronExpression()).trim().isEmpty()) return next;
        // 条数缺省 5、上限 20,避免一次渲染过多时刻
        int count = requestedCount <= 0 ? 5 : Math.min(requestedCount, 20);
        try {
            CronExpression expression = CronExpression.parse(normalizeCronExpression(task.getCronExpression()));
            // 以当前本地时间(而非 UTC)为起点逐次推进,与 Go 版返回的本地时间口径一致
            LocalDateTime time = LocalDateTime.now();
            DateTimeFormatter format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            for (int i = 0; i < count; i++) {
                time = expression.next(time);
                // 表达式已无后续时刻(如一次性表达式)时提前结束
                if (time == null) break;
                next.add(time.format(format));
            }
            return next;
        } catch (IllegalArgumentException error) {
            throw ApiException.validation("cron 表达式无效: " + error.getMessage());
        }
    }

    /** 后台线程的唯一收尾入口，涵盖身份解析、超时、引擎失败和结果落库。 */
    private void runWorker(long runId, SyncTask task, boolean force, SyncExecutionGuard.Lease lease,
                           SyncWorker worker) {
        ScheduledExecutorService timer = null;
        ScheduledFuture<?> timeout = null;
        SyncRun finish = new SyncRun();
        finish.setId(runId);
        finish.setTaskId(task.getId());
        try {
            worker.checkRunning();
            // 0 不创建定时器。超时回调与手动取消共用门闩，迟到回调不能中断结果写库。
            if (task.getTimeoutMinutes() != 0) {
                timer = createTimeoutTimer(runId);
                int minutes = Math.max(1, task.getTimeoutMinutes());
                timeout = timer.schedule(() -> {
                    worker.requestStop("执行超时（" + minutes + " 分钟），已停止同步");
                }, minutes, TimeUnit.MINUTES);
            }
            identities.asOwner("sync-task", task.getId(), () -> {
                worker.checkRunning();
                execute(runId, task, force, lease, worker, finish);
                return null;
            });
        } catch (RuntimeException | Error error) {
            finish.setStatus("failed");
            finish.setError(msg(error));
        } finally {
            // 先原子拒绝后续取消，再清理定时器；驱动即便消费过中断，取消原因也不会丢失。
            String stoppedBecause = worker.beginFinish();
            if (stoppedBecause != null) {
                finish.setStatus("failed");
                finish.setError(stoppedBecause);
            }
            try {
                if (timeout != null) timeout.cancel(false);
                if (timer != null) timer.shutdownNow();
            } finally {
                try {
                    repository.finishSyncRun(finish);
                } catch (RuntimeException error) {
                    LOG.error("event=sync.finish_failed task_id={} run_id={} intended_status={} compensation=execution_guard",
                            task.getId(), runId, finish.getStatus(), error);
                } finally {
                    try { lease.close(); }
                    finally {
                        RUN_SLOTS.release();
                        // 直到写库和会话锁释放完成才移除，关闭等待不能漏掉仍在释放资源的线程。
                        runningWorkers.remove(task.getId(), worker);
                        Thread.interrupted();
                    }
                }
            }
        }
    }

    /** 每轮仅在配置了总超时时创建；包内工厂使测试能够直接推进超时回调。 */
    ScheduledExecutorService createTimeoutTimer(long runId) {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sync-timeout-" + runId);
            t.setDaemon(true);
            return t;
        });
    }

    /** 业务执行只填结果；取消、超时和异常均由 runWorker 统一归并并收尾。 */
    private void execute(long runId, SyncTask task, boolean force, SyncExecutionGuard.Lease lease,
                         SyncWorker worker, SyncRun finish) {
        if (checkpointStore != null && !force) {
            com.el2ck.sync.SyncCheckpointStore.State prior = checkpointStore.read(task.getId());
            if (prior != null && !prior.complete) {
                finish.setResumed(true); finish.setCheckpointFrom(SyncCheckpointStore.positionLabel(prior,prior.last)); finish.setCheckpointRows(prior.rows);
                finish.setNote("从已确认断点继续同步");
            }
        }
        access.source(repository.getDataSource(task.getDataSourceId()), task.getConnectionId());
        access.target(task.getConnectionId(), task.getTargetDb(), task.getTargetTable(), task.isAutoCreateTable());
        worker.checkRunning();
        DataSource observedSource = repository.getDataSource(task.getDataSourceId());
        com.el2ck.service.datasource.SourceObservationService.Ticket observation = sourceObservations == null
                ? null : sourceObservations.begin(observedSource, "sync");
        SyncEngine.Stats stats = engine.run(task, force, runId, () -> { worker.checkRunning(); lease.check(); });
        if (observation != null && !DataSource.isLocalFile(observedSource.getType())
                && !DataSource.CK_TABLE.equals(observedSource.getType())) sourceObservations.record(observation, true);
        // 同步失败可能来自手机号、字段或目标写入，不能据此标记源连接失败。
        applyStats(finish, stats);
    }

    /** 引擎统计转运行结果：逐文件失败必须在历史中可见。 */
    static void applyStats(SyncRun finish, SyncEngine.Stats stats) {
        boolean failed = stats.getFailed() > 0 || stats.getExcluded() > 0;
        finish.setStatus(failed ? "failed" : "success");
        List<String> notes = new ArrayList<>();
        if (stats.getFailed() + stats.getNewFiles() + stats.getSkipped()
                + stats.getChanged() + stats.getUpdated() + stats.getExcluded() > 0) {
            notes.add("文件：新增 " + stats.getNewFiles() + "，跳过 " + stats.getSkipped()
                    + "，变化 " + stats.getChanged() + "，更新 " + stats.getUpdated()
                    + "，排除 " + stats.getExcluded() + "，失败 " + stats.getFailed());
        }
        notes.addAll(stats.getNotes());
        appendRunNote(finish, String.join("；", notes));
        finish.setError(failed ? (stats.getErrors().isEmpty() ? "存在失败或被排除的文件，请检查源文件" : String.join("；", stats.getErrors())) : null);
        finish.setRowsRead(stats.getRowsRead());
        finish.setRowsWritten(stats.getRowsWritten());
    }

    private static void appendRunNote(SyncRun run, String text) {
        if (text == null || text.trim().isEmpty()) return;
        String existing = def(run.getNote());
        run.setNote(existing.isEmpty() ? text : existing + "；" + text);
    }

    /** 兼容旧版本误写入 error 的成功摘要；只修正已知普通说明，不隐藏失败记录或未知异常。 */
    private static SyncRun normalizeRunMessage(SyncRun run) {
        if (run == null || !"success".equals(run.getStatus())) return run;
        String error = def(run.getError()).trim();
        if (error.startsWith("文件：新增 ") || error.startsWith("已启用忽略额外尾列：")
                || error.startsWith("本轮沿用旧版 CSV 断点读取规则")) {
            appendRunNote(run, error);
            run.setError(null);
        }
        return run;
    }

    private static List<SyncRun> normalizeRunMessages(List<SyncRun> runs) {
        for (SyncRun run : runs) normalizeRunMessage(run);
        return runs;
    }

    /** 某任务运行历史(最近 20 条)。 */
    public List<SyncRun> runs(long taskId) {
        // 任务不存在返回 404
        if (repository.getSyncTask(taskId) == null) {
            throw ApiException.notFound("记录不存在");
        }
        // 直接返回该任务最近 20 条运行历史
        return normalizeRunMessages(repository.listSyncRuns(taskId, 20));
    }

    /** 历史分页先校验任务权限，再约束页码、筛选值及查询规模。 */
    public com.el2ck.entity.sync.SyncRunPage runLogs(long taskId, int page, int size, String status,
                                                   Boolean resumed, int days, String keyword) {
        if (repository.getSyncTask(taskId) == null) throw ApiException.notFound("记录不存在");
        String filter = def(status).trim();
        if (!filter.isEmpty() && !java.util.Arrays.asList("queued","running","success","failed").contains(filter))
            throw ApiException.validation("运行状态不合法");
        if (page < 1 || size < 1 || size > 100 || days < 0 || days > 3650 || def(keyword).length() > 200)
            throw ApiException.validation("日志分页或筛选参数不合法");
        Integer resumeFilter = resumed == null ? null : (resumed ? 1 : 0);
        com.el2ck.entity.sync.SyncRunPage result = new com.el2ck.entity.sync.SyncRunPage();
        result.setPage(page); result.setSize(size);
        result.setTotal(repository.countSyncRuns(taskId,filter,resumeFilter,days,def(keyword).trim()));
        result.setRows(normalizeRunMessages(repository.querySyncRuns(taskId,filter,resumeFilter,days,def(keyword).trim(),(long)(page-1)*size,size)));
        return result;
    }

    /** 精确查询某次运行，防止后续运行挤出最近历史后无法收尾。 */
    public SyncRun runStatus(long taskId, long runId) {
        if (taskId <= 0 || runId <= 0) throw ApiException.validation("ID 不合法");
        if (repository.getSyncTask(taskId) == null) throw ApiException.notFound("记录不存在");
        SyncRun run = repository.getSyncRun(taskId, runId);
        if (run == null || run.getDeletedAt() != null) throw ApiException.notFound("运行记录不存在或已删除");
        return normalizeRunMessage(run);
    }

    /** 仅逻辑删除当前任务所有者可见的单条终态日志。 */
    public int deleteRunLog(long taskId, long runId) {
        if (runId <= 0) throw ApiException.validation("执行日志 ID 不合法");
        requireTask(taskId);
        return repository.softDeleteSyncRunLog(taskId, runId);
    }

    // =====================================================================
    // 工具
    // =====================================================================

    /**
     * 读取连接显示名。
     *
     * @param id 连接 ID
     * @return 连接名；连接不存在时为空串
     */
    private String nameOfConnection(long id) {
        // 按连接 ID 取实体
        Connection c = repository.getConnection(id);
        return c == null ? "" : c.getName();
    }

    /** 目标类型是否为可按数值排序的数字类型(剥掉 nullable() 包裹后判定)。 */
    private static boolean isNumericType(String type) {
        String t = def(type).trim().toLowerCase();
        while (t.startsWith("nullable(") && t.endsWith(")")) {
            t = t.substring(9, t.length() - 1).trim();
        }
        return t.matches("u?int(8|16|32|64|128|256)|float(32|64)|decimal.*|numeric.*");
    }

    /**
     * 判断 CK 字段类型是否支持增量游标比较。
     *
     * @param type CK 字段类型
     * @return 支持增量比较时为 true
     */
    private static boolean isIncrementalType(String type) {
        return SyncEngine.isIncrementalType(type);
    }

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
