/**
 * 【文件职责】本文件定义认证与资源授权模块的资源访问控制服务 SyncAccessService。负责资源归属、可见范围和授权校验，并在需要时协调 ClickHouse 权限同步。
 */

package com.el2ck.service.auth;

import com.el2ck.config.AuthContext;
import com.el2ck.entity.datasource.DataSource;
import com.el2ck.entity.sync.SyncTask;
import com.el2ck.exception.ApiException;
import com.el2ck.service.ck.CkInstanceService;
import com.el2ck.service.connection.ConnectionService;
import com.el2ck.service.datasource.DataSourceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

/** Authorizes structured sync inputs/outputs before any source introspection or target query. */
@Service
public class SyncAccessService {
    private final ResourceAccessService access;
    private final ConnectionService connections;
    private final DataSourceService sources;
    private final JdbcTemplate jdbc;
    private final javax.sql.DataSource database;
    /** 生产环境注入实例登记;单元测试可缺省,按 backend_id 回退。 */
    @Autowired(required = false)
    private CkInstanceService instances;
    @Autowired(required = false)
    private com.el2ck.service.ck.CkIdentityService identities;

    /**
     * 注入同步源、目标访问校验及平台元数据访问依赖。
     *
     * @param access 资源所有权与表级授权服务
     * @param connections ClickHouse 连接服务
     * @param sources 数据源配置和执行文件授权服务
     * @param ds 平台元数据库
     */
    public SyncAccessService(ResourceAccessService access,ConnectionService connections,DataSourceService sources,javax.sql.DataSource ds) {
        this.access=access;this.connections=connections;this.sources=sources;this.jdbc=new JdbcTemplate(ds);
        this.database=ds;
    }

    /**
     * 校验同步源存在、归当前用户所有且允许执行其文件或表。
     *
     * @param source 待读取的数据源
     * @param connectionId 源 CK 表使用的连接 ID
     * @throws com.el2ck.exception.ApiException 数据源不存在或当前用户无执行权限时抛出
     */
    public void source(DataSource source,long connectionId) {
        if(source==null)throw ApiException.notFound("数据源不存在");
        access.requireOwner("datasource",source.getId());
        sources.authorizeExecutionFile(source);
        if(DataSource.CK_TABLE.equals(source.getType())) {
            Map<String,Object> cfg=sources.decryptConfig(source);
            access.table(connectionId,String.valueOf(cfg.get("database")),String.valueOf(cfg.get("table")),true);
        }
    }

    /**
     * 校验同步目标数据库、表名和当前用户的建表/写入边界。
     *
     * <p>已登记资产必须通过表级权限检查；物理表已存在但尚未登记时，即使允许新建也拒绝，
     * 防止普通用户抢占管理员尚未分配的存量表。
     *
     * @param connectionId 目标 ClickHouse 连接 ID
     * @param db 目标数据库
     * @param table 目标表
     * @param allowNew 是否允许为该任务登记尚不存在的目标表名
     */
    public void target(long connectionId,String db,String table,boolean allowNew) {
        if(db==null || table==null || !db.matches("[A-Za-z_][A-Za-z0-9_]*") || !table.matches("[A-Za-z_][A-Za-z0-9_]*"))
            throw ApiException.validation("目标数据库或表名非法");
        if(instances!=null) instances.requireAllowedDatabase(db);
        List<Long> ids=assetIds(connectionId,db,table);
        if(!ids.isEmpty()) { access.table(connectionId,db,table,true); return; }
        if(!allowNew) throw ApiException.notFound("目标表尚未分配给当前用户");
        // A pre-existing physical table can only be assigned through the administrator's migration page.
        if(physicalExists(connectionId,db,table))
            throw new ApiException(403,"已有目标表尚未分配归属，请联系管理员");
    }

    /**
     * 校验并登记同步任务目标表。
     *
     * <p>自动建表目标先登记为 provisioning，物理表创建成功后由同步引擎转为 active；
     * 非自动建表目标登记为 active。
     *
     * @param task 已解析目标连接、库和表的同步任务
     */
    public void registerTarget(SyncTask task) {
        target(task.getConnectionId(),task.getTargetDb(),task.getTargetTable(),task.isAutoCreateTable());
        if(assetIds(task.getConnectionId(),task.getTargetDb(),task.getTargetTable()).isEmpty()) {
            // 自动建表的物理对象尚不存在:先占用表名并保持 provisioning,建表成功后由同步引擎转 active。
            String status=task.isAutoCreateTable() ? "provisioning" : "active";
            access.registerTable("clickhouse",task.getConnectionId(),task.getTargetDb(),task.getTargetTable(),
                    AuthContext.userId(),status,"sync",task.getId());
        }
    }

    /**
     * 在事务中完成目标校验、资产加锁、任务唯一性检查后执行保存动作。
     *
     * <p>同一物理实例的连接别名锁定同一目标资产。锁后检查使用 READ_COMMITTED，
     * 不复用锁等待前的事务快照；事务一直持有资产行锁到任务保存提交。
     *
     * @param task 待保存的同步任务
     * @param save 实际持久化回调
     * @param <T> 保存结果类型
     * @return 回调返回的保存结果
     */
    @org.springframework.transaction.annotation.Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public <T> T persist(SyncTask task,java.util.function.Supplier<T> save) {
        com.el2ck.repository.metadata.MetadataTransactions.requireReadCommitted(database, "同步任务保存");
        registerTarget(task);
        List<Long> ids = assetIds(task.getConnectionId(),task.getTargetDb(),task.getTargetTable());
        if (ids.isEmpty()) throw new ApiException(409, "目标表登记状态已变化，请刷新后重试");
        for (Long id : ids) {
            List<Long> locked = jdbc.queryForList("SELECT id FROM el_data_assets WHERE id=?"
                            + " AND database_name=? AND table_name=? AND status<>'deleted' FOR UPDATE",
                    Long.class,id,task.getTargetDb(),task.getTargetTable());
            if (locked.isEmpty()) throw new ApiException(409, "目标表登记状态已变化，请刷新后重试");
        }
        // 等锁期间目标可能被删除或转移归属；不能沿用 registerTarget 中已过期的授权结果。
        access.table(task.getConnectionId(),task.getTargetDb(),task.getTargetTable(),true);
        uniqueTarget(task.getId(),task.getConnectionId(),task.getTargetDb(),task.getTargetTable());
        return save.get();
    }

    /**
     * 确保同一物理 ClickHouse 实例内的目标表只被一个同步任务占用。
     * 这里跨所有者检查冲突，但不返回冲突任务信息；连接别名不能绕过检查。
     *
     * @param taskId 当前任务 ID；更新任务时排除自身
     * @param connectionId 目标连接 ID
     * @param db 目标数据库
     * @param table 目标表
     * @throws com.el2ck.exception.ApiException 目标已被其他任务使用时抛出 400
     */
    public void uniqueTarget(long taskId,long connectionId,String db,String table) {
        long instanceId = instances == null ? 0 : instances.instanceId(connectionId);
        String sql = "SELECT t.id FROM sync_tasks t WHERE t.id<>? AND t.target_db=? AND t.target_table=?"
                + " AND (t.connection_id=?";
        List<Object> arguments = new ArrayList<>(Arrays.<Object>asList(taskId,db,table,connectionId));
        if (instanceId > 0) {
            sql += " OR t.connection_id IN (SELECT id FROM el_connections WHERE ck_instance_id=?)";
            arguments.add(instanceId);
        }
        sql += ") LIMIT 1";
        if (!jdbc.queryForList(sql,Long.class,arguments.toArray()).isEmpty())
            throw ApiException.validation("目标表已被其他同步任务使用，请选择独立目标表");
    }

    /**
     * 查询指定物理目标对应的未删除资产 ID。
     *
     * @param connectionId 连接 ID
     * @param db 数据库名
     * @param table 表名
     * @return 资产 ID 列表；未登记时为空
     */
    private List<Long> assetIds(long connectionId,String db,String table) {
        if(instances!=null) {
            return jdbc.queryForList("SELECT id FROM el_data_assets WHERE ck_instance_id=? AND database_name=? AND table_name=?"
                    + " AND status<>'deleted' ORDER BY id",Long.class,instances.instanceId(connectionId),db,table);
        }
        return jdbc.queryForList("SELECT id FROM el_data_assets WHERE backend_type='clickhouse' AND backend_id=?"
                + " AND database_name=? AND table_name=? AND status<>'deleted' ORDER BY id",Long.class,connectionId,db,table);
    }

    /**
     * 通过 CK system.tables 探测目标物理表是否已经存在。
     *
     * @param connectionId 连接 ID
     * @param db 数据库名
     * @param table 表名
     * @return 物理表存在时为 true
     * @throws com.el2ck.exception.ApiException CK 探测失败时转换为 502
     */
    private boolean physicalExists(long connectionId,String db,String table) {
        try {
            com.el2ck.ckpool.CkClient probe = (instances != null && identities != null)
                    ? identities.managementClient(connectionId)
                    : connections.client(connectionId);
            return !probe.query("SELECT name FROM system.tables WHERE database=? AND name=?",Arrays.<Object>asList(db,table))
                    .getRows().isEmpty();
        } catch (RuntimeException e) {
            throw ApiException.badGateway("检查目标表失败: " + com.el2ck.util.Sql.messageOf(e));
        }
    }
}
