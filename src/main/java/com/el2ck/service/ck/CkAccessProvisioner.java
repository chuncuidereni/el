/**
 * 【文件职责】本文件定义ck模块的访问权限供给服务 CkAccessProvisioner。负责 ClickHouse 用户/授权变更及其状态核对，调用失败时保留可重试或审计所需的操作状态。
 */

package com.el2ck.service.ck;

import com.el2ck.config.AuthContext;
import com.el2ck.exception.ApiException;
import com.el2ck.util.Sql;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.*;

/**
 * 受控的 CK 账号授权指令与结果核对。
 *
 * <p>每张表一个读取角色 {@code el_t_{assetId}_g{generation}_read},只授予该精确表的
 * SELECT/SHOW;所有者另按开放功能直接在表上授予写入权限。任何语句都由管理身份执行,
 * 业务身份永远拿不到账号管理或转授权限。
 *
 * <p>授权不是「写一条 GRANT 就宣布成功」:授予后会用接收者自己的 CK 身份执行
 * {@code SELECT ... LIMIT 0} 核对实际可用;撤权后同样核对 SELECT 已失败。
 */
@Service
public class CkAccessProvisioner {

    /** 读取角色只包含这两项,避免 SHOW CREATE TABLE 因缺少 SHOW 而失败。 */
    private static final String READ_PRIVILEGES = "SELECT, SHOW";
    /** 所有者写入权限精确到表;不授予库级通配,也不授予自由 DROP/CREATE。 */
    static final String WRITE_PRIVILEGES = "INSERT, ALTER UPDATE, ALTER DELETE, ALTER ADD COLUMN, "
            + "ALTER DROP COLUMN, ALTER MODIFY COLUMN, ALTER RENAME COLUMN, TRUNCATE, OPTIMIZE";

    private final JdbcTemplate jdbc;
    private final CkIdentityService identities;
    private final CkInstanceService instances;

    /**
     * 注入平台元数据库、CK 执行身份和实例登记服务。
     *
     * @param ds 平台元数据库
     * @param identities 业务身份与管理身份客户端提供者
     * @param instances CK 实例和允许数据库登记
     */
    public CkAccessProvisioner(DataSource ds, CkIdentityService identities, CkInstanceService instances) {
        this.jdbc = new JdbcTemplate(ds);
        this.identities = identities;
        this.instances = instances;
    }

    /** 读取角色名:资产 ID + 生命周期代次,重建后旧角色不会被同名新表继承。 */
    public static String readRole(long assetId, int generation) {
        return "el_t_" + assetId + "_g" + generation + "_read";
    }

    // =====================================================================
    // 所有者权限
    // =====================================================================

    /** 为资产所有者建立读取角色并授予精确读写权限。 */
    public void grantOwner(long assetId) {
        grantOwner(assetId, null);
    }

    /**
     * @param supportingTable 服务表为 View 时,底层聚合表需要的只读表名(同库);不登记资产、不暴露给列表。
     */
    public void grantOwner(long assetId, String supportingTable) {
        Map<String, Object> a = asset(assetId);
        long instanceId = num(a.get("ck_instance_id"));
        long owner = num(a.get("owner_user_id"));
        if (instanceId <= 0) throw new ApiException(409, "表资产缺少 CK 实例信息");
        String db = str(a.get("database_name"));
        String table = str(a.get("table_name"));
        instances.requireAllowedDatabase(db);
        String role = readRole(assetId, ((Number) a.get("generation")).intValue());
        String user = userName(owner, instanceId);
        long version = expectedVersion(a);
        long op = begin("asset:" + assetId + ":g" + a.get("generation") + ":owner:grant", assetId, owner,
                instanceId, "grant-owner", version);
        try {
            com.el2ck.ckpool.CkClient mgmt = identities.managementClientForInstance(instanceId);
            String q = qualified(db, table);
            mgmt.exec("CREATE ROLE IF NOT EXISTS " + role);
            mgmt.exec("GRANT " + READ_PRIVILEGES + " ON " + q + " TO " + role);
            if (supportingTable != null && !supportingTable.isEmpty() && !supportingTable.equals(table)) {
                // 服务表是 View,查询时按调用者身份检查底层聚合表的 SELECT。
                mgmt.exec("GRANT SELECT, SHOW ON " + qualified(db, supportingTable) + " TO " + role);
            }
            if (!identities.usesConfiguredAccount(owner,instanceId)) {
                mgmt.exec("GRANT " + role + " TO " + user);
                mgmt.exec("GRANT " + WRITE_PRIVILEGES + " ON " + q + " TO " + user);
                refreshDefaultRoles(instanceId, owner, role, -1);
            }
            verifyRead(a, owner);
            jdbc.update("UPDATE el_data_assets SET permission_version=permission_version+1,last_error=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                    assetId);
            finish(op, "active", null);
            // 角色默认值在会话建立时生效;授权后丢弃该用户的池化连接,下一次请求重新登录。
            identities.invalidateUser(owner);
            audit("ck.grant.owner", "table", assetId, owner);
        } catch (RuntimeException e) {
            String error = CkIdentityService.safeMessage(e);
            jdbc.update("UPDATE el_data_assets SET last_error=?,updated_at=CURRENT_TIMESTAMP WHERE id=?", error, assetId);
            finish(op, "failed", error);
            throw new ApiException(502, "CK 表权限授予失败: " + error);
        }
    }

    // =====================================================================
    // 共享授权 / 撤权
    // =====================================================================

    /** 把读取角色授予接收者,并核对接收者确实能读取该精确表。 */
    public void grantRead(long assetId, long userId, long expectedVersion) {
        Map<String, Object> a = asset(assetId);
        long currentVersion = expectedVersion(a);
        if (expectedVersion > 0 && currentVersion != expectedVersion) {
            throw new ApiException(409, "表权限版本已变化，请刷新后重新共享");
        }
        long instanceId = num(a.get("ck_instance_id"));
        String role = readRole(assetId, ((Number) a.get("generation")).intValue());
        String db = str(a.get("database_name"));
        String table = str(a.get("table_name"));
        long op = begin("asset:" + assetId + ":g" + a.get("generation") + ":user:" + userId + ":grant-read:" + expectedVersion,
                assetId, userId, instanceId, "grant-read", expectedVersion);
        try {
            com.el2ck.ckpool.CkClient mgmt = identities.managementClientForInstance(instanceId);
            String user = userName(userId, instanceId);
            mgmt.exec("CREATE ROLE IF NOT EXISTS " + role);
            mgmt.exec("GRANT " + READ_PRIVILEGES + " ON " + qualified(db, table) + " TO " + role);
            if (!identities.usesConfiguredAccount(userId,instanceId)) {
                mgmt.exec("GRANT " + role + " TO " + user);
                refreshDefaultRoles(instanceId, userId, role, -1);
            }
            verifyRead(a, userId);
            jdbc.update("UPDATE el_resource_grants SET status='active',applied_version=?,last_error=NULL,updated_at=CURRENT_TIMESTAMP"
                            + " WHERE resource_type='table' AND resource_id=? AND grantee_user_id=?",
                    expectedVersion, assetId, userId);
            finish(op, "active", null);
            identities.invalidateUser(userId);
            audit("share.grant", "table", assetId, userId);
        } catch (RuntimeException e) {
            String error = CkIdentityService.safeMessage(e);
            // 失败保持待处理,由后台对账重试;不提前宣称共享成功。
            jdbc.update("UPDATE el_resource_grants SET status='pending',last_error=?,updated_at=CURRENT_TIMESTAMP"
                            + " WHERE resource_type='table' AND resource_id=? AND grantee_user_id=?",
                    error, assetId, userId);
            finish(op, "failed", error);
            throw new ApiException(502, "CK 共享授权失败: " + error);
        }
    }

    /** 撤销接收者的读取角色,并核对接收者已无法再读取该表。 */
    public void revokeRead(long assetId, long userId, long expectedVersion) {
        Map<String, Object> a = asset(assetId);
        long instanceId = num(a.get("ck_instance_id"));
        String role = readRole(assetId, ((Number) a.get("generation")).intValue());
        String db = str(a.get("database_name"));
        String table = str(a.get("table_name"));
        long op = begin("asset:" + assetId + ":g" + a.get("generation") + ":user:" + userId + ":revoke-read:" + expectedVersion,
                assetId, userId, instanceId, "revoke-read", expectedVersion);
        try {
            com.el2ck.ckpool.CkClient mgmt = identities.managementClientForInstance(instanceId);
            String user = userName(userId, instanceId);
            try {
                mgmt.exec("REVOKE " + role + " FROM " + user);
            } catch (RuntimeException ignored) {
                // 角色可能本来就没授给该用户;以随后的「不可读」核对为准。
            }
            refreshDefaultRoles(instanceId, userId, null, assetId);
            // 撤销共享不能移除管理员业务账号本来持有的库级授权。
            if (!identities.usesConfiguredAccount(userId,instanceId)) verifyCannotRead(a, userId);
            jdbc.update("UPDATE el_resource_grants SET status='revoked',applied_version=?,last_error=NULL,updated_at=CURRENT_TIMESTAMP"
                            + " WHERE resource_type='table' AND resource_id=? AND grantee_user_id=?",
                    expectedVersion, assetId, userId);
            finish(op, "done", null);
            identities.invalidateUser(userId);
            audit("share.revoke", "table", assetId, userId);
        } catch (RuntimeException e) {
            String error = CkIdentityService.safeMessage(e);
            jdbc.update("UPDATE el_resource_grants SET status='revoking',last_error=?,updated_at=CURRENT_TIMESTAMP"
                            + " WHERE resource_type='table' AND resource_id=? AND grantee_user_id=?",
                    error, assetId, userId);
            finish(op, "failed", error);
            throw new ApiException(502, "CK 撤权尚未完成: " + error);
        }
    }

    // =====================================================================
    // 建表临时权限
    // =====================================================================

    /** 建表前临时授予用户精确目标表的 CREATE TABLE;管理连接不代跑用户 DDL。 */
    public void grantCreate(long connectionId, String database, String table, long userId) {
        long instanceId = instances.instanceId(connectionId);
        if (identities.usesConfiguredAccount(userId,instanceId)) return;
        String user = userName(userId, instanceId);
        // CREATE TABLE ... AS SELECT 在 ClickHouse 中还需要目标表的 INSERT 权限。
        identities.managementClientForInstance(instanceId).exec("GRANT CREATE TABLE, INSERT ON "
                + qualified(database, table) + " TO " + user);
    }

    /** 建表结束后立即收回临时 CREATE TABLE 权限。 */
    public void revokeCreate(long connectionId, String database, String table, long userId) {
        long instanceId = instances.instanceId(connectionId);
        if (identities.usesConfiguredAccount(userId,instanceId)) return;
        String user = userName(userId, instanceId);
        String q = qualified(database, table);
        try {
            identities.managementClientForInstance(instanceId).exec("REVOKE INSERT ON " + q + " FROM " + user);
        } catch (RuntimeException ignored) {
            // 表可能因建表失败不存在;继续收回 CREATE 权限。
        }
        identities.managementClientForInstance(instanceId).exec("REVOKE CREATE TABLE ON " + q + " FROM " + user);
    }

    // =====================================================================
    // 生命周期清理
    // =====================================================================

    /** 删除/重建前清除该代的全部角色与直接权限。调用方负责先把资产置为 deleting。 */
    public void cleanupAsset(long assetId) {
        Map<String, Object> a = asset(assetId);
        long instanceId = num(a.get("ck_instance_id"));
        if (instanceId <= 0) return;
        String role = readRole(assetId, ((Number) a.get("generation")).intValue());
        String db = str(a.get("database_name"));
        String table = str(a.get("table_name"));
        long owner = num(a.get("owner_user_id"));
        com.el2ck.ckpool.CkClient mgmt = identities.managementClientForInstance(instanceId);
        Set<Long> users = new LinkedHashSet<>();
        users.add(owner);
        users.addAll(jdbc.queryForList("SELECT grantee_user_id FROM el_resource_grants"
                + " WHERE resource_type='table' AND resource_id=? AND status<>'revoked'", Long.class, assetId));
        for (long userId : users) {
            if (identities.usesConfiguredAccount(userId,instanceId)) continue;
            String user = storedUserName(userId, instanceId);
            if (user.isEmpty()) continue;
            try { mgmt.exec("REVOKE " + role + " FROM " + user); } catch (RuntimeException ignored) { }
            try { mgmt.exec("REVOKE " + WRITE_PRIVILEGES + " ON " + qualified(db, table) + " FROM " + user); } catch (RuntimeException ignored) { }
            try { refreshDefaultRoles(instanceId, userId, null, assetId); } catch (RuntimeException ignored) { }
            identities.invalidateUser(userId);
        }
        try { mgmt.exec("REVOKE " + READ_PRIVILEGES + " ON " + qualified(db, table) + " FROM " + role); } catch (RuntimeException ignored) { }
        mgmt.exec("DROP ROLE IF EXISTS " + role);
    }

    // =====================================================================
    // 默认角色与核对
    // =====================================================================

    /**
     * 重新计算并设置用户的默认角色集合。只包含平台为该用户登记的有效读取角色,
     * 不把外部授予的角色一并激活。
     */
    public void refreshDefaultRoles(long instanceId, long userId, String extraRole, long excludeAssetId) {
        if (identities.usesConfiguredAccount(userId,instanceId)) return;
        Set<String> roles = new LinkedHashSet<>();
        if (extraRole != null && !extraRole.isEmpty()) roles.add(extraRole);
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT id,generation FROM el_data_assets WHERE owner_user_id=? AND ck_instance_id=? AND status='active'",
                userId, instanceId)) {
            long id = num(row.get("id"));
            if (id != excludeAssetId) roles.add(readRole(id, ((Number) row.get("generation")).intValue()));
        }
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT a.id,a.generation FROM el_resource_grants g JOIN el_data_assets a ON a.id=g.resource_id"
                        + " WHERE g.resource_type='table' AND g.grantee_user_id=? AND g.status='active'"
                        + " AND a.ck_instance_id=? AND a.status='active'", userId, instanceId)) {
            long id = num(row.get("id"));
            if (id != excludeAssetId) roles.add(readRole(id, ((Number) row.get("generation")).intValue()));
        }
        com.el2ck.ckpool.CkClient mgmt = identities.managementClientForInstance(instanceId);
        String user = userName(userId, instanceId);
        if (roles.isEmpty()) {
            mgmt.exec("SET DEFAULT ROLE NONE TO " + user);
            return;
        }
        mgmt.exec("SET DEFAULT ROLE " + String.join(", ", roles) + " TO " + user);
    }

    /** 以接收者身份核对 SELECT 可用。 */
    public void verifyRead(Map<String, Object> asset, long userId) {
        long instanceId = num(asset.get("ck_instance_id"));
        String sql = "SELECT count() FROM " + qualified(str(asset.get("database_name")), str(asset.get("table_name"))) + " LIMIT 0";
        try (com.el2ck.ckpool.CkClient c = identities.openForUser(userId, instanceId)) {
            c.query(sql);
        } catch (ApiException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ApiException(409, "CK 权限核对失败: " + CkIdentityService.safeMessage(e));
        }
    }

    /** 以接收者身份核对 SELECT 已失败。连接都建立不起来时按核对失败处理,不能宣称撤权完成。 */
    public void verifyCannotRead(Map<String, Object> asset, long userId) {
        long instanceId = num(asset.get("ck_instance_id"));
        String sql = "SELECT count() FROM " + qualified(str(asset.get("database_name")), str(asset.get("table_name"))) + " LIMIT 0";
        boolean readable = false;
        com.el2ck.ckpool.CkClient c;
        try {
            c = identities.openForUser(userId, instanceId);
        } catch (RuntimeException e) {
            throw new ApiException(409, "CK 撤权核对失败: 无法以用户身份连接: " + CkIdentityService.safeMessage(e));
        }
        try {
            c.query(sql);
            readable = true;
        } catch (RuntimeException ignored) {
            // 期望路径:权限已撤销。
        } finally {
            c.close();
        }
        if (readable) {
            throw new ApiException(409, "CK 撤权核对失败: 仍存在其他授权路径，请管理员检查角色与直接授权");
        }
    }

    // =====================================================================
    // 内部
    // =====================================================================

    /**
     * 读取授权所需的表资产快照。
     *
     * @param assetId 表资产 ID
     * @return 包含实例、库表、代次、所有者和权限版本的行
     * @throws com.el2ck.exception.ApiException 资产不存在时抛出 404
     */
    public Map<String, Object> asset(long assetId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id,ck_instance_id,database_name,table_name,generation,owner_user_id,status,permission_version"
                        + " FROM el_data_assets WHERE id=?", assetId);
        if (rows.isEmpty()) throw ApiException.notFound("表资产不存在");
        return rows.get(0);
    }

    /** 已绑定账号名(不论当前状态),用于撤权/清理。 */
    private String storedUserName(long userId, long instanceId) {
        Map<String, Object> b = identities.binding(userId, instanceId);
        return b == null ? "" : str(b.get("ck_username"));
    }

    /**
     * 获取用户在该实例上可用于授权的有效 CK 账号名。
     *
     * @param userId 平台用户 ID
     * @param instanceId CK 实例 ID
     * @return 已验证处于可用状态的绑定账号
     * @throws com.el2ck.exception.ApiException 绑定缺失、停用或用户名策略不匹配时抛出 409
     */
    private String userName(long userId, long instanceId) {
        Map<String, Object> b = identities.binding(userId, instanceId);
        if (!identities.usableBinding(b)) {
            throw new ApiException(409, "用户 " + userId + " 的 CK 账号尚未就绪");
        }
        return str(b.get("ck_username"));
    }

    /**
     * 将数据库名和表名分别安全引用后拼成 CK 限定标识符。
     *
     * @param db 数据库名
     * @param table 表名
     * @return 形如 {@code `db`.`table`} 的标识符
     */
    private static String qualified(String db, String table) {
        return Sql.quoteIdent(db) + "." + Sql.quoteIdent(table);
    }

    /**
     * 从资产行读取用于乐观并发核对的权限版本。
     *
     * @param asset 资产查询行
     * @return 数值版本；缺失或非数值时返回 0
     */
    private long expectedVersion(Map<String, Object> asset) {
        Object v = asset.get("permission_version");
        return v instanceof Number ? ((Number) v).longValue() : 0L;
    }

    /**
     * 创建或重启一条 CK 授权操作记录。
     *
     * <p>operation_key 冲突时保留原记录、累加尝试次数并重置为 running，用于跨库失败后的审计和重试。
     *
     * @param key 幂等操作键
     * @param assetId 表资产 ID
     * @param userId 目标用户 ID
     * @param instanceId CK 实例 ID
     * @param action 操作类型
     * @param expectedVersion 开始操作时的权限版本
     * @return 操作记录 ID
     */
    private long begin(String key, Long assetId, Long userId, Long instanceId, String action, long expectedVersion) {
        jdbc.update("INSERT INTO el_ck_access_operations(operation_key,asset_id,user_id,ck_instance_id,action,desired_state,status,expected_version,created_by)"
                        + " VALUES(?,?,?,?,?,'active','running',?,?) ON DUPLICATE KEY UPDATE status='running',attempts=attempts+1,updated_at=CURRENT_TIMESTAMP",
                key, assetId, userId, instanceId, action, expectedVersion,
                AuthContext.get() == null ? userId : AuthContext.userId());
        return jdbc.queryForObject("SELECT id FROM el_ck_access_operations WHERE operation_key=?", Long.class, key);
    }

    /**
     * 写回 CK 授权操作终态及最近错误。
     *
     * @param operationId 操作记录 ID
     * @param status active、failed 等终态
     * @param error 可空错误摘要
     */
    private void finish(long operationId, String status, String error) {
        jdbc.update("UPDATE el_ck_access_operations SET status=?,last_error=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                status, error, operationId);
    }

    /**
     * 写入一条资源访问审计记录。
     *
     * <p>后台线程没有登录上下文时，以目标用户作为审计主体，避免产生无主体记录。
     *
     * @param action 审计动作
     * @param type 资源类型
     * @param resourceId 资源 ID
     * @param target 目标用户 ID，可为 null
     */
    private void audit(String action, String type, long resourceId, Long target) {
        AuthContext.Principal p = AuthContext.get();
        jdbc.update("INSERT INTO el_access_audit(actor_user_id,action,resource_type,resource_id,target_user_id) VALUES(?,?,?,?,?)",
                p == null ? target : p.getUserId(), action, type, resourceId, target);
    }

    /**
     * 将可空数据库值转为字符串。
     *
     * @param v 原值
     * @return null 转为空串，其余调用 {@link String#valueOf(Object)}
     */
    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    /**
     * 将 JDBC 返回的数值或数值字符串转换为 long。
     *
     * @param v 原值
     * @return 长整数值
     * @throws NumberFormatException 值不是合法数字时抛出
     */
    private static long num(Object v) {
        return v instanceof Number ? ((Number) v).longValue() : Long.parseLong(String.valueOf(v));
    }
}
