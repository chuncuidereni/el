/**
 * 【文件职责】本文件定义ck模块的业务服务 TableLifecycleService。承载该模块的核心业务规则，协调仓储、连接器或外部服务，并统一转换业务异常和操作状态。
 */

package com.el2ck.service.ck;

import com.el2ck.ckpool.CkClient;
import com.el2ck.config.AuthContext;
import com.el2ck.exception.ApiException;
import com.el2ck.service.connection.ConnectionService;
import com.el2ck.util.Sql;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.*;

/**
 * CK 表资产生命周期:建表、改名、删除的受控流程。
 *
 * <p>平台元数据与 ClickHouse 没有本地事务,所有步骤按「先记 provisioning → 受控执行 → 核对 →
 * 转 active」推进;失败保留可诊断状态,绝不把结果不确定的表当成创建成功。
 *
 * <p>表名占用以物理实例为单位(ck_instance_id + 库 + 表),多个连接别名不会重复占用;
 * 删除后把资产改名并保留 original_table_name,释放名字又保留历史。
 */
@Service
public class TableLifecycleService {

    private static final java.util.regex.Pattern CREATE_TABLE = java.util.regex.Pattern.compile(
            "(?is)^\\s*CREATE\\s+(?:OR\\s+REPLACE\\s+)?TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?"
                    + "((?:`[^`]+`|[A-Za-z_][A-Za-z0-9_]*)(?:\\s*\\.\\s*(?:`[^`]+`|[A-Za-z_][A-Za-z0-9_]*))?)");

    /** 禁止借建表语句访问外部系统或写服务器文件。 */
    private static final java.util.regex.Pattern FORBIDDEN_ENGINE = java.util.regex.Pattern.compile(
            "(?is)\\b(?:ENGINE\\s*=\\s*(?:URL|File|S3|S3Queue|HDFS|AzureBlobStorage|MySQL|PostgreSQL|MongoDB|Executable|Dictionary|Redis|Kafka)|INTO\\s+OUTFILE)\\b");

    private final JdbcTemplate jdbc;
    private final CkInstanceService instances;
    private final CkIdentityService identities;
    private final CkAccessProvisioner provisioner;
    private final ConnectionService connections;
    /**
     * 创建ClickHouse 表生命周期服务并装配后续调用所需的依赖。
     *
     * 依赖由 Spring 容器统一管理；本构造器只保存引用或初始化轻量适配对象，不执行远程业务操作。
     *
     * @param ds 平台元数据 JDBC 数据源，用于读取账号安全、归属和授权记录
     * @param instances ClickHouse 实例登记服务，用于校验实例和允许的数据库范围
     * @param identities ClickHouse 用户身份服务，用于按任务所有者或当前用户解析执行身份
     * @param provisioner ClickHouse 访问授权编排器，用于创建或撤销真实数据库权限
     * @param connections ClickHouse 连接服务，负责解析连接并按执行身份创建客户端
     */
    public TableLifecycleService(DataSource ds, CkInstanceService instances, CkIdentityService identities,
                                 CkAccessProvisioner provisioner, ConnectionService connections) {
        this.jdbc = new JdbcTemplate(ds);
        this.instances = instances;
        this.identities = identities;
        this.provisioner = provisioner;
        this.connections = connections;
    }

    // =====================================================================
    // 建表
    // =====================================================================

    /**
     * 以当前用户身份执行服务端校验过的结构化建表语句,并登记/授权表资产。
     *
     * @param connectionId 目标连接
     * @param defaultDb    未在 DDL 中限定库名时使用的库
     * @param ddl          CREATE TABLE 语句
     * @param originType   manual / sync / workflow
     * @param originId     来源任务 ID
     * @return 新资产 ID
     */
    public long create(long connectionId, String defaultDb, String ddl, String originType, Long originId) {
        long userId = AuthContext.userId();
        String statement = singleStatement(ddl);
        if (FORBIDDEN_ENGINE.matcher(statement).find()) {
            throw new ApiException(403, "该建表语句包含外部引擎或文件写入子句，平台不允许执行");
        }
        String[] location = parseCreateTarget(statement, defaultDb);
        String db = location[0];
        String table = location[1];
        instances.requireAllowedDatabase(db);
        long instanceId = instances.instanceId(connectionId);

        if (physicalExists(connectionId, db, table)) {
            throw new ApiException(409, "表名已占用");
        }
        long assetId;
        try {
            jdbc.update("INSERT INTO el_data_assets(backend_type,backend_id,database_name,table_name,owner_user_id,"
                            + "ck_instance_id,creator_user_id,creator_name_snapshot,status,origin_type,origin_id,permission_version)"
                            + " VALUES('clickhouse',?,?,?,?,?,?,?,'provisioning',?,?,0)",
                    connectionId, db, table, userId, instanceId, userId, AuthContext.get().getUsername(), originType, originId);
            assetId = jdbc.queryForObject("SELECT id FROM el_data_assets WHERE ck_instance_id=? AND database_name=? AND table_name=?",
                    Long.class, instanceId, db, table);
        } catch (DuplicateKeyException e) {
            throw new ApiException(409, "表名已占用");
        }

        boolean created = false;
        boolean createGranted = false;
        try {
            provisioner.grantCreate(connectionId, db, table, userId);
            createGranted = true;
            CkClient userClient = identities.client(connectionId);
            userClient.exec(statement);
            created = true;
            provisioner.revokeCreate(connectionId, db, table, userId);
            createGranted = false;
            if (!physicalExists(connectionId, db, table)) {
                throw new ApiException(502, "建表语句执行后未在 ClickHouse 中找到目标表");
            }
            provisioner.grantOwner(assetId);
            jdbc.update("UPDATE el_data_assets SET status='active',last_error=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=?", assetId);
            audit("table.create", assetId, userId);
            return assetId;
        } catch (RuntimeException e) {
            String error = CkIdentityService.safeMessage(e);
            if (created) {
                // 结果不确定时不自动删表:保留 failed 状态,由管理员核对后再处理。
                jdbc.update("UPDATE el_data_assets SET status='failed',last_error=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                        error, assetId);
            } else {
                jdbc.update("UPDATE el_data_assets SET status='failed',last_error=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                        error, assetId);
            }
            throw e instanceof ApiException ? (ApiException) e : new ApiException(502, "建表失败: " + error);
        } finally {
            if (createGranted) {
                try { provisioner.revokeCreate(connectionId, db, table, userId); } catch (RuntimeException ignored) { }
            }
        }
    }

    // =====================================================================
    // 删除
    // =====================================================================

    /** 已登记表清理资产和授权;未登记表仅允许内置管理员用自身 CK 业务权限删除。 */
    public void drop(long connectionId, String db, String table) {
        long userId = AuthContext.userId();
        String target = qualified(db, table);
        Map<String, Object> asset = findAsset(connectionId, db, table);
        if (asset == null) {
            if (!AuthContext.isBuiltinAdmin()) throw ApiException.notFound("表不存在或未登记");
            instances.requireAllowedDatabase(db);
            identities.requirePrivilege(connectionId, "DROP TABLE", db, table);
            try {
                // 不代用管理身份,不为删除临时认领资产,也不修改该账号原有授权。
                identities.client(connectionId).exec("DROP TABLE IF EXISTS " + target + " SYNC");
                audit("table.drop.unregistered", null, userId);
            } catch (RuntimeException e) {
                throw e instanceof ApiException ? (ApiException) e : ApiException.badGateway("删表未完成: " + Sql.messageOf(e));
            }
            return;
        }
        requireAssetOwner(asset, db);
        if (AuthContext.isBuiltinAdmin()) identities.requirePrivilege(connectionId,"DROP TABLE",db,table);
        long assetId = num(asset.get("id"));
        jdbc.update("UPDATE el_data_assets SET status='deleting',updated_at=CURRENT_TIMESTAMP WHERE id=?", assetId);
        try {
            provisioner.cleanupAsset(assetId);
            identities.managementClient(connectionId).exec("DROP TABLE IF EXISTS " + qualified(db, table) + " SYNC");
            jdbc.update("UPDATE el_resource_grants SET status='revoked',updated_at=CURRENT_TIMESTAMP"
                    + " WHERE resource_type='table' AND resource_id=?", assetId);
            jdbc.update("UPDATE el_data_assets SET status='deleted',original_table_name=COALESCE(original_table_name,table_name),"
                            + "table_name=CONCAT(table_name,'#deleted#',id),deleted_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                    assetId);
            audit("table.drop", assetId, userId);
        } catch (RuntimeException e) {
            String error = CkIdentityService.safeMessage(e);
            jdbc.update("UPDATE el_data_assets SET status='deleting',last_error=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                    error, assetId);
            throw e instanceof ApiException ? (ApiException) e : new ApiException(502, "删表未完成: " + error);
        }
    }

    // =====================================================================
    // 表注释 / 改名
    // =====================================================================

    /** 所有者可修改既有资产的表注释；兼容旧资产未授予 ALTER MODIFY COMMENT 的情况。 */
    public void modifyComment(long connectionId, String db, String table, String comment) {
        Map<String, Object> asset = requireOwnedAsset(connectionId, db, table);
        if (AuthContext.isBuiltinAdmin()) {
            identities.requirePrivilege(connectionId, "ALTER MODIFY COMMENT", db, table);
        }
        try {
            // 受控管理操作仅接受表名和文本，不接收自由 SQL，也不向用户追加数据库权限。
            identities.managementClient(connectionId).exec("ALTER TABLE " + qualified(db, table)
                    + " MODIFY COMMENT " + Sql.quoteString(comment));
            audit("table.comment", num(asset.get("id")), AuthContext.userId());
        } catch (RuntimeException e) {
            throw e instanceof ApiException ? (ApiException) e
                    : ApiException.badGateway("修改表注释失败: " + Sql.messageOf(e));
        }
    }

    /** 受控改名:同步迁移授权对象名,保持资产 ID 与共享关系不变。 */
    public void rename(long connectionId, String db, String table, String newTable) {
        long userId = AuthContext.userId();
        Map<String, Object> asset = requireOwnedAsset(connectionId, db, table);
        if (AuthContext.isBuiltinAdmin()) {
            identities.requirePrivilege(connectionId,"SELECT, DROP TABLE",db,table);
            identities.requirePrivilege(connectionId,"CREATE TABLE, INSERT",db,newTable);
        }
        long assetId = num(asset.get("id"));
        long instanceId = num(asset.get("ck_instance_id"));
        String target = newTable == null ? "" : newTable.trim();
        newTable = Sql.quoteIdent(target);
        if (newTable.equals(table)) return;
        if (jdbc.queryForObject("SELECT COUNT(*) FROM el_data_assets WHERE ck_instance_id=? AND database_name=? AND table_name=? AND id<>?",
                Long.class, instanceId, db, newTable, assetId) > 0) {
            throw new ApiException(409, "表名已占用");
        }
        if (physicalExists(connectionId, db, newTable)) throw new ApiException(409, "表名已占用");
        int generation = ((Number) asset.get("generation")).intValue();
        String role = CkAccessProvisioner.readRole(assetId, generation);
        long owner = num(asset.get("owner_user_id"));
        jdbc.update("UPDATE el_data_assets SET status='changing',updated_at=CURRENT_TIMESTAMP WHERE id=?", assetId);
        try {
            CkClient mgmt = identities.managementClientForInstance(instanceId);
            String oldQ = qualified(db, table);
            String newQ = qualified(db, newTable);
            String ownerName = bindingName(owner, instanceId);
            try { mgmt.exec("REVOKE " + "SELECT, SHOW" + " ON " + oldQ + " FROM " + role); } catch (RuntimeException ignored) { }
            if (!identities.usesConfiguredAccount(owner,instanceId)) {
                try { mgmt.exec("REVOKE " + "INSERT, ALTER UPDATE, ALTER DELETE, ALTER ADD COLUMN, ALTER DROP COLUMN, "
                        + "ALTER MODIFY COLUMN, ALTER RENAME COLUMN, TRUNCATE, OPTIMIZE" + " ON " + oldQ + " FROM " + ownerName); } catch (RuntimeException ignored) { }
            }
            mgmt.exec("RENAME TABLE " + oldQ + " TO " + newQ);
            mgmt.exec("GRANT SELECT, SHOW ON " + newQ + " TO " + role);
            if (!identities.usesConfiguredAccount(owner,instanceId)) {
                mgmt.exec("GRANT INSERT, ALTER UPDATE, ALTER DELETE, ALTER ADD COLUMN, ALTER DROP COLUMN, "
                        + "ALTER MODIFY COLUMN, ALTER RENAME COLUMN, TRUNCATE, OPTIMIZE ON " + newQ + " TO " + ownerName);
            }
            jdbc.update("UPDATE el_data_assets SET table_name=?,status='active',last_error=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                    newTable, assetId);
            for (Long grantee : jdbc.queryForList("SELECT grantee_user_id FROM el_resource_grants"
                    + " WHERE resource_type='table' AND resource_id=? AND status='active'", Long.class, assetId)) {
                Map<String, Object> updated = provisioner.asset(assetId);
                provisioner.verifyRead(updated, grantee);
            }
            audit("table.rename", assetId, userId);
        } catch (RuntimeException e) {
            String error = CkIdentityService.safeMessage(e);
            jdbc.update("UPDATE el_data_assets SET status='changing',last_error=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                    error, assetId);
            throw e instanceof ApiException ? (ApiException) e : new ApiException(502, "改名未完成: " + error);
        }
    }

    // =====================================================================
    // 同步/工作流目标登记
    // =====================================================================

    /**
     * 同步或工作流写入前登记目标。已存在且归属当前用户的表直接复用;不存在则登记为 provisioning,
     * 由引擎以用户身份自动建表后再调用 {@link #activateTarget}。
     */
    public long registerTarget(long connectionId, String db, String table, String originType, Long originId) {
        long userId = AuthContext.userId();
        instances.requireAllowedDatabase(db);
        Sql.quoteIdent(db);
        Sql.quoteIdent(table);
        long instanceId = instances.instanceId(connectionId);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id,owner_user_id,status FROM el_data_assets WHERE ck_instance_id=? AND database_name=? AND table_name=?",
                instanceId, db, table);
        if (!rows.isEmpty()) {
            long id = num(rows.get(0).get("id"));
            long owner = num(rows.get(0).get("owner_user_id"));
            if (owner != userId) throw new ApiException(409, "表名已占用");
            String status = str(rows.get(0).get("status"));
            if ("deleted".equals(status)) throw new ApiException(409, "表名已占用");
            return id;
        }
        try {
            jdbc.update("INSERT INTO el_data_assets(backend_type,backend_id,database_name,table_name,owner_user_id,"
                            + "ck_instance_id,creator_user_id,creator_name_snapshot,status,origin_type,origin_id,permission_version)"
                            + " VALUES('clickhouse',?,?,?,?,?,?,?,'provisioning',?,?,0)",
                    connectionId, db, table, userId, instanceId, userId, AuthContext.get().getUsername(), originType, originId);
        } catch (DuplicateKeyException e) {
            throw new ApiException(409, "表名已占用");
        }
        return jdbc.queryForObject("SELECT id FROM el_data_assets WHERE ck_instance_id=? AND database_name=? AND table_name=?",
                Long.class, instanceId, db, table);
    }

    /** 同步自动建表只复用本任务自己的预留，不认领同用户其他流程的同名资产。 */
    public long reserveSyncTarget(long connectionId, String db, String table, long taskId) {
        long assetId=registerTarget(connectionId,db,table,"sync",taskId);
        // 名字已登记给同一用户的其他任务/工作流也不能复用其建表预留。
        // 同任务重试只接回原预留，不以“同所有者”推断此次创建权。
        List<Map<String,Object>> rows=jdbc.queryForList(
                "SELECT origin_type,origin_id FROM el_data_assets WHERE id=?",assetId);
        if (rows.size()!=1 || !"sync".equals(str(rows.get(0).get("origin_type")))
                || num(rows.get(0).get("origin_id"))!=taskId)
            throw new ApiException(409,"该表名已由其他任务或工作流预留，请更换目标表名");
        return assetId;
    }

    /** 目标表建好后授予所有者读写并转 active。 */
    public void activateTarget(long assetId) {
        provisioner.grantOwner(assetId);
        jdbc.update("UPDATE el_data_assets SET status='active',last_error=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=?", assetId);
    }

    /** 目标登记后执行失败:保留 failed 记录,不认领物理对象。 */
    public void failTarget(long assetId, String error) {
        jdbc.update("UPDATE el_data_assets SET status='failed',last_error=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                error == null ? "" : (error.length() > 450 ? error.substring(0, 450) : error), assetId);
    }

    /** 补偿删除本次失败创建留下的半成品表(仅精确表名,由管理身份执行)。 */
    public void dropPartial(long connectionId, String db, String table) {
        try {
            identities.managementClient(connectionId).exec("DROP TABLE IF EXISTS " + qualified(db, table) + " SYNC");
        } catch (RuntimeException ignored) {
            // 补偿失败不覆盖原始错误;表资产已标记 failed,由管理员核对。
        }
    }

    /** 同步自动建表前临时授予精确 CREATE TABLE。 */
    public void grantCreate(long connectionId, String db, String table) {
        provisioner.grantCreate(connectionId, db, table, AuthContext.userId());
    }

    /** 同步自动建表后收回临时权限。 */
    public void revokeCreate(long connectionId, String db, String table) {
        provisioner.revokeCreate(connectionId, db, table, AuthContext.userId());
    }

    // =====================================================================
    // 内部
    // =====================================================================

    /**
     * 查找物理表对应的资产并校验当前用户所有权。
     *
     * @param connectionId 外部连接 ID
     * @param db 数据库名
     * @param table 表名
     * @return 已通过所有权校验的资产
     * @throws com.el2ck.common.ApiException 资产不存在时为 404，无所有权时为 403
     */
    private Map<String, Object> requireOwnedAsset(long connectionId, String db, String table) {
        Map<String, Object> asset = findAsset(connectionId, db, table);
        if (asset == null) throw ApiException.notFound("表不存在或未登记");
        requireAssetOwner(asset, db);
        return asset;
    }

    /**
     * 按 CK 实例、库名和表名查找数据资产记录。
     *
     * @param connectionId 外部连接 ID
     * @param db 数据库名
     * @param table 表名
     * @return 首条匹配资产；不存在时为 null
     */
    private Map<String, Object> findAsset(long connectionId, String db, String table) {
        long instanceId = instances.instanceId(connectionId);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id,ck_instance_id,generation,owner_user_id,status FROM el_data_assets"
                        + " WHERE ck_instance_id=? AND database_name=? AND table_name=?", instanceId, db, table);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 校验当前用户是否有权执行表生命周期操作。
     *
     * <p>资产所有者始终通过；内置管理员只有在目标库属于实例允许数据库时才通过。
     * 其它情况抛出 403，不改变资产状态。
     *
     * @param asset 数据资产记录
     * @param db 目标数据库
     */
    private void requireAssetOwner(Map<String, Object> asset, String db) {
        if (num(asset.get("owner_user_id")) != AuthContext.userId()
                && !(AuthContext.isBuiltinAdmin() && instances.allowedDatabases().contains(db))) {
            throw new ApiException(403, "只有所有者可以执行该生命周期操作");
        }
    }

    /**
     * 获取所有者在该 CK 实例上的可用账号名。
     *
     * @param userId 所有者用户 ID
     * @param instanceId CK 实例 ID
     * @return CK 用户名
     * @throws com.el2ck.common.ApiException 绑定不存在、不可用或状态冲突时抛出 409
     */
    private String bindingName(long userId, long instanceId) {
        Map<String, Object> b = identities.binding(userId, instanceId);
        if (!identities.usableBinding(b)) {
            throw new ApiException(409, "所有者 CK 账号尚未就绪");
        }
        return str(b.get("ck_username"));
    }

    /** 物理表是否存在(管理身份)。 */
    public boolean physicalExists(long connectionId, String db, String table) {
        try {
            com.el2ck.ckpool.QueryResult res = identities
                    .managementClientForInstance(instances.instanceId(connectionId))
                    .query("EXISTS TABLE " + qualified(db, table));
            return !res.getRows().isEmpty() && !res.getRows().get(0).isEmpty()
                    && num(res.getRows().get(0).get(0)) != 0;
        } catch (RuntimeException e) {
            throw ApiException.badGateway("检查目标表失败: " + Sql.messageOf(e));
        }
    }

    /**
     * 拆分并校验建表 DDL，要求其中恰有一条非空语句。
     *
     * @param ddl 用户提交的 DDL
     * @return 去除首尾空白的单条语句
     * @throws com.el2ck.common.ApiException 空内容或多语句时抛出 400
     */
    private static String singleStatement(String ddl) {
        List<String> statements = Sql.splitStatements(ddl == null ? "" : ddl);
        List<String> nonEmpty = new ArrayList<>();
        for (String s : statements) if (s != null && !s.trim().isEmpty()) nonEmpty.add(s.trim());
        if (nonEmpty.size() != 1) throw ApiException.validation("建表请求只能包含一条 CREATE TABLE 语句");
        return nonEmpty.get(0);
    }

    /** 从 CREATE TABLE 语句解析 [库, 表];未限定库时使用默认库。 */
    static String[] parseCreateTarget(String ddl, String defaultDb) {
        java.util.regex.Matcher m = CREATE_TABLE.matcher(ddl);
        if (!m.find()) throw ApiException.validation("只支持 CREATE TABLE 建表语句");
        String raw = m.group(1).replace("`", "").replaceAll("\\s+", "");
        int dot = raw.indexOf('.');
        String db = dot < 0 ? defaultDb : raw.substring(0, dot);
        String table = dot < 0 ? raw : raw.substring(dot + 1);
        if (!Sql.validIdent(db) || !Sql.validIdent(table)) {
            throw ApiException.validation("建表语句中的库名或表名非法");
        }
        return new String[]{db, table};
    }

    /**
     * 分别引用库名和表名，生成 ClickHouse 全限定标识符。
     *
     * @param db 数据库名
     * @param table 表名
     * @return 安全的全限定表名
     */
    private static String qualified(String db, String table) {
        return Sql.quoteIdent(db) + "." + Sql.quoteIdent(table);
    }

    /**
     * 记录一次表生命周期审计，操作者和目标均为资产所有者。
     *
     * @param action 审计动作
     * @param assetId 资产 ID，可为空
     * @param userId 资产所有者 ID
     */
    private void audit(String action, Long assetId, long userId) {
        jdbc.update("INSERT INTO el_access_audit(actor_user_id,action,resource_type,resource_id,target_user_id) VALUES(?,?,?,?,?)",
                userId, action, "table", assetId, userId);
    }

    /**
     * 将可空数据库值转为字符串，null 转为空串。
     *
     * @param v 原始值
     * @return 字符串表示
     */
    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    /**
     * 将数据库数值或十进制字符串转换为 long。
     *
     * @param v 原始值
     * @return long 值
     * @throws NumberFormatException 非数值字符串
     */
    private static long num(Object v) {
        return v instanceof Number ? ((Number) v).longValue() : Long.parseLong(String.valueOf(v));
    }
}
