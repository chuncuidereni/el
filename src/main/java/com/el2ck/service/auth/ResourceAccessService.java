/**
 * 【文件职责】本文件定义认证与资源授权模块的资源访问控制服务 ResourceAccessService。负责资源归属、可见范围和授权校验，并在需要时协调 ClickHouse 权限同步。
 */

package com.el2ck.service.auth;

import com.el2ck.config.AuthContext;
import com.el2ck.exception.ApiException;
import com.el2ck.service.ck.CkAccessProvisioner;
import com.el2ck.service.ck.CkInstanceService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.util.*;

/**
 * 平台资源归属与表级授权。
 *
 * <p>表访问有两层:本服务负责平台元数据层(所有者/有效共享),真实 CK 连接由
 * {@code CkIdentityService} 使用用户绑定账号执行。共享只有在 CK 授权核对成功后才置为 active;
 * 「存在一条 grant 记录」不再等于放行。
 */
@Service
public class ResourceAccessService {
    private final JdbcTemplate jdbc;
    private final AccountSecurityService accounts;
    private final CkInstanceService instances;
    private final CkAccessProvisioner provisioner;

    /**
     * 注入资源归属查询、账号审计和 ClickHouse 授权依赖。
     *
     * @param ds 平台元数据库；本服务只通过 JDBC 访问归属、授权和审计表
     * @param accounts 用户启用状态与审计服务
     * @param instances ClickHouse 实例与允许数据库登记；测试场景可为 null
     * @param provisioner ClickHouse 表级授权供给器；非 CK 资源场景可为 null
     */
    public ResourceAccessService(DataSource ds, AccountSecurityService accounts,
                                 CkInstanceService instances, CkAccessProvisioner provisioner) {
        this.jdbc = new JdbcTemplate(ds);
        this.accounts = accounts;
        this.instances = instances;
        this.provisioner = provisioner;
    }

    /** 内置 admin 的库表范围由已配置 CK 业务账号决定;不扩展到用户的数据源、任务或文件。 */
    public boolean adminDatabase(String db) {
        Set<String> allowed = instances == null ? new HashSet<>(Arrays.asList("ods_data","dwd_data")) : instances.allowedDatabases();
        return AuthContext.isBuiltinAdmin() && db != null && allowed.contains(db.toLowerCase(Locale.ROOT));
    }

    /**
     * 判断资产是否属于内置管理员可统一管理的 ClickHouse 数据库范围。
     *
     * @param a 数据资产查询行
     * @return 后端为 ClickHouse 且数据库位于管理员允许范围时为 true
     */
    private boolean adminAsset(Map<String,Object> a) {
        return "clickhouse".equals(a.get("backend_type")) && adminDatabase(String.valueOf(a.get("database_name")));
    }

    /** 直连 CK 创建、尚未登记的平台外表只补展示信息,不会自动认领或伪造创建者。 */
    public Map<String,Object> unregisteredAdminTable(String db,String table) {
        if (!adminDatabase(db)) throw ApiException.notFound("表不存在或无权访问");
        Map<String,Object> a = new LinkedHashMap<>();
        a.put("assetId",null); a.put("ownerUserId",null); a.put("creatorUserId",null);
        a.put("database",db); a.put("table",table); a.put("creatorName","创建者未知");
        a.put("accessMode","admin"); a.put("canRead",true); a.put("canWrite",true);
        a.put("canManage",false); a.put("canShare",false); a.put("permissionStatus","unregistered");
        a.put("canDrop",true);
        return a;
    }

    /**
     * 读取资源持久化的所有者 ID。
     *
     * @param type 资源类型，例如 table、workflow 或 datasource
     * @param id 资源 ID
     * @return 所有者用户 ID；没有归属记录时返回 0
     */
    public long owner(String type, long id) {
        List<Long> ids = jdbc.queryForList("SELECT owner_user_id FROM el_resource_owners WHERE resource_type=? AND resource_id=?", Long.class, type, id);
        return ids.isEmpty() ? 0 : ids.get(0);
    }

    /**
     * 校验当前登录用户是资源所有者。
     *
     * @param type 资源类型
     * @param id 资源 ID
     * @throws com.el2ck.exception.ApiException 当前用户不是所有者时抛出 404，避免泄露资源是否存在
     */
    public void requireOwner(String type, long id) {
        if (owner(type, id) != AuthContext.userId()) throw ApiException.notFound("资源不存在或无权访问");
    }

    /**
     * 判断当前登录用户是否为资源所有者，不抛异常。
     *
     * @param type 资源类型
     * @param id 资源 ID
     * @return 当前用户为所有者时为 true
     */
    public boolean isOwner(String type, long id) {
        return owner(type, id) == AuthContext.userId();
    }

    /**
     * 根据工作流运行记录反查工作流，再校验其所有权。
     *
     * @param runId 工作流运行 ID
     * @throws com.el2ck.exception.ApiException 运行记录不存在或工作流不属于当前用户时抛出 404
     */
    public void requireWorkflowRun(long runId) {
        List<Long> ids = jdbc.queryForList("SELECT workflow_id FROM wf_runs WHERE id=?", Long.class, runId);
        if (ids.isEmpty()) throw ApiException.notFound("运行记录不存在");
        requireOwner("workflow", ids.get(0));
    }

    /**
     * 将指定资源登记给当前登录用户。
     *
     * @param type 资源类型
     * @param id 资源 ID
     */
    public void register(String type, long id) {
        jdbc.update("INSERT INTO el_resource_owners(resource_type,resource_id,owner_user_id) VALUES(?,?,?)", type, id, AuthContext.userId());
    }

    /** 返回持久化归属，不以当前登录用户名代替实际所有者。 */
    public Map<String, Object> ownerInfo(String type, long id) {
        requireOwner(type, id);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT o.owner_user_id AS ownerUserId,u.username AS ownerUsername"
                        + " FROM el_resource_owners o LEFT JOIN app_users u ON u.id=o.owner_user_id"
                        + " WHERE o.resource_type=? AND o.resource_id=?", type, id);
        if (rows.isEmpty()) throw ApiException.notFound("资源不存在或未绑定用户");
        return rows.get(0);
    }

    /**
     * 查询当前用户拥有的某类资源 ID。
     *
     * @param type 资源类型
     * @return 当前用户拥有的资源 ID 集合；没有记录时为空集合
     */
    public Set<Long> ownedIds(String type) {
        return new HashSet<>(jdbc.queryForList("SELECT resource_id FROM el_resource_owners WHERE resource_type=? AND owner_user_id=?", Long.class, type, AuthContext.userId()));
    }

    /** 幂等登记物理表资产并返回资产 ID(工作流产物重复生成时复用同一条资产)。 */
    public long registerOrGetTable(String backend, long backendId, String db, String table, long owner) {
        String database = db == null ? "" : db;
        List<Long> ids = jdbc.queryForList("SELECT id FROM el_data_assets WHERE backend_type=? AND backend_id=?"
                + " AND database_name=? AND table_name=?", Long.class, backend, backendId, database, table);
        if (!ids.isEmpty()) return ids.get(0);
        try {
            return registerTable(backend, backendId, database, table, owner);
        } catch (ApiException e) {
            if (e.getStatus() == 409) {
                ids = jdbc.queryForList("SELECT id FROM el_data_assets WHERE backend_type=? AND backend_id=?"
                        + " AND database_name=? AND table_name=?", Long.class, backend, backendId, database, table);
                if (!ids.isEmpty()) return ids.get(0);
            }
            throw e;
        }
    }

    /**
     * 删除当前用户对指定资源的归属登记。
     *
     * @param type 资源类型
     * @param id 资源 ID
     */
    public void remove(String type, long id) {
        requireOwner(type, id);
        jdbc.update("DELETE FROM el_resource_grants WHERE resource_type=? AND resource_id=?", type, id);
        jdbc.update("DELETE FROM el_resource_owners WHERE resource_type=? AND resource_id=?", type, id);
    }

    // =====================================================================
    // 表资产
    // =====================================================================

    /** 当前用户可见的资产:所有者,或持有 active 共享。 */
    public Map<String, Object> asset(long id) {
        List<Map<String, Object>> rows = assetQuery("a.id=?", id);
        if (rows.isEmpty()) throw ApiException.notFound("表不存在");
        Map<String, Object> a = rows.get(0);
        boolean owner = number(a, "owner_user_id") == AuthContext.userId();
        boolean granted = hasGrant("table", id);
        if (!owner && !granted && !adminAsset(a)) throw ApiException.notFound("表不存在或无权访问");
        enrich(a, owner);
        return a;
    }

    /** 是否存在有效共享(只认 CK 授权已完成的 active 记录)。 */
    public boolean hasGrant(String type, long id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM el_resource_grants WHERE resource_type=? AND resource_id=?"
                + " AND grantee_user_id=? AND status='active'", Long.class, type, id, AuthContext.userId()) > 0;
    }

    /**
     * 按管理范围查询当前用户可查看的数据资产。
     *
     * @param scope 范围标识；管理员范围会包含受管 CK 库，其他值按用户归属和有效共享过滤
     * @return 带所有者、创建者及读写能力字段的资产视图
     */
    public List<Map<String, Object>> assets(String scope) {
        long uid = AuthContext.userId();
        String where = "shared".equals(scope)
                ? "a.owner_user_id<>? AND EXISTS(SELECT 1 FROM el_resource_grants g WHERE g.resource_type='table'"
                    + " AND g.resource_id=a.id AND g.grantee_user_id=? AND g.status='active')"
                : "a.owner_user_id=?";
        List<Map<String, Object>> rows = assetQuery(where + " AND a.status<>'deleted' ORDER BY a.id DESC",
                "shared".equals(scope) ? new Object[]{uid, uid} : new Object[]{uid});
        for (Map<String, Object> a : rows) enrich(a, number(a, "owner_user_id") == uid);
        return rows;
    }

    /**
     * 按物理库表定位资产并鉴权。
     *
     * @param manage true 表示写/共享等所有者动作,共享表一律拒绝
     */
    public Map<String, Object> table(long connectionId, String db, String table, boolean manage) {
        long instanceId = instances == null ? 0 : instances.instanceId(connectionId);
        // 写/生命周期入口允许重试 failed 目标;读入口只认可用状态。
        String statuses = manage ? "'active','changing','provisioning','failed'" : "'active','changing','provisioning'";
        List<Map<String, Object>> rows = instanceId > 0
                ? assetQuery("(a.ck_instance_id=? OR (a.ck_instance_id IS NULL AND a.backend_type='clickhouse' AND a.backend_id=?))"
                    + " AND a.database_name=? AND a.table_name=? AND a.status IN (" + statuses + ")",
                    instanceId, connectionId, db == null ? "" : db, table)
                : assetQuery("a.backend_type='clickhouse' AND a.backend_id=? AND a.database_name=? AND a.table_name=? AND a.status IN (" + statuses + ")",
                    connectionId, db == null ? "" : db, table);
        if (rows.isEmpty()) {
            if (adminDatabase(db)) return unregisteredAdminTable(db,table);
            throw ApiException.notFound("表尚未分配或无权访问");
        }
        Map<String, Object> a = rows.get(0);
        boolean owner = number(a, "owner_user_id") == AuthContext.userId();
        boolean admin = adminAsset(a);
        if (!owner && !admin && !hasGrant("table", number(a, "id"))) throw ApiException.notFound("表尚未分配或无权访问");
        if (manage && !owner && !admin) throw new ApiException(403, "共享表只允许查看");
        enrich(a, owner);
        return a;
    }

    /**
     * 计算当前用户在指定连接和数据库内可见的表名。
     *
     * <p>只纳入 active 资产，并要求用户为所有者或持有 active 共享。
     *
     * @param connectionId 连接 ID
     * @param db 数据库名
     * @return 可见表名集合
     */
    public Set<String> visibleTables(long connectionId, String db) {
        long uid = AuthContext.userId();
        long instanceId = instances == null ? 0 : instances.instanceId(connectionId);
        String base = instanceId > 0
                ? "(a.ck_instance_id=? OR (a.ck_instance_id IS NULL AND a.backend_type='clickhouse' AND a.backend_id=?))"
                : "a.backend_type='clickhouse' AND a.backend_id=?";
        Object[] where = instanceId > 0 ? new Object[]{instanceId, connectionId, db, uid, uid} : new Object[]{connectionId, db, uid, uid};
        return new HashSet<>(jdbc.queryForList("SELECT a.table_name FROM el_data_assets a WHERE " + base
                        + " AND a.database_name=? AND a.status='active' AND (a.owner_user_id=?"
                        + " OR EXISTS(SELECT 1 FROM el_resource_grants g WHERE g.resource_type='table' AND g.resource_id=a.id"
                        + " AND g.grantee_user_id=? AND g.status='active'))",
                String.class, where));
    }

    /** 当前用户在某个库内可见表的元数据与能力(供原表列表合并展示创建者/权限)。 */
    public List<Map<String, Object>> tableMetas(long connectionId, String db) {
        long uid = AuthContext.userId();
        long instanceId = instances == null ? 0 : instances.instanceId(connectionId);
        String base = instanceId > 0
                ? "(a.ck_instance_id=? OR (a.ck_instance_id IS NULL AND a.backend_type='clickhouse' AND a.backend_id=?))"
                : "a.backend_type='clickhouse' AND a.backend_id=?";
        if (adminDatabase(db)) {
            Object[] args = instanceId > 0 ? new Object[]{db,instanceId,connectionId} : new Object[]{db,connectionId};
            List<Map<String,Object>> all = assetQuery("a.database_name=? AND a.status='active' AND " + base + " ORDER BY a.table_name",args);
            for (Map<String,Object> a : all) enrich(a,number(a,"owner_user_id")==uid);
            return all;
        }
        Object[] where = instanceId > 0 ? new Object[]{db, instanceId, connectionId, uid, uid} : new Object[]{db, connectionId, uid, uid};
        List<Map<String, Object>> rows = assetQuery("a.database_name=? AND a.status='active' AND " + base
                        + " AND (a.owner_user_id=? OR EXISTS(SELECT 1 FROM el_resource_grants g WHERE g.resource_type='table'"
                        + " AND g.resource_id=a.id AND g.grantee_user_id=? AND g.status='active')) ORDER BY a.table_name",
                where);
        for (Map<String, Object> row : rows) enrich(row, number(row, "owner_user_id") == uid);
        return rows;
    }

    /**
     * 计算当前用户在指定连接内至少拥有一张可见表的数据库集合。
     *
     * @param connectionId 连接 ID
     * @return 可见数据库名集合
     */
    public Set<String> visibleDatabases(long connectionId) {
        long uid = AuthContext.userId();
        long instanceId = instances == null ? 0 : instances.instanceId(connectionId);
        String base = instanceId > 0
                ? "(a.ck_instance_id=? OR (a.ck_instance_id IS NULL AND a.backend_type='clickhouse' AND a.backend_id=?))"
                : "a.backend_type='clickhouse' AND a.backend_id=?";
        Object[] where = instanceId > 0 ? new Object[]{instanceId, connectionId, uid, uid} : new Object[]{connectionId, uid, uid};
        return new HashSet<>(jdbc.queryForList("SELECT DISTINCT a.database_name FROM el_data_assets a WHERE " + base
                        + " AND a.status='active' AND (a.owner_user_id=?"
                        + " OR EXISTS(SELECT 1 FROM el_resource_grants g WHERE g.resource_type='table' AND g.resource_id=a.id"
                        + " AND g.grantee_user_id=? AND g.status='active'))",
                String.class, where));
    }

    /**
     * 查找当前用户通过自有或已共享表能够访问的 ClickHouse 连接。
     *
     * @return 可访问连接 ID 集合
     */
    public Set<Long> visibleConnections() {
        long uid = AuthContext.userId();
        Set<Long> instanceIds = new HashSet<>(jdbc.queryForList(
                "SELECT DISTINCT a.ck_instance_id FROM el_data_assets a WHERE a.ck_instance_id IS NOT NULL AND a.status='active'"
                        + " AND (a.owner_user_id=? OR EXISTS(SELECT 1 FROM el_resource_grants g WHERE g.resource_type='table'"
                        + " AND g.resource_id=a.id AND g.grantee_user_id=? AND g.status='active'))", Long.class, uid, uid));
        Set<Long> out = new HashSet<>();
        for (long instanceId : instanceIds) {
            out.addAll(jdbc.queryForList("SELECT id FROM el_connections WHERE ck_instance_id=?", Long.class, instanceId));
        }
        return out;
    }

    /**
     * 幂等登记物理表资产，并记录所有者、来源和初始状态。
     *
     * <p>方法在事务内执行；同一后端、数据库和表的重复登记会转换为 409。
     *
     * @param backend 后端类型，例如 clickhouse、datasource
     * @param backendId 后端连接或数据源 ID
     * @param db 数据库名
     * @param table 表名
     * @param owner 所有者用户 ID
     * @return 已存在或新登记的资产 ID
     */
    @Transactional
    public long registerTable(String backend, long backendId, String db, String table, long owner) {
        return registerTable(backend, backendId, db, table, owner, "active", "manual", null);
    }

    /** 登记表资产;同步自动建表时先以 provisioning 登记,授权完成后由生命周期转 active。 */
    @Transactional
    public long registerTable(String backend, long backendId, String db, String table, long owner,
                              String status, String originType, Long originId) {
        if (!"clickhouse".equals(backend) && !"datasource".equals(backend)) throw ApiException.validation("无效的表类型");
        if (table == null || table.trim().isEmpty() || table.length() > 255) throw ApiException.validation("表名不能为空或过长");
        Long instanceId = null;
        if ("clickhouse".equals(backend)) {
            instanceId = instances == null ? null : instances.instanceId(backendId);
            if (instanceId != null && instanceId > 0) instances.requireAllowedDatabase(db);
        }
        try {
            jdbc.update("INSERT INTO el_data_assets(backend_type,backend_id,database_name,table_name,owner_user_id,"
                            + "ck_instance_id,creator_user_id,creator_name_snapshot,status,origin_type,origin_id,permission_version)"
                            + " VALUES(?,?,?,?,?,?,?,?,?,?,?,0)",
                    backend, backendId, db == null ? "" : db, table, owner, instanceId, owner,
                    AuthContext.get() == null ? null : AuthContext.get().getUsername(),
                    status, originType, originId);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new ApiException(409, "该表已登记，请使用已有表或联系管理员核对归属");
        }
        long id = jdbc.queryForObject("SELECT id FROM el_data_assets WHERE backend_type=? AND backend_id=? AND database_name=? AND table_name=?",
                Long.class, backend, backendId, db == null ? "" : db, table);
        accounts.audit("table.register", "table", id, owner);
        return id;
    }

    // =====================================================================
    // 共享 / 撤销
    // =====================================================================

    /**
     * 共享:先落 pending 记录,再执行 CK 授权并核对,成功后才转 active。
     * CK 网络操作刻意不放在长时间持有行锁的事务里。
     */
    public void share(long id, long recipient) {
        Map<String, Object> a = asset(id);
        if (number(a, "owner_user_id") != AuthContext.userId()) throw new ApiException(403, "只有所有者可以共享");
        if (recipient == AuthContext.userId()) throw ApiException.validation("无需共享给自己");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM app_users WHERE id=?", Long.class, recipient) == 0 || !accounts.enabled(recipient))
            throw ApiException.validation("接收用户不存在或已禁用");
        long expected = number(a, "permission_version");
        jdbc.update("INSERT INTO el_resource_grants(resource_type,resource_id,grantee_user_id,granted_by,permission,status,expected_version,applied_version)"
                        + " VALUES('table',?,?,?,'view','pending',?,0) ON DUPLICATE KEY UPDATE granted_by=VALUES(granted_by),"
                        + " status='pending',expected_version=VALUES(expected_version),applied_version=0,last_error=NULL,updated_at=CURRENT_TIMESTAMP",
                id, recipient, AuthContext.userId(), expected);
        if (provisioner == null || !"clickhouse".equals(String.valueOf(a.get("backend_type")))
                || a.get("ck_instance_id") == null) {
            // 外部数据源/文件表没有 CK 角色可授,平台侧 active 即为生效。
            jdbc.update("UPDATE el_resource_grants SET status='active',applied_version=? WHERE resource_type='table' AND resource_id=? AND grantee_user_id=?",
                    expected, id, recipient);
        } else {
            provisioner.grantRead(id, recipient, expected);
        }
        accounts.audit("share.grant", "table", id, recipient);
    }

    /** 撤销:先阻断平台访问,再撤 CK 角色并核对,失败保留 revoking。 */
    public void revoke(long id, long recipient) {
        Map<String, Object> a = asset(id);
        if (number(a, "owner_user_id") != AuthContext.userId()) throw new ApiException(403, "只有所有者可以撤销共享");
        long expected = number(a, "permission_version") + 1;
        jdbc.update("UPDATE el_resource_grants SET status='revoking',expected_version=?,updated_at=CURRENT_TIMESTAMP"
                + " WHERE resource_type='table' AND resource_id=? AND grantee_user_id=?", expected, id, recipient);
        if (provisioner == null || !"clickhouse".equals(String.valueOf(a.get("backend_type")))
                || a.get("ck_instance_id") == null) {
            jdbc.update("UPDATE el_resource_grants SET status='revoked',applied_version=? WHERE resource_type='table' AND resource_id=? AND grantee_user_id=?",
                    expected, id, recipient);
        } else {
            provisioner.revokeRead(id, recipient, expected);
        }
        accounts.audit("share.revoke", "table", id, recipient);
    }

    /**
     * 查询某表资产的共享记录及实时授权状态。
     *
     * @param id 表资产 ID
     * @return 接收用户、权限、状态和最近错误列表
     * @throws com.el2ck.exception.ApiException 当前用户不是所有者时抛出 403
     */
    public List<Map<String, Object>> shares(long id) {
        if (number(asset(id), "owner_user_id") != AuthContext.userId()) throw new ApiException(403, "只有所有者可以管理共享");
        return jdbc.queryForList("SELECT g.grantee_user_id AS userId,u.username,g.permission,g.status,g.last_error AS lastError,"
                + "g.created_at AS createdAt,g.updated_at AS updatedAt FROM el_resource_grants g"
                + " JOIN app_users u ON u.id=g.grantee_user_id WHERE g.resource_type='table' AND g.resource_id=?"
                + " ORDER BY g.created_at", id);
    }

    /**
     * 搜索可作为共享接收者的启用用户。
     *
     * @param query 用户名关键字；null 按空关键字处理，最长 100 个字符
     * @return 最多 50 个候选用户，排除当前用户和禁用账号
     */
    public List<Map<String, Object>> recipients(String query) {
        String keyword = query == null ? "" : query.trim();
        if (keyword.length() > 100) throw ApiException.validation("搜索用户名过长");
        return jdbc.queryForList("SELECT u.id AS userId,u.username FROM app_users u LEFT JOIN el_user_security s ON s.user_id=u.id"
                + " WHERE LOCATE(?,u.username)>0 AND u.id<>? AND COALESCE(s.enabled,TRUE)=TRUE ORDER BY u.username,u.id LIMIT 50",
                keyword, AuthContext.userId());
    }

    /** Source configuration changes must never carry grants to a different physical source. */
    @Transactional
    public void retireSourceAssets(long sourceId) {
        requireOwner("datasource", sourceId);
        List<Long> ids = jdbc.queryForList("SELECT id FROM el_data_assets WHERE backend_type='datasource' AND backend_id=?", Long.class, sourceId);
        for (long id : ids) {
            jdbc.update("DELETE FROM el_resource_grants WHERE resource_type='table' AND resource_id=?", id);
            jdbc.update("DELETE FROM el_data_assets WHERE id=?", id);
            accounts.audit("table.retire", "table", id, null);
        }
    }

    // =====================================================================
    // 内部
    // =====================================================================

    /**
     * 执行资产查询并补充所有者和创建者展示名。
     *
     * @param where 由本类固定片段拼出的 WHERE 条件，不接受调用方 SQL
     * @param args 条件中的 JDBC 参数
     * @return 资产原始行及联表展示字段
     */
    private List<Map<String, Object>> assetQuery(String where, Object... args) {
        return jdbc.queryForList("SELECT a.*,u.username AS owner_name,c.username AS creator_name,"
                + "COALESCE(c.username,a.creator_name_snapshot) AS creator_display"
                + " FROM el_data_assets a LEFT JOIN app_users u ON u.id=a.owner_user_id"
                + " LEFT JOIN app_users c ON c.id=a.creator_user_id WHERE " + where, args);
    }

    /**
     * 为资产查询行补充前端使用的标识、访问模式和能力标记。
     *
     * @param a 将被原地修改的资产行
     * @param owner 当前用户是否已确认为所有者
     */
    private void enrich(Map<String, Object> a, boolean owner) {
        long assetId = number(a, "id");
        long ownerId = number(a, "owner_user_id");
        boolean granted = !owner && hasGrant("table", assetId);
        boolean admin = adminAsset(a);
        String status = a.get("status") == null ? "active" : String.valueOf(a.get("status"));
        a.put("assetId", assetId);
        a.put("database", a.get("database_name"));
        a.put("table", a.get("table_name"));
        a.put("ownerUserId", ownerId);
        a.put("creatorUserId", a.get("creator_user_id"));
        String creator = a.get("creator_display") == null ? null : String.valueOf(a.get("creator_display"));
        a.put("creatorName", creator == null ? "历史数据" : creator);
        a.put("accessMode", owner ? "owner" : admin ? "admin" : "read");
        a.put("canRead", owner || granted || admin);
        a.put("canWrite", owner || admin);
        a.put("canManage", owner || admin);
        a.put("canDrop", owner || admin);
        a.put("canShare", owner);
        a.put("permissionStatus", status);
    }

    /**
     * 将数据库驱动返回的数值或数值字符串统一转换为 long。
     *
     * @param row 查询结果行
     * @param field 字段名
     * @return 字段的长整数值
     * @throws NumberFormatException 字段为空或不是合法数字时抛出
     */
    public static long number(Map<String, Object> row, String field) {
        Object v = row.get(field);
        return v instanceof Number ? ((Number) v).longValue() : Long.parseLong(String.valueOf(v));
    }
}
