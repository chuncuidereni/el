/**
 * 【文件职责】本文件定义ck模块的业务服务 CkIdentityService。承载该模块的核心业务规则，协调仓储、连接器或外部服务，并统一转换业务异常和操作状态。
 */

package com.el2ck.service.ck;

import com.el2ck.ckpool.CkClient;
import com.el2ck.ckpool.CkConfig;
import com.el2ck.ckpool.CkPool;
import com.el2ck.config.AppConfig;
import com.el2ck.config.AuthContext;
import com.el2ck.exception.ApiException;
import com.el2ck.service.connection.ConnectionService;
import com.el2ck.util.Crypto;
import com.el2ck.util.Sql;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.security.SecureRandom;
import java.util.*;

/**
 * 平台用户与真实 ClickHouse 账号的绑定、凭据解析和业务连接供给。
 *
 * <p>设计要点(见 docs/CK真实用户与表级权限开发方案.md):
 * <ul>
 *   <li>业务 SQL 只使用当前登录用户绑定的 CK 账号,任何异常分支都不回退到 default/公共账号;</li>
 *   <li>账号/授权管理使用独立的配置化管理身份({@code app.ck-management}),与业务连接严格分开;</li>
 *   <li>连接池键包含用户、绑定与凭据版本,禁用/轮换后可整体失效;</li>
 *   <li>绑定状态 pending/active/failed/disabling/disabled,失败保留脱敏原因并可重试。</li>
 * </ul>
 */
@Service
public class CkIdentityService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final CkInstanceService instances;
    private final ConnectionService connections;
    private final CkPool pool;
    private final AppConfig config;

    /**
     * 注入平台元数据库、CK 实例/连接配置、连接池和应用配置。
     *
     * @param ds 平台元数据库
     * @param instances CK 实例登记服务
     * @param connections 连接配置服务，使用延迟代理避免依赖环
     * @param pool 业务身份连接池
     * @param config 密钥等应用配置
     */
    public CkIdentityService(DataSource ds, CkInstanceService instances,
                             @org.springframework.context.annotation.Lazy ConnectionService connections,
                             CkPool pool, AppConfig config) {
        this.jdbc = new JdbcTemplate(ds);
        this.instances = instances;
        this.connections = connections;
        this.pool = pool;
        this.config = config;
    }

    // =====================================================================
    // 业务身份
    // =====================================================================

    /** 当前登录用户在该连接所属实例上的有效绑定;未初始化/失败/禁用一律拒绝。 */
    public Map<String, Object> requireBinding(long connectionId) {
        long userId = AuthContext.userId();
        long instanceId = instances.instanceId(connectionId);
        Map<String, Object> b = binding(userId, instanceId);
        if (AuthContext.isBuiltinAdmin() && !usableBinding(b)) {
            initialize(userId, instanceId);
            b = binding(userId, instanceId);
        }
        if (!usableBinding(b)) {
            String reason = b == null ? "尚未初始化" : str(b.get("status"));
            throw new ApiException(403, "当前账号在该 ClickHouse 实例上不可用(" + reason + ")，请联系管理员初始化或重试");
        }
        return b;
    }

    /** 当前用户的业务连接配置:主机/端口/超时来自连接,账号口令来自绑定。 */
    public CkConfig config(long connectionId) {
        Map<String, Object> b = requireBinding(connectionId);
        CkConfig base = connections.managementConfig(connectionId).copy();
        base.setUsername(str(b.get("ck_username")));
        try {
            base.setPassword(Crypto.decrypt(config.getSecretKey(), str(b.get("credential_ciphertext"))));
        } catch (RuntimeException e) {
            throw new ApiException(403, "ClickHouse 绑定凭据不可用，请联系管理员重新初始化");
        }
        return base;
    }

    /** 当前用户的业务客户端(按用户+绑定+凭据版本缓存)。 */
    public CkClient client(long connectionId) {
        Map<String, Object> b = requireBinding(connectionId);
        CkConfig cfg = config(connectionId);
        String key = "u:" + AuthContext.userId() + ":c:" + connectionId + ":b:" + num(b.get("id"))
                + ":v" + num(b.get("credential_version"));
        try {
            return pool.get(key, cfg);
        } catch (RuntimeException e) {
            // HTTP 状态及安全文案不变；同步恢复仍需底层网络异常/SQLState 判定是否重试。
            throw new ApiException(502, Sql.messageOf(e), e);
        }
    }

    /** 以业务账号核对管理员受控 DDL 的前置权限,不能借管理连接提升权限。 */
    public void requirePrivilege(long connectionId, String privilege, String db, String table) {
        List<List<Object>> rows = client(connectionId).query("CHECK GRANT " + privilege + " ON "
                + Sql.quoteIdent(db) + "." + Sql.quoteIdent(table)).getRows();
        if (rows.isEmpty() || rows.get(0).isEmpty() || !"1".equals(str(rows.get(0).get(0))))
            throw new ApiException(403,"当前 CK 业务账号没有该操作所需的权限");
    }

    /** 当前用户的专用连接(导出/取消查询等长操作),用完由调用方关闭。 */
    public CkClient openDedicated(long connectionId, String database) {
        CkConfig cfg = config(connectionId);
        String db = database == null ? "" : database.trim();
        if (!db.isEmpty() && !db.equals(cfg.getDatabase())) {
            Sql.quoteIdent(db);
            cfg.setDatabase(db);
        }
        try {
            return CkClient.open(cfg);
        } catch (RuntimeException e) {
            throw new ApiException(502, Sql.messageOf(e), e);
        }
    }

    /** 当前连接对应实例的管理身份用户名。 */
    public String managementUsername(long connectionId) {
        return managementConfigForInstance(instances.instanceId(connectionId)).getUsername();
    }

    /** 指定用户在某实例上的业务配置(供授权核对使用,不依赖当前请求身份)。 */
    public CkConfig configForUser(long userId, long instanceId) {
        Map<String, Object> b = binding(userId, instanceId);
        if (!usableBinding(b)) {
            throw new ApiException(409, "目标用户的 CK 账号尚未就绪，无法完成授权");
        }
        CkConfig base = connections.managementConfig(instances.managementConnectionId(instanceId)).copy();
        base.setUsername(str(b.get("ck_username")));
        try {
            base.setPassword(Crypto.decrypt(config.getSecretKey(), str(b.get("credential_ciphertext"))));
        } catch (RuntimeException e) {
            throw new ApiException(409, "目标用户的 CK 绑定凭据不可用，请先重试初始化");
        }
        return base;
    }

    /** 以指定用户身份打开临时连接(授权核对/撤权核对),用完由调用方关闭。 */
    public CkClient openForUser(long userId, long instanceId) {
        try {
            return CkClient.open(configForUser(userId, instanceId));
        } catch (RuntimeException e) {
            throw ApiException.badGateway(Sql.messageOf(e));
        }
    }
    /** 业务身份是否已就绪;用于列表展示,不抛异常。 */
    public boolean ready(long userId, long connectionId) {
        try {
            Map<String, Object> b = binding(userId, instances.instanceId(connectionId));
            return usableBinding(b);
        } catch (RuntimeException e) {
            return false;
        }
    }

    // =====================================================================
    // 管理身份
    // =====================================================================

    /** 配置化管理身份连接指定连接对应的实例。 */
    public CkClient managementClient(long connectionId) {
        return managementClientForInstance(instances.instanceId(connectionId));
    }

    /** 续传内部固定设置：仅允许本来就可写入和清理目标的账号启用去重，不授予通用 ALTER SETTINGS。 */
    public void enableCheckpointDedup(long connectionId,String database,String table) {
        requirePrivilege(connectionId,"INSERT",database,table);
        requirePrivilege(connectionId,"ALTER DELETE",database,table);
        managementClient(connectionId).exec("ALTER TABLE "+Sql.quoteIdent(database)+"."+Sql.quoteIdent(table)
                +" MODIFY SETTING non_replicated_deduplication_window=20000");
    }

    /**
     * 续传专用的旧写入终止操作。25.3 的 KILL QUERY 会读取 system.processes，不能要求
     * 隔离的业务账号获得其他用户查询文本的读取权限。此处使用配置管理身份，但 SQL
     * 只能由服务端生成，并同时限定当前有效绑定账号与严格校验的断点 token。
     * 不接收任意 SQL，不改变业务账号权限；调用方必须持有任务锁和目标表断点锁。
     */
    public void stopCheckpointWrites(long connectionId,String token) {
        if(token==null) return;
        if(!token.matches("el2ck_sync_[a-f0-9]{32}")) throw new IllegalStateException("断点写入标识非法");
        Map<String,Object> current=requireBinding(connectionId);
        String username=str(current.get("ck_username"));
        if(username.isEmpty()) throw new IllegalStateException("当前 ClickHouse 业务身份为空，不能停止旧写入");
        try {
            CkClient management=managementClient(connectionId);
            String marker="position(query, '/*"+token+"*/') > 0 AND position(query, 'KILL QUERY') = 0";
            // 若历史绑定或任务归属改变，不能把其他账号尚在执行的旧写入误判为“已经停止”。
            com.el2ck.ckpool.QueryResult active=management.query("SELECT query_id, user, query FROM system.processes WHERE "+marker);
            int userColumn=active.getColumns().indexOf("user");
            for(List<Object> row:active.getRows()) {
                if(userColumn<0 || userColumn>=row.size() || !username.equals(str(row.get(userColumn))))
                    throw new IllegalStateException("旧写入身份与当前绑定不一致，已保留断点，请由管理员核对旧运行");
            }
            com.el2ck.ckpool.QueryResult result=management.query(
                    "KILL QUERY WHERE user = "+Sql.quoteString(username)
                    +" AND "+marker+" SYNC");
            int status=result.getColumns().indexOf("kill_status");
            for(List<Object> row:result.getRows()) {
                if(status<0 || status>=row.size() || !"finished".equals(String.valueOf(row.get(status))))
                    throw new IllegalStateException("旧写入尚未确认停止，保留断点，请稍后继续传输");
            }
        } catch(RuntimeException e) {
            throw new IllegalStateException("无法确认旧写入已停止，断点已保留；请检查配置的 CK 管理账号连接及 "
                    +"SELECT(query_id, user, query) ON system.processes、KILL QUERY ON *.* 权限后继续传输: "
                    +safeMessage(e),e);
        }
    }

    /** 工作流内部 DDL 使用部署管理身份；调用方必须先核对源表和目标资产权限。 */
    public CkConfig workflowManagementConfig(long connectionId) {
        requireBinding(connectionId);
        return managementConfigForInstance(instances.instanceId(connectionId));
    }

    /** 配置化管理身份连接指定实例。 */
    public CkClient managementClientForInstance(long instanceId) {
        CkConfig cfg = managementConfigForInstance(instanceId);
        String key = "c:" + instances.managementConnectionId(instanceId) + ":mgmt";
        try {
            return pool.get(key, cfg);
        } catch (RuntimeException e) {
            throw new ApiException(502, "CK 管理身份连接失败: " + Sql.messageOf(e), e);
        }
    }

    /** 管理身份配置:地址来自实例连接,账号口令只来自部署配置。 */
    public CkConfig managementConfigForInstance(long instanceId) {
        if (!config.getCkManagement().isEnabled()) {
            throw new ApiException(409, "真实 CK 账号与授权管理未启用(app.ck-management.enabled=false)");
        }
        String username = config.getCkManagement().getUsername();
        if (username == null || username.trim().isEmpty()) {
            throw new ApiException(409, "未配置 CK 管理身份(app.ck-management.username/password)");
        }
        long connectionId = instances.managementConnectionId(instanceId);
        CkConfig cfg = connections.managementConfig(connectionId).copy();
        cfg.setUsername(username.trim());
        cfg.setPassword(config.getCkManagement().getPassword() == null ? "" : config.getCkManagement().getPassword());
        return cfg;
    }

    // =====================================================================
    // 绑定初始化 / 禁用 / 启用
    // =====================================================================

    /** 升级时仅补建启用用户缺失的绑定，不重置已有绑定的密码或状态。 */
    public void initializeMissingBindings() {
        for (Map<String,Object> instance : instances.list()) {
            long instanceId = num(instance.get("instanceId"));
            List<Long> missing = jdbc.queryForList("SELECT u.id FROM app_users u"
                    + " LEFT JOIN el_user_security s ON s.user_id=u.id"
                    + " WHERE COALESCE(s.enabled,TRUE)=TRUE AND NOT EXISTS"
                    + " (SELECT 1 FROM el_user_ck_bindings b WHERE b.user_id=u.id AND b.ck_instance_id=?)",
                    Long.class, instanceId);
            for (Long userId : missing) {
                try { initialize(userId, instanceId); }
                catch (RuntimeException e) {
                    org.slf4j.LoggerFactory.getLogger(CkIdentityService.class)
                            .warn("CK 绑定初始化待重试 userId={}, instanceId={}", userId, instanceId);
                }
            }
        }
    }

    /** 为一条平台用户在所有已登记实例上初始化 CK 账号。 */
    public List<Map<String, Object>> initializeAll(long userId) {
        try {
            instances.syncInstances();
        } catch (RuntimeException e) {
            // 实例登记失败不应阻断平台建号;用户列表会显示没有可用绑定,管理员稍后重试。
            return Collections.emptyList();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> instance : instances.list()) {
            out.add(initialize(userId, num(instance.get("instanceId"))));
        }
        return out;
    }

    /**
     * 初始化(或重试)一个绑定。CK 侧失败不抛出到平台账号创建流程,而是落 failed 状态并返回原因。
     */
    public synchronized Map<String, Object> initialize(long userId, long instanceId) {
        Long enabled = jdbc.queryForObject("SELECT COUNT(*) FROM app_users u LEFT JOIN el_user_security s ON s.user_id=u.id"
                + " WHERE u.id=? AND COALESCE(s.enabled,TRUE)=TRUE", Long.class, userId);
        if (enabled == null || enabled == 0) throw new ApiException(409, "用户不存在或已禁用，不能初始化 CK 账号");
        Map<String, Object> existing = binding(userId, instanceId);
        if (isAdminAccount(userId)) return initializeConfiguredAdmin(userId, instanceId, existing);
        if (usableBinding(existing)) return bindingView(num(existing.get("id")));
        String username = ckUsername(userId);
        boolean replacingShared = existing != null && !username.equals(str(existing.get("ck_username")));
        long version = existing == null ? 1 : num(existing.get("credential_version")) + 1;
        long bindingId;
        if (existing == null) {
            jdbc.update("INSERT INTO el_user_ck_bindings(user_id,ck_instance_id,ck_username,credential_ciphertext,credential_version,status)"
                    + " VALUES(?,?,?,?,?,'pending')", userId, instanceId, username, "", version);
            bindingId = jdbc.queryForObject("SELECT id FROM el_user_ck_bindings WHERE user_id=? AND ck_instance_id=?",
                    Long.class, userId, instanceId);
        } else {
            bindingId = num(existing.get("id"));
            jdbc.update("UPDATE el_user_ck_bindings SET status='pending',last_error=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=?", bindingId);
        }
        invalidateUser(userId);
        long operationId = operation("binding:" + bindingId + ":v" + version, userId, instanceId, "create-user");
        String password = newPassword();
        String ciphertext = Crypto.encrypt(config.getSecretKey(), password);
        try {
            CkClient mgmt = managementClientForInstance(instanceId);
            // 原共享账号的密码与授权不做任何修改;新业务身份必须是平台独立创建的账号。
            boolean canRotate = existing != null && !replacingShared
                    && (!str(existing.get("ck_user_uuid")).isEmpty()
                        || "disabled".equals(str(existing.get("status")))
                        || "disabling".equals(str(existing.get("status"))));
            if (canRotate) {
                mgmt.exec("ALTER USER " + quoteName(username) + " IDENTIFIED WITH sha256_password BY " + Sql.quoteString(password));
            } else {
                // 不使用 IF NOT EXISTS,同名外部用户或不确定的旧操作不能被自动认领。
                mgmt.exec("CREATE USER " + quoteName(username) + " IDENTIFIED WITH sha256_password BY "
                        + Sql.quoteString(password) + " DEFAULT ROLE NONE");
            }
            List<List<Object>> ids = mgmt.query("SELECT toString(id) FROM system.users WHERE name=?",
                    Collections.<Object>singletonList(username)).getRows();
            if (ids.size() != 1) throw new ApiException(409, "无法核对新建 CK 用户");
            jdbc.update("UPDATE el_user_ck_bindings SET ck_username=?,ck_user_uuid=?,credential_ciphertext=?,credential_version=?,"
                    + "updated_at=CURRENT_TIMESTAMP WHERE id=?", username, str(ids.get(0).get(0)), ciphertext, version, bindingId);
            verifyUser(instanceId, username, password);
            restoreTablePermissions(userId, instanceId, username, password);
            jdbc.update("UPDATE el_user_ck_bindings SET status='active',last_error=NULL,"
                    + "last_verified_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE id=?", bindingId);
            finishOperation(operationId, "active", null);
            invalidateUser(userId);
            audit("ck.binding.create", "ck-binding", bindingId, userId);
        } catch (RuntimeException e) {
            String error = safeMessage(e);
            jdbc.update("UPDATE el_user_ck_bindings SET status='failed',last_error=?,updated_at=CURRENT_TIMESTAMP WHERE id=?", error, bindingId);
            finishOperation(operationId, "failed", error);
        }
        return bindingView(bindingId);
    }

    /** 切换独立身份时仅恢复本人及有效共享表,不会复制旧共享账号的库级权限。 */
    private void restoreTablePermissions(long userId, long instanceId, String username, String password) {
        List<Map<String,Object>> assets = jdbc.queryForList("SELECT a.id,a.generation,a.database_name,a.table_name,a.owner_user_id"
                + " FROM el_data_assets a WHERE a.ck_instance_id=? AND a.status='active' AND (a.owner_user_id=?"
                + " OR EXISTS(SELECT 1 FROM el_resource_grants g WHERE g.resource_type='table' AND g.resource_id=a.id"
                + " AND g.grantee_user_id=? AND g.status='active')) ORDER BY a.id", instanceId, userId, userId);
        CkClient mgmt = managementClientForInstance(instanceId);
        Set<String> roles = new LinkedHashSet<>();
        for (Map<String,Object> a : assets) {
            String role = CkAccessProvisioner.readRole(num(a.get("id")), (int)num(a.get("generation")));
            String table = Sql.quoteIdent(str(a.get("database_name"))) + "." + Sql.quoteIdent(str(a.get("table_name")));
            mgmt.exec("GRANT " + role + " TO " + quoteName(username));
            if (num(a.get("owner_user_id")) == userId) {
                mgmt.exec("GRANT " + CkAccessProvisioner.WRITE_PRIVILEGES + " ON " + table + " TO " + quoteName(username));
            }
            roles.add(role);
        }
        mgmt.exec("SET DEFAULT ROLE " + (roles.isEmpty() ? "NONE" : String.join(",", roles)) + " TO " + quoteName(username));
        CkConfig cfg = connections.managementConfig(instances.managementConnectionId(instanceId)).copy();
        cfg.setUsername(username); cfg.setPassword(password);
        try (CkClient c = CkClient.open(cfg)) {
            for (Map<String,Object> a : assets) {
                c.query("SELECT * FROM " + Sql.quoteIdent(str(a.get("database_name"))) + "."
                        + Sql.quoteIdent(str(a.get("table_name"))) + " LIMIT 0");
            }
        }
    }

    /** 内置 admin 使用配置的业务账号;普通用户使用独立账号,两者均不回退到管理身份。 */
    public boolean usableBinding(Map<String,Object> b) {
        return b != null && "active".equals(str(b.get("status")))
                && expectedUsername(num(b.get("user_id")),num(b.get("ck_instance_id"))).equals(str(b.get("ck_username")));
    }

    /**
     * 判断用户是否为内置管理员账号。
     *
     * @param userId 平台用户 ID
     * @return 用户名为 admin 且角色为 admin 时为 true
     */
    private boolean isAdminAccount(long userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM app_users WHERE id=? AND username='admin' AND role='admin'", Long.class, userId) > 0;
    }

    /**
     * 根据用户角色计算其在指定实例上应绑定的 CK 用户名。
     *
     * <p>内置管理员使用实例配置的业务账号，普通用户使用平台生成的独立账号。
     *
     * @param userId 平台用户 ID
     * @param instanceId CK 实例 ID
     * @return 当前策略要求的 CK 用户名
     */
    private String expectedUsername(long userId, long instanceId) {
        return isAdminAccount(userId)
                ? str(connections.managementConfig(instances.managementConnectionId(instanceId)).getUsername())
                : ckUsername(userId);
    }

    /** 配置绑定的外部账号不归平台创建;即使禁用或更换角色,也不能修改其原生授权。 */
    public boolean usesConfiguredAccount(long userId, long instanceId) {
        Map<String,Object> b = binding(userId, instanceId);
        String username = b == null ? "" : str(b.get("ck_username"));
        return !username.isEmpty() && !ckUsername(userId).equals(username);
    }

    /**
     * 初始化或刷新内置管理员对配置 CK 业务账号的绑定。
     *
     * <p>仅当用户名和已解密密码均与配置一致时复用 active 绑定；否则重新验证并更新凭据版本。
     *
     * @param userId 内置管理员用户 ID
     * @param instanceId CK 实例 ID
     * @param existing 现有绑定行，可为 null
     * @return 最新的绑定视图
     */
    private Map<String,Object> initializeConfiguredAdmin(long userId, long instanceId, Map<String,Object> existing) {
        CkConfig cfg = connections.managementConfig(instances.managementConnectionId(instanceId));
        String username = str(cfg.getUsername());
        String password = cfg.getPassword() == null ? "" : cfg.getPassword();
        if (username.isEmpty()) throw new ApiException(409,"请配置管理员使用的 CK 业务账号（例如 el-admin）");
        long version = existing == null ? 1 : num(existing.get("credential_version")) + 1;
        if (existing != null && "active".equals(str(existing.get("status"))) && username.equals(str(existing.get("ck_username")))) {
            try {
                if (password.equals(Crypto.decrypt(config.getSecretKey(),str(existing.get("credential_ciphertext")))))
                    return bindingView(num(existing.get("id")));
            } catch (RuntimeException ignored) { /* 凭据变更时重新核对配置 */ }
        }
        if (existing == null) {
            jdbc.update("INSERT INTO el_user_ck_bindings(user_id,ck_instance_id,ck_username,credential_ciphertext,status) VALUES(?,?,?,'','pending')",
                    userId,instanceId,username);
            existing = binding(userId,instanceId);
        }
        long id = num(existing.get("id"));
        jdbc.update("UPDATE el_user_ck_bindings SET status='pending',last_error=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=?",id);
        invalidateUser(userId);
        try {
            verifyUser(instanceId,username,password);
            jdbc.update("UPDATE el_user_ck_bindings SET ck_username=?,ck_user_uuid=NULL,credential_ciphertext=?,credential_version=?,"
                    + "status='active',last_error=NULL,last_verified_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                    username,Crypto.encrypt(config.getSecretKey(),password),version,id);
            audit("ck.binding.configured-admin","ck-binding",id,userId);
        } catch (RuntimeException e) {
            jdbc.update("UPDATE el_user_ck_bindings SET status='failed',last_error=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",safeMessage(e),id);
        }
        return bindingView(id);
    }

    /** 管理员按绑定 ID 重试。 */
    public Map<String, Object> retry(long bindingId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT user_id,ck_instance_id FROM el_user_ck_bindings WHERE id=?", bindingId);
        if (rows.isEmpty()) throw ApiException.notFound("绑定记录不存在");
        return initialize(num(rows.get(0).get("user_id")), num(rows.get(0).get("ck_instance_id")));
    }

    /** 平台禁用用户时轮换 CK 凭据;CK 失败保留 disabling 状态,不阻断平台侧禁用。 */
    public void disableAll(long userId) {
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT id,ck_instance_id,ck_username,credential_version FROM el_user_ck_bindings WHERE user_id=?", userId)) {
            disableRow(userId, row);
        }
        audit("ck.binding.disable", "user", userId, userId);
    }

    /** 对仍处于 disabling 的绑定重试 CK 侧禁用(后台对账调用)。 */
    public void retryDisabling(long userId) {
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT id,ck_instance_id,ck_username,credential_version FROM el_user_ck_bindings"
                        + " WHERE user_id=? AND status='disabling'", userId)) {
            disableRow(userId, row);
        }
    }

    /**
     * 禁用一条用户 CK 绑定。
     *
     * <p>平台生成的账号会轮换密码、撤销角色并核对登录失败；外部配置账号只停用平台绑定，
     * 不修改其 CK 原生授权。失败时保留 disabling 状态供后台对账重试。
     *
     * @param userId 平台用户 ID
     * @param row 待禁用绑定行
     */
    private void disableRow(long userId, Map<String, Object> row) {
        long bindingId = num(row.get("id"));
        long instanceId = num(row.get("ck_instance_id"));
        String username = str(row.get("ck_username"));
        if (!ckUsername(userId).equals(username)) {
            // 遗留共享账号不能轮换密码;平台绑定停用后不再可用于业务。
            jdbc.update("UPDATE el_user_ck_bindings SET status='disabled',updated_at=CURRENT_TIMESTAMP WHERE id=?", bindingId);
            invalidateUser(userId);
            return;
        }
        long version = num(row.get("credential_version")) + 1;
        String password = newPassword();
        jdbc.update("UPDATE el_user_ck_bindings SET status='disabling',updated_at=CURRENT_TIMESTAMP WHERE id=?", bindingId);
        long operationId = operation("binding:" + bindingId + ":disable:v" + version, userId, instanceId, "disable-user");
        try {
            managementClientForInstance(instanceId).exec("ALTER USER " + quoteName(username)
                    + " IDENTIFIED WITH sha256_password BY " + Sql.quoteString(password));
            jdbc.update("UPDATE el_user_ck_bindings SET status='disabled',credential_ciphertext=?,credential_version=?,"
                            + "last_error=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                    Crypto.encrypt(config.getSecretKey(), password), version, bindingId);
            finishOperation(operationId, "done", null);
        } catch (RuntimeException e) {
            String error = safeMessage(e);
            jdbc.update("UPDATE el_user_ck_bindings SET last_error=?,updated_at=CURRENT_TIMESTAMP WHERE id=?", error, bindingId);
            finishOperation(operationId, "failed", error);
        }
        invalidateUser(userId);
    }

    /** 平台重新启用用户时生成新凭据并恢复绑定。 */
    public void enableAll(long userId) {
        initializeAll(userId);
    }

    /** 清除某用户的全部池化业务连接。 */
    public void invalidateUser(long userId) {
        pool.removePrefix("u:" + userId + ":");
    }

    // =====================================================================
    // 查询
    // =====================================================================

    /**
     * 查询用户在指定 CK 实例上的原始绑定记录。
     *
     * @param userId 平台用户 ID
     * @param instanceId CK 实例 ID
     * @return 绑定行；不存在时为 null
     */
    public Map<String, Object> binding(long userId, long instanceId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM el_user_ck_bindings WHERE user_id=? AND ck_instance_id=?", userId, instanceId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 查询用户在所有 CK 实例上的绑定视图。
     *
     * <p>active 记录若与当前用户名策略不一致，会返回 migration-required 和迁移提示。
     *
     * @param userId 平台用户 ID
     * @return 按绑定 ID 排序的展示行
     */
    public List<Map<String, Object>> bindings(long userId) {
        List<Map<String,Object>> rows = jdbc.queryForList("SELECT b.id AS bindingId,b.ck_instance_id AS instanceId,i.name AS instanceName,"
                + "b.ck_username AS ckUsername,b.status,b.last_error AS lastError,b.last_verified_at AS lastVerifiedAt,"
                + "b.credential_version AS credentialVersion FROM el_user_ck_bindings b"
                + " LEFT JOIN el_ck_instances i ON i.id=b.ck_instance_id WHERE b.user_id=? ORDER BY b.id", userId);
        for (Map<String,Object> row : rows) {
            if ("active".equals(str(row.get("status"))) && !expectedUsername(userId,num(row.get("instanceId"))).equals(str(row.get("ckUsername")))) {
                row.put("status", "migration-required");
                row.put("lastError", "CK 绑定与当前账号策略不一致，请重新初始化绑定");
            }
        }
        return rows;
    }

    /**
     * 读取单个绑定的脱敏展示视图。
     *
     * @param bindingId 绑定 ID
     * @return 绑定详情；不存在时返回 null
     */
    private Map<String, Object> bindingView(long bindingId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT b.id AS bindingId,b.user_id AS userId,"
                + "b.ck_instance_id AS instanceId,i.name AS instanceName,b.ck_username AS ckUsername,b.status,"
                + "b.last_error AS lastError,b.last_verified_at AS lastVerifiedAt,b.credential_version AS credentialVersion"
                + " FROM el_user_ck_bindings b LEFT JOIN el_ck_instances i ON i.id=b.ck_instance_id WHERE b.id=?", bindingId);
        return rows.isEmpty() ? Collections.emptyMap() : rows.get(0);
    }

    /** 平台侧稳定 CK 用户名:只由不可变 user_id 派生,改显示名不影响绑定。 */
    public static String ckUsername(long userId) {
        return "el_user_" + userId;
    }

    // =====================================================================
    // 内部
    // =====================================================================

    /**
     * 使用待验证凭据实际登录 CK，并核对 currentUser() 与绑定用户名一致。
     *
     * @param instanceId CK 实例 ID
     * @param username 预期 CK 用户名
     * @param password 待验证密码
     * @throws com.el2ck.exception.ApiException 登录失败或执行身份不一致时抛出 409
     */
    private void verifyUser(long instanceId, String username, String password) {
        CkConfig cfg = connections.managementConfig(instances.managementConnectionId(instanceId)).copy();
        cfg.setUsername(username);
        cfg.setPassword(password);
        try (CkClient c = CkClient.open(cfg)) {
            List<List<Object>> rows = c.query("SELECT currentUser()").getRows();
            if (rows.size() != 1 || rows.get(0).isEmpty() || !username.equals(str(rows.get(0).get(0)))) {
                throw new ApiException(409, "CK 执行身份与绑定账号不一致");
            }
        }
    }

    /**
     * 安全引用 CK 标识符。
     *
     * @param name 原始标识符
     * @return 转义后的反引号标识符
     */
    private String quoteName(String name) {
        return Sql.quoteIdent(name);
    }

    /**
     * 生成 256 位随机密码并以无填充 Base64URL 编码。
     *
     * @return 可供平台生成账号使用的新密码
     */
    private static String newPassword() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * 创建或重启一条 CK 身份操作审计记录。
     *
     * @param key 幂等操作键
     * @param userId 目标用户 ID
     * @param instanceId CK 实例 ID
     * @param action 操作类型
     * @return 操作记录 ID
     */
    private long operation(String key, long userId, long instanceId, String action) {
        jdbc.update("INSERT INTO el_ck_access_operations(operation_key,user_id,ck_instance_id,action,desired_state,status,created_by)"
                        + " VALUES(?,?,?,?,?,'running',?) ON DUPLICATE KEY UPDATE status='running',attempts=attempts+1,updated_at=CURRENT_TIMESTAMP",
                key, userId, instanceId, action, "active", AuthContext.get() == null ? userId : AuthContext.userId());
        return jdbc.queryForObject("SELECT id FROM el_ck_access_operations WHERE operation_key=?", Long.class, key);
    }

    /**
     * 写回 CK 身份操作终态和最近错误。
     *
     * @param id 操作记录 ID
     * @param status 操作终态
     * @param error 可空错误摘要
     */
    private void finishOperation(long id, String status, String error) {
        jdbc.update("UPDATE el_ck_access_operations SET status=?,last_error=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                status, error, id);
    }

    /**
     * 写入身份或授权操作审计记录。
     *
     * @param action 审计动作
     * @param type 资源类型
     * @param resourceId 资源 ID，可为 null
     * @param target 目标用户 ID，可为 null
     */
    private void audit(String action, String type, Long resourceId, Long target) {
        AuthContext.Principal p = AuthContext.get();
        jdbc.update("INSERT INTO el_access_audit(actor_user_id,action,resource_type,resource_id,target_user_id)"
                        + " VALUES(?,?,?,?,?)",
                p == null ? target : p.getUserId(), action, type, resourceId, target);
    }

    static String safeMessage(Throwable e) {
        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        m = m.replace('\n', ' ').replace('\r', ' ').trim();
        return m.length() > 450 ? m.substring(0, 450) : m;
    }

    static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    static long num(Object v) {
        return v instanceof Number ? ((Number) v).longValue() : Long.parseLong(String.valueOf(v));
    }
}
