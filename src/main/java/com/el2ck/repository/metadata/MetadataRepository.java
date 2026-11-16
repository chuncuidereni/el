package com.el2ck.repository.metadata;

/**
 * 【文件职责】统一访问 MySQL 元数据表，维护数据源、连接、同步任务、运行记录和工作流等核心业务实体。
 */

import com.el2ck.mapper.connection.ConnectionMapper;
import com.el2ck.mapper.datasource.DataSourceMapper;
import com.el2ck.mapper.sync.SyncCheckpointMapper;
import com.el2ck.mapper.sync.SyncFileMapper;
import com.el2ck.mapper.sync.SyncRunMapper;
import com.el2ck.mapper.sync.SyncTaskMapper;
import com.el2ck.mapper.system.AppSettingsMapper;
import com.el2ck.mapper.user.UserRemarkMapper;
import com.el2ck.mapper.workflow.WfRunMapper;
import com.el2ck.mapper.workflow.WorkflowMapper;

import com.el2ck.entity.system.AppSettings;
import com.el2ck.entity.connection.Connection;
import com.el2ck.entity.datasource.DataSource;
import com.el2ck.entity.sync.SyncFieldMapping;
import com.el2ck.entity.sync.SyncRun;
import com.el2ck.entity.sync.SyncTask;
import com.el2ck.entity.workflow.WfRun;
import com.el2ck.entity.workflow.WfRunLog;
import com.el2ck.entity.workflow.WfTask;
import com.el2ck.exception.ResourceNotFoundException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 元数据仓库门面(MyBatis 实现,对应 Go internal/store)。
 *
 * 平台全部持久化实体收敛为九张表:连接 connections、数据源 data_sources、全局默认高级配置
 * app_settings、同步任务/运行历史、工作流/运行/节点日志、用户备注 —— 全部入库。
 * 连接密码与数据源配置以密文落列(连接 password_enc / 数据源整段 config_enc),明文只在服务层;
 * 高级配置列(dial/query 超时等)为 NULL 即「继承全局默认」。
 * mapping_json ↔ List&lt;SyncFieldMapping&gt; 的 JSON 转换在这里完成
 * (实体 {@link SyncTask#mappingJson} 仅作持久层载体)。
 */
@Repository
public class MetadataRepository {
    // 元数据删除与引用检查由应用层组合完成；业务写入必须走事务，避免留下悬空同步任务或工作流引用。
    @org.springframework.beans.factory.annotation.Autowired
    private com.el2ck.service.auth.ResourceAccessService access;

    @org.springframework.beans.factory.annotation.Autowired
    private javax.sql.DataSource metadataDatabase;

    @org.springframework.beans.factory.annotation.Autowired
    private TableChangeGate tableChangeGate;

    /** 独立 ObjectMapper(不随 Spring 环境定制),用于 JSON 列转换。 */
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    // connections 连接表 Mapper
    private final ConnectionMapper conns;
    // data_sources 数据源表 Mapper
    private final DataSourceMapper datasources;
    // app_settings 全局默认高级配置表 Mapper
    private final AppSettingsMapper appSettings;
    // sync_tasks 同步任务表 Mapper
    private final SyncTaskMapper syncTasks;

/** 在所有权校验通过后启用或停用同步任务。 */
    public void setSyncEnabled(long taskId, boolean enabled) { access.requireOwner("sync-task",taskId); syncTasks.setEnabled(taskId, enabled); }
    // sync_runs 同步运行历史表 Mapper
    private final SyncRunMapper syncRuns;
    // wf_tasks 工作流定义表 Mapper
    private final WorkflowMapper workflows;
    // wf_runs + wf_run_logs 工作流运行/节点日志表 Mapper
    private final WfRunMapper wfRuns;
    // user_remarks 用户备注表 Mapper
    private final UserRemarkMapper remarks;
    // sync_files SFTP 目录同步去重表 Mapper
    private final SyncFileMapper syncFiles;
    // sync_checkpoints 同步断点表 Mapper(仅用于任务删除时的级联清理)
    private final SyncCheckpointMapper syncCheckpoints;

    /** 构造器:由 Spring 注入全部表 Mapper(见上方字段)。 */
    public MetadataRepository(ConnectionMapper conns, DataSourceMapper datasources, AppSettingsMapper appSettings,
                 SyncTaskMapper syncTasks, SyncRunMapper syncRuns,
                 WorkflowMapper workflows, WfRunMapper wfRuns,
                 UserRemarkMapper remarks, SyncFileMapper syncFiles,
                 SyncCheckpointMapper syncCheckpoints) {
        // 形参 → 字段逐一赋值,保存各 Mapper 引用
        this.conns = conns;
        this.datasources = datasources;
        this.appSettings = appSettings;
        this.syncTasks = syncTasks;
        this.syncRuns = syncRuns;
        this.workflows = workflows;
        this.wfRuns = wfRuns;
        this.remarks = remarks;
        this.syncFiles = syncFiles;
        this.syncCheckpoints = syncCheckpoints;
    }

    // =====================================================================
    // 同步任务 & 运行历史
    // =====================================================================

    /** 全部同步任务。 */
    public List<SyncTask> listSyncTasks() {
        // 结果容器
        List<SyncTask> out = new ArrayList<>();
        // 所有权限制直接放入 SQL；禁止先查全库再在内存过滤。
        for (SyncTask t : syncTasks.listOwned(com.el2ck.config.AuthContext.userId())) out.add(decodeMapping(t));
        return out;
    }

    public List<com.el2ck.entity.sync.SyncTaskListDetails> listSyncTaskDetails() {
        return listSyncTaskDetails(null);
    }

    public List<com.el2ck.entity.sync.SyncTaskListDetails> listSyncTaskDetails(List<Long> taskIds) {
        return syncTasks.listOwnedDetails(com.el2ck.config.AuthContext.userId(), taskIds);
    }

    public long countSyncTaskPage(List<String> words) {
        return syncTasks.countOwnedPage(com.el2ck.config.AuthContext.userId(), words);
    }

    public List<com.el2ck.entity.sync.SyncTaskListDetails> listSyncTaskRuntime(List<Long> taskIds) {
        return syncTasks.listOwnedRuntime(com.el2ck.config.AuthContext.userId(), taskIds);
    }

    public List<SyncTask> pageSyncTasks(List<String> words, long offset, int limit) {
        List<SyncTask> out = new ArrayList<>();
        for (SyncTask task : syncTasks.pageOwned(com.el2ck.config.AuthContext.userId(), words, offset, limit))
            out.add(decodeMapping(task));
        return out;
    }

    /** 单个同步任务;不存在返回 null。 */
    public SyncTask getSyncTask(long id) {
        access.requireOwner("sync-task",id);
        // 按主键查单条;存在则解码 mapping 后返回
        SyncTask t = syncTasks.getById(id);
        return t == null ? null : decodeMapping(t);
    }

    /** 新增同步任务,返回新主键。 */
    @org.springframework.transaction.annotation.Transactional
    public long createSyncTask(SyncTask t) {
        lockDataSources(java.util.Collections.singleton(t.getDataSourceId()));
        // schema_json(源端列结构)同为 JSON 载体列,入库前先编码
        t.setSchemaJson(SyncTaskSchemaJson.encode(t));
        // 再把字段映射列表编码成 JSON 文本写回实体,之后统一由 Mapper 落库
        t.setMappingJson(encodeMapping(t.getMapping()));
        // 排序键 / 主键同为 JSON 数组载体列
        t.setSortingKeysJson(encodeMapping(t.getSortingKeys()));
        t.setPrimaryKeysJson(encodeMapping(t.getPrimaryKeys()));
        syncTasks.insert(t);
        access.register("sync-task",t.getId());
        // 插入后 MyBatis 把自增主键回填到 t.id,直接返回
        return t.getId();
    }

    /** 更新同步任务;不存在抛 NotFoundException。 */
    @org.springframework.transaction.annotation.Transactional
    public void updateSyncTask(SyncTask t) {
        access.requireOwner("sync-task",t.getId());
        lockDataSources(java.util.Collections.singleton(t.getDataSourceId()));
        // 与新增一致:schema 与 mapping 两个 JSON 载体列都要在写库前编码好
        t.setSchemaJson(SyncTaskSchemaJson.encode(t));
        // 同样先把字段映射编码成 JSON 后再更新
        t.setMappingJson(encodeMapping(t.getMapping()));
        t.setSortingKeysJson(encodeMapping(t.getSortingKeys()));
        t.setPrimaryKeysJson(encodeMapping(t.getPrimaryKeys()));
        // 受影响行数为 0 说明主键记录不存在
        if (syncTasks.update(t) == 0) {
            throw new ResourceNotFoundException("记录不存在");
        }
    }

    /**
     * 全量转增量的配置与断点标识必须同时提交或回滚。
     * 调用方持有任务执行锁；旧轮次令牌、指纹和完成标志再做一次条件校验，防止覆盖变化后的断点。
     */
    @org.springframework.transaction.annotation.Transactional
    public void updateSyncTaskWithDateBaseline(SyncTask task, String previousFingerprint,
                                               String previousToken, String nextFingerprint) {
        updateSyncTask(task);
        if (syncCheckpoints.rebindCompleted(task.getId(), previousFingerprint, previousToken, nextFingerprint) != 1)
            throw new IllegalStateException("原全量断点已变化，任务配置未保存，请刷新后重试");
    }

    /** 删除同步任务;不存在抛 NotFoundException。 */
    @org.springframework.transaction.annotation.Transactional
    public void deleteSyncTask(long id) {
        access.requireOwner("sync-task",id);
        // 先删除任务主记录,0 行即不存在
        if (syncTasks.deleteById(id) == 0) {
            throw new ResourceNotFoundException("记录不存在");
        }
        // 级联清理该任务的历史运行记录
        syncRuns.deleteByTask(id);
        syncFiles.clearByTask(id);
        // 断点只有任务存活期间才有意义;不清理会在 el_sync_checkpoints 留下孤儿行
        syncCheckpoints.deleteByTask(id);
        access.remove("sync-task",id);
    }

    /** 创建运行记录(状态 running),返回新 ID。 */
    public long createSyncRun(SyncRun r) {
        access.requireOwner("sync-task",r.getTaskId());
        try (TableChangeGate.Lease gate = tableChangeGate.acquire()) {
            gate.check();
            // running 记录提交后才释放启动闸门，手动修改会看到该任务已在执行。
            syncRuns.insert(r);
            return r.getId();
        }
    }

    /** 结束运行:写状态/结束时间/读写行数/错误。 */
    public void finishSyncRun(SyncRun r) {
        try { syncRuns.finish(r); }
        catch (RuntimeException failure) {
            try { syncRuns.finishIfRunning(r); }
            catch (RuntimeException retry) { failure.addSuppressed(retry); throw failure; }
        }
    }

    /** 后台工作线程上报本次运行快照；不会修改已经结束的历史。 */
    public void updateSyncRunProgress(SyncRun run) { syncRuns.progress(run); }

    /** 最近 limit 条运行历史(时间倒序),limit 非法时默认 20。 */
    public List<SyncRun> listSyncRuns(long taskId, int limit) {
        access.requireOwner("sync-task",taskId);
        // 规整 limit:非正数或超过 100 时回退默认 20
        if (limit <= 0 || limit > 100) {
            limit = 20;
        }
        // 查该任务最近 limit 条历史
        List<SyncRun> rows = syncRuns.listByTask(taskId, limit);
        // Mapper 可能返回 null,统一转成空列表返回
        return rows == null ? new ArrayList<SyncRun>() : rows;
    }

    /** 查询指定任务的一次运行。 */
    public SyncRun getSyncRun(long taskId, long runId) {
        access.requireOwner("sync-task",taskId);
        return syncRuns.getByTaskAndId(taskId, runId);
    }

    /** 执行日志分页查询(状态/续传/最近天数/关键字过滤)。 */
    public List<SyncRun> querySyncRuns(long taskId, String status, Integer resumed, int days,
                                       String keyword, long offset, int limit) {
        access.requireOwner("sync-task",taskId);
        List<SyncRun> rows = syncRuns.queryPage(taskId, status, resumed, days, keyword, limit, offset);
        return rows == null ? new ArrayList<SyncRun>() : rows;
    }

    /** 与 querySyncRuns 同条件的总数。 */
    public long countSyncRuns(long taskId, String status, Integer resumed, int days, String keyword) {
        access.requireOwner("sync-task",taskId);
        return syncRuns.countPage(taskId, status, resumed, days, keyword);
    }

    /** 日志的逻辑删除与活动状态检查在同一事务完成，重复删除为幂等操作。 */
    @org.springframework.transaction.annotation.Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public int softDeleteSyncRunLog(long taskId, long runId) {
        access.requireOwner("sync-task", taskId);
        MetadataTransactions.requireReadCommitted(metadataDatabase, "删除同步日志");
        SyncRun row = syncRuns.lockRunLog(taskId, runId);
        if (row == null) throw com.el2ck.exception.ApiException.notFound("执行日志不存在或不属于该任务");
        if (!"success".equals(row.getStatus()) && !"failed".equals(row.getStatus())) {
            throw new com.el2ck.exception.ApiException(409, "运行中或排队中的执行日志不能删除，请等待执行结束");
        }
        return syncRuns.softDeleteRunLog(taskId, runId);
    }

    /** 每个任务各自最新一条运行记录(任务列表页用,避免逐条任务再查一次历史)。 */
    public List<SyncRun> listLatestSyncRuns() {
        return listLatestSyncRuns(null);
    }

    public List<SyncRun> listLatestSyncRuns(List<Long> taskIds) {
        return syncRuns.listLatestOwned(com.el2ck.config.AuthContext.userId(), taskIds);
    }

    /** 任务是否正在运行(并发防重入)。 */
    public boolean hasRunningSyncRun(long taskId) {
        access.requireOwner("sync-task",taskId);
        // 统计该任务状态为 running 的记录数,大于 0 即在运行
        return syncRuns.countRunning(taskId) > 0;
    }

    // =====================================================================
    // SFTP 目录同步:文件级去重(sync_files)
    // =====================================================================

    /** 某任务已同步文件记录,按远端路径归键(便于按文件比对 size/mtime)。对应 Go ListSyncedFiles。 */
    public java.util.Map<String, com.el2ck.entity.sync.SyncFile> listSyncedFiles(long taskId) {
        access.requireOwner("sync-task",taskId);
        java.util.Map<String, com.el2ck.entity.sync.SyncFile> out = new java.util.LinkedHashMap<>();
        for (com.el2ck.entity.sync.SyncFile f : syncFiles.listByTask(taskId)) {
            out.put(f.getRemotePath(), f);
        }
        return out;
    }

    /** 记录/更新一个已同步文件。对应 Go UpsertSyncedFile。 */
    public void upsertSyncedFile(long taskId, String remotePath, long size, String mtime) {
        access.requireOwner("sync-task",taskId);
        syncFiles.upsert(taskId, remotePath, size, mtime == null ? "" : mtime);
    }

    /** 清空某任务的去重记录(强制全量重建时用)。对应 Go ClearSyncedFiles。 */
    public void clearSyncedFiles(long taskId) {
        access.requireOwner("sync-task",taskId);
        syncFiles.clearByTask(taskId);
    }

    // =====================================================================
    // 工作流 & 运行记录/节点日志
    // =====================================================================

    /** 全部工作流。 */
    public List<WfTask> listWorkflows() {
        // 查全部工作流;null 统一转成空列表
        List<WfTask> rows=new ArrayList<>();
        for(long id:access.ownedIds("workflow")) {
            WfTask task=workflows.getById(id);
            if(task!=null) rows.add(task);
        }
        rows.sort(java.util.Comparator.comparingLong(WfTask::getId).reversed());
        return rows;
    }

    /** 单个工作流;不存在返回 null。 */
    public WfTask getWorkflow(long id) {
        access.requireOwner("workflow",id);
        // 按主键查单个工作流,无记录返回 null
        return workflows.getById(id);
    }

    /** 新增工作流,返回新主键。 */
    @org.springframework.transaction.annotation.Transactional
    public long createWorkflow(WfTask w) {
        lockWorkflowSources(w.getNodesJson());
        // 插入工作流定义,自增主键回填到 w.id
        workflows.insert(w);
        access.register("workflow",w.getId());
        return w.getId();
    }

    /** 更新工作流;不存在抛 NotFoundException。 */
    @org.springframework.transaction.annotation.Transactional
    public void updateWorkflow(WfTask w) {
        access.requireOwner("workflow",w.getId());
        lockWorkflowSources(w.getNodesJson());
        // 按主键更新;0 行即记录不存在
        if (workflows.update(w) == 0) {
            throw new ResourceNotFoundException("记录不存在");
        }
    }

    /** 删除工作流;不存在抛 NotFoundException。 */
    @org.springframework.transaction.annotation.Transactional
    public void deleteWorkflow(long id) {
        access.requireOwner("workflow",id);
        // 先删工作流主记录,0 行即不存在
        if (workflows.deleteById(id) == 0) {
            throw new ResourceNotFoundException("记录不存在");
        }
        // 级联清理其全部节点日志(先子表后主表)
        wfRuns.deleteLogsByWorkflow(id);
        // 再级联清理其全部运行记录
        wfRuns.deleteByWorkflow(id);
        access.remove("workflow",id);
    }

    /** 创建运行记录(状态 running),返回新 ID。 */
    public long createWfRun(WfRun r) {
        access.requireOwner("workflow",r.getWorkflowId());
        try (TableChangeGate.Lease gate = tableChangeGate.acquire()) {
            gate.check();
            // 插入运行记录,自增主键回填到 r.id。
            wfRuns.insert(r);
            return r.getId();
        }
    }

    /** 结束运行:写状态/结束时间/错误。 */
    public void finishWfRun(WfRun r) {
        // 把运行记录更新为结束态(状态/结束时间/错误落库)
        wfRuns.finish(r);
    }

    /** 最近 limit 条运行记录。 */
    public List<WfRun> listWfRuns(long workflowId, int limit) {
        access.requireOwner("workflow",workflowId);
        // 规整 limit:非正数或超过 100 时回退默认 20
        if (limit <= 0 || limit > 100) {
            limit = 20;
        }
        // 查该工作流最近 limit 条运行记录
        List<WfRun> rows = wfRuns.listByWorkflow(workflowId, limit);
        // null 统一转成空列表返回
        return rows == null ? new ArrayList<WfRun>() : rows;
    }

    /** 工作流最近一次运行；从未运行时返回 null。 */
    public WfRun latestWfRun(long workflowId) {
        access.requireOwner("workflow",workflowId);
        // 取该工作流最近一条运行记录(id DESC LIMIT 1)
        return wfRuns.latestByWorkflow(workflowId);
    }

    /** 某次运行的节点日志。 */
    public List<WfRunLog> listWfRunLogs(long runId) {
        access.requireWorkflowRun(runId);
        // 查该次运行的全部节点日志(按执行顺序);null 转空列表
        List<WfRunLog> rows = wfRuns.listLogs(runId);
        return rows == null ? new ArrayList<WfRunLog>() : rows;
    }

    /** 创建节点日志(状态 running),返回新 ID。 */
    public long createWfRunLog(WfRunLog l) {
        // 插入节点日志,自增主键回填到 l.id
        wfRuns.insertLog(l);
        return l.getId();
    }

    /** 结束节点日志:写状态/读写行数/耗时/错误。 */
    public void finishWfRunLog(WfRunLog l) {
        // 把节点日志更新为结束态(状态/读写行数/耗时/错误落库)
        wfRuns.finishLog(l);
    }

    /** 某工作流是否有正在运行的记录(并发防重入)。 */
    public boolean hasRunningWfRun(long workflowId) {
        access.requireOwner("workflow",workflowId);
        // 统计该工作流状态为 running 的记录数,大于 0 即在运行
        return wfRuns.countRunning(workflowId) > 0;
    }

    // =====================================================================
    // 连接(connections)
    // =====================================================================

    /** 全部连接:默认连接优先。 */
    public List<Connection> listConnections() {
        // 查全部连接(Mapper 内按 is_default DESC 排序);null 转空列表
        List<Connection> rows = conns.list();
        return rows == null ? new ArrayList<Connection>() : rows;
    }

    /** 单个连接;不存在返回 null。 */
    public Connection getConnection(long id) {
        // 按主键查单个连接
        return conns.getById(id);
    }

    /** 新增连接,返回新主键。 */
    public long createConnection(Connection c) {
        // 插入连接,自增主键回填到 c.id
        conns.insert(c);
        return c.getId();
    }

    /** 更新连接;返回是否命中(不存在返回 false,由调用方决定语义)。 */
    public boolean updateConnection(Connection c) {
        // 按主键更新,受影响行数 > 0 表示命中
        return conns.update(c) > 0;
    }

    /** 将某连接设为默认(同一条 UPDATE 原子清除其余默认位);调用前调用方需确认 id 存在。 */
    public void setDefaultConnection(long id) {
        // 由 Mapper 一条 UPDATE 完成:目标 id 置 1,其余全部置 0
        conns.setDefault(id);
    }

    /** 删除连接(先清理其用户备注)并返回是否命中;不存在抛 NotFoundException。 */
    public void deleteConnection(long id) {
        if (getConnection(id) == null) throw new ResourceNotFoundException("记录不存在");
        List<String> references = new ArrayList<>();
        for (SyncTask task : syncTasks.list()) {
            if (task.getConnectionId() == id) references.add("同步任务 #" + task.getId() + "「" + task.getName() + "」");
        }
        references.addAll(conns.references(id));
        collectWorkflowReferences(references, "connectionId", id);
        for (Map<String, Object> row : conns.configuredReferences()) {
            collectJsonReference(references, String.valueOf(row.get("label")),
                    String.valueOf(row.get("payload")), "connectionId", id);
        }
        requireUnreferenced("连接", references);
        // 先清理该连接下的全部用户备注(表间无外键,手动级联)
        remarks.deleteByConn(id);
        // 再删连接主记录,0 行即不存在
        if (conns.deleteById(id) == 0) {
            throw new ResourceNotFoundException("记录不存在");
        }
    }

    // =====================================================================
    // 数据源(data_sources)
    // =====================================================================

    /** 全部数据源。 */
    public List<DataSource> listDataSources() {
        // 查全部数据源;null 转空列表
        List<DataSource> rows = new ArrayList<>();
        for (Long id : access.ownedIds("datasource")) {
            DataSource d = datasources.getById(id);
            if (d != null) rows.add(d);
        }
        return rows == null ? new ArrayList<DataSource>() : rows;
    }

    /** 单个数据源;不存在返回 null。 */
    public DataSource getDataSource(long id) {
        access.requireOwner("datasource", id);
        // 按主键查单个数据源
        return datasources.getById(id);
    }

    /** 按唯一标识取数据源(查重用);不存在返回 null。 */
    public DataSource getDataSourceByCode(String code) {
        // 按唯一业务编码 code 查询
        return datasources.getByCode(code);
    }

    /** 校验数据源分组是否存在(写入 group_id 前的存在性检查;表结构未声明外键,只能显式查)。 */
    public boolean dataSourceGroupExists(long id) {
        access.requireOwner("datasource-group", id);
        // ds_groups 中能数到该 id 即存在
        return datasources.countGroup(id) > 0;
    }

    /** 新增数据源,返回新主键。 */
    @org.springframework.transaction.annotation.Transactional
    public long createDataSource(DataSource d) {
        com.el2ck.config.AuthContext.userId();
        // 插入数据源,自增主键回填到 d.id
        datasources.insert(d);
        access.register("datasource", d.getId());
        return d.getId();
    }


    public java.util.Map<String, Object> dataSourceOwner(long id) {
        return access.ownerInfo("datasource", id);
    }

    /** 更新数据源;不存在抛 NotFoundException。 */
    @org.springframework.transaction.annotation.Transactional
    public void updateDataSource(DataSource d) {
        access.requireOwner("datasource", d.getId());
        DataSource previous = datasources.getById(d.getId());
        if (previous != null && (!java.util.Objects.equals(previous.getType(), d.getType())
                || !java.util.Objects.equals(previous.getConfigEnc(), d.getConfigEnc()))) {
            access.retireSourceAssets(d.getId());
        }
        // 按主键更新,0 行即不存在
        if (datasources.update(d) == 0) {
            throw new ResourceNotFoundException("记录不存在");
        }
    }

    /**
     * 删除数据源前先检查同步任务和所有工作流 JSON 中的 dataSourceId 引用；存在引用时要求先修改/删除引用配置。
     * 通过检查后再退休资产、移除 ACL 并按主键删除。记录不存在时抛出 ResourceNotFoundException。
     * 和引用写入共用源行锁；READ_COMMITTED 下锁后的普通查询读取最新提交数据，
     * 不对同步任务/工作流做全表加锁。源锁阻止新的引用写入，直到删除提交。
     */
    @org.springframework.transaction.annotation.Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void deleteDataSource(long id) {
        MetadataTransactions.requireReadCommitted(metadataDatabase, "数据源删除");
        lockDataSources(java.util.Collections.singleton(id));
        List<String> references = new ArrayList<>();
        if (syncTasks.findSourceReference(id) != null) {
            throw com.el2ck.exception.ApiException.validation(
                    "请删除映射同步表的任务后再进行此操作");
        }
        for (WfTask task : workflows.listReferences()) {
            collectJsonReference(references, "关联工作流 #" + task.getId(), task.getNodesJson(), "dataSourceId", id);
        }
        requireUnreferenced("数据源", references);
        access.retireSourceAssets(id);
        access.remove("datasource", id);
        // 按主键删除,0 行即不存在
        if (datasources.deleteById(id) == 0) {
            throw new ResourceNotFoundException("记录不存在");
        }
    }

    private void collectWorkflowReferences(List<String> references, String key, long id) {
        for (WfTask task : workflows.list()) {
            collectJsonReference(references, "关联工作流",
                    task.getNodesJson(), key, id);
        }
    }

    /** 数据源及其所有权必须在同一事务中保持到引用写入/删除提交；多源按 ID 升序锁定。 */
    private void lockDataSources(java.util.Collection<Long> ids) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("数据源引用变更必须在事务内执行");
        long ownerId = com.el2ck.config.AuthContext.userId();
        for (Long id : ids) {
            if (id == null || id <= 0) throw com.el2ck.exception.ApiException.validation("数据源 ID 必须为正整数");
        }
        for (Long id : new java.util.TreeSet<>(ids)) {
            if (datasources.lockOwnedById(id, ownerId) == null)
                throw com.el2ck.exception.ApiException.notFound("数据源不存在或无权访问");
        }
    }

    /** 与工作流校验共用 ID 解析规则；只锁实际数据源节点，模板等节点的残留字段不参与加锁。 */
    private void lockWorkflowSources(String payload) {
        java.util.Set<Long> ids = new java.util.TreeSet<>();
        try { collectDataSourceIds(JSON.readTree(payload == null ? "[]" : payload), ids); }
        catch (java.io.IOException e) {
            throw com.el2ck.exception.ApiException.validation("工作流数据源引用不合法");
        }
        lockDataSources(ids);
    }

    private static void collectDataSourceIds(com.fasterxml.jackson.databind.JsonNode node, java.util.Set<Long> ids) {
        if (node == null) return;
        if (node.isObject() && com.el2ck.entity.workflow.WfNode.TYPE_DATASOURCE.equals(node.path("type").asText())) {
            long id = com.el2ck.util.ReferenceId.parse(node.path("config").path("dataSourceId"));
            if (id <= 0) throw com.el2ck.exception.ApiException.validation("数据源节点未选择有效数据源，dataSourceId 必须为正整数");
            ids.add(id);
        }
        for (com.fasterxml.jackson.databind.JsonNode child : node) collectDataSourceIds(child, ids);
    }

    private static void collectJsonReference(List<String> references, String label, String payload, String key, long id) {
        try {
            com.fasterxml.jackson.databind.JsonNode root = JSON.readTree(payload == null ? "[]" : payload);
            if (containsReference(root, key, id)) references.add(label);
        } catch (java.io.IOException e) {
            throw com.el2ck.exception.ApiException.validation("无法检查" + label + "的引用，配置损坏，请先修复配置");
        }
    }

    private static boolean containsReference(com.fasterxml.jackson.databind.JsonNode node, String key, long id) {
        if (node == null) return false;
        if (node.isObject() && node.has(key)) {
            // 未选连接、空值和坏 ID 不指向任何资源，不能阻断无关资源的删除。
            if (com.el2ck.util.ReferenceId.parse(node.get(key)) == id) return true;
        }
        for (com.fasterxml.jackson.databind.JsonNode child : node) {
            if (containsReference(child, key, id)) return true;
        }
        return false;
    }

    private static void requireUnreferenced(String kind, List<String> references) {
        if (!references.isEmpty()) {
            throw com.el2ck.exception.ApiException.validation("无法删除" + kind + "，仍被以下配置引用："
                    + String.join("；", references) + "。请先修改或删除引用配置");
        }
    }

    // =====================================================================
    // 全局默认高级配置(app_settings)
    // =====================================================================

    /** 读取全局默认高级配置:缺失的键回填内置默认,永不返回 null。 */
    public AppSettings getAppSettings() {
        // 先以代码内置默认值构造一个基准配置对象
        AppSettings a = new AppSettings();
        a.setDialTimeoutMs(AppSettings.DEFAULT_DIAL_TIMEOUT_MS);
        a.setQueryTimeoutMs(AppSettings.DEFAULT_QUERY_TIMEOUT_MS);
        a.setConnMaxLifetimeMin(AppSettings.DEFAULT_CONN_MAX_LIFETIME_MIN);
        // 默认非只读
        a.setReadOnly(false);
        a.setParamsJson("{}");
        // 读出 app_settings 全表键值对
        List<Map<String, Object>> rows = appSettings.listAll();
        if (rows != null) {
            // 逐行把「键」归位到对应实体字段
            for (Map<String, Object> row : rows) {
                // 取出键名(null 容错为空串)
                String k = str(row.get("key"));
                // 取出键值(null 容错为空串)
                String v = str(row.get("value"));
                if ("dial_timeout_ms".equals(k)) {
                    // dial 超时:解析失败回退内置默认
                    a.setDialTimeoutMs(parseLong(v, AppSettings.DEFAULT_DIAL_TIMEOUT_MS));
                } else if ("query_timeout_ms".equals(k)) {
                    // query 超时:解析失败回退内置默认
                    a.setQueryTimeoutMs(parseLong(v, AppSettings.DEFAULT_QUERY_TIMEOUT_MS));
                } else if ("conn_max_lifetime_min".equals(k)) {
                    // 连接最大存活分钟:解析失败回退内置默认
                    a.setConnMaxLifetimeMin(parseLong(v, AppSettings.DEFAULT_CONN_MAX_LIFETIME_MIN));
                } else if ("read_only".equals(k)) {
                    // 只读开关:值 "1" 视为 true
                    a.setReadOnly("1".equals(v));
                } else if ("params_json".equals(k) && !v.isEmpty()) {
                    // 附加参数 JSON:仅非空才覆盖
                    a.setParamsJson(v);
                }
            }
        }
        return a;
    }

    /** 保存全局默认高级配置(全部 5 个键 UPSERT)。 */
    public void setAppSettings(AppSettings a) {
        // dial 超时键:null 回退默认后写回
        appSettings.upsert("dial_timeout_ms",
                String.valueOf(derefLong(a.getDialTimeoutMs(), AppSettings.DEFAULT_DIAL_TIMEOUT_MS)));
        // query 超时键:null 回退默认后写回
        appSettings.upsert("query_timeout_ms",
                String.valueOf(derefLong(a.getQueryTimeoutMs(), AppSettings.DEFAULT_QUERY_TIMEOUT_MS)));
        // 连接最大存活分钟键:null 回退默认后写回
        appSettings.upsert("conn_max_lifetime_min",
                String.valueOf(derefLong(a.getConnMaxLifetimeMin(), AppSettings.DEFAULT_CONN_MAX_LIFETIME_MIN)));
        // 只读开关键:Boolean → "1"/"0"
        appSettings.upsert("read_only", Boolean.TRUE.equals(a.getReadOnly()) ? "1" : "0");
        // 附加参数 JSON 键:空值写默认 "{}"
        String params = a.getParamsJson();
        appSettings.upsert("params_json", (params == null || params.isEmpty()) ? "{}" : params);
    }

    // =====================================================================
    // 用户备注
    // =====================================================================

    /** 某连接下全部 用户名 → 备注。 */
    public Map<String, String> getUserRemarks(long connId) {
        // 保持插入顺序的结果容器
        Map<String, String> out = new LinkedHashMap<>();
        // 查出该连接下全部备注行
        List<Map<String, Object>> rows = remarks.listByConn(connId);
        if (rows != null) {
            // 逐行把 用户名 → 备注 收进 Map
            for (Map<String, Object> row : rows) {
                out.put(str(row.get("user_name")), str(row.get("remark")));
            }
        }
        return out;
    }

    /** 写入单个用户备注(按 (连接, 用户名) 覆盖;remark 为空写空串)。 */
    public void setUserRemark(long connId, String name, String remark) {
        // UPSERT 该用户的备注;null 备注归一为空串(列 NOT NULL)
        remarks.upsert(connId, name, remark == null ? "" : remark);
    }

    /** 删除单个用户备注。 */
    public void deleteUserRemark(long connId, String name) {
        // 按 (连接, 用户名) 删除备注
        remarks.delete(connId, name);
    }

    // =====================================================================
    // 工具
    // =====================================================================

    /**
     * 读取任务后:把两个 JSON 载体列反解回对象 —— schema_json → 源表列结构及 CSV 策略,mapping_json → 字段映射列表。
     * 两者容错口径不同:mapping_json 坏数据兜底为空列表(旧数据/手工改库不该阻断读取),
     * schema_json 是同步语义的一部分,解析失败直接抛 IllegalStateException,避免带着错结构继续跑任务。
     */
    private static SyncTask decodeMapping(SyncTask t) {
        SyncTaskSchemaJson.decode(t);
        // 取出持久层存下的 mapping_json 原文
        String json = t.getMappingJson();
        if (json == null || json.trim().isEmpty()) {
            // 空文本视作无映射,置为空列表
            t.setMapping(new ArrayList<SyncFieldMapping>());
        } else {
            try {
                // 正常情况:JSON 文本反解为字段映射列表
                t.setMapping(JSON.readValue(json, new TypeReference<List<SyncFieldMapping>>() {
                }));
            } catch (Exception e) {
                // 反序列化失败时兜底为空列表,不让坏数据中断读取
                t.setMapping(new ArrayList<SyncFieldMapping>());
            }
        }
        // 排序键 / 主键:JSON 数组文本 → List<String>;空或坏数据兜底为空列表
        t.setSortingKeys(decodeStringList(t.getSortingKeysJson()));
        t.setPrimaryKeys(decodeStringList(t.getPrimaryKeysJson()));
        return t;
    }

    /** JSON 数组文本 → List&lt;String&gt;;空/空白/坏数据一律兜底为空列表(与 mapping 的容错口径一致)。 */
    private static List<String> decodeStringList(String json) {
        if (json == null || json.trim().isEmpty()) {
            return new ArrayList<>();
        }
        try {
            List<String> v = JSON.readValue(json, new TypeReference<List<String>>() {});
            return v == null ? new ArrayList<>() : v;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /**
     * 保存前:把列表编码为 JSON 数组文本,供 mapping_json 及排序键等数组列使用
     * (故形参放宽为 List&lt;?&gt;);null 编码为空数组 "[]",保证列上的 NOT NULL 约束不会被违反。
     */
    private static String encodeMapping(List<?> mapping) {
        // null 与空列表都编码为 "[]",读取端无需区分
        if (mapping == null) {
            return "[]";
        }
        try {
            return JSON.writeValueAsString(mapping);
        } catch (Exception e) {
            // 理论上不会失败;失败按致命错误处理并带上原因
            throw new IllegalStateException("JSON 序列化失败: " + e.getMessage(), e);
        }
    }

    /** 取值辅助:null 统一转为空串,其余调用 toString。 */
    private static String str(Object v) {
        // 三元容错 null
        return v == null ? "" : v.toString();
    }

    /** 长整型解析:解析失败或空 → 回退默认。 */
    private static Long parseLong(String s, long def) {
        // 空值直接返回默认
        if (s == null || s.isEmpty()) {
            return def;
        }
        try {
            // 去掉首尾空白后解析为 long
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            // 内容非数字时回退默认
            return def;
        }
    }

    /** 可空长整型回退默认。 */
    private static long derefLong(Long v, long def) {
        // null 回退默认,非 null 原样返回
        return v == null ? def : v;
    }
}
