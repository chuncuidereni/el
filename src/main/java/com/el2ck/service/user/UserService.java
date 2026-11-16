/**
 * 【文件职责】本文件定义用户与授权规则模块的业务服务 UserService。承载该模块的核心业务规则，协调仓储、连接器或外部服务，并统一转换业务异常和操作状态。
 */

package com.el2ck.service.user;

import com.el2ck.service.connection.ConnectionService;

import com.el2ck.exception.ApiException;
import com.el2ck.util.Sql;
import com.el2ck.ckpool.CkClient;
import com.el2ck.ckpool.CkConfig;
import com.el2ck.ckpool.QueryResult;
import com.el2ck.entity.user.GrantRule;
import com.el2ck.entity.user.UserInfo;
import com.el2ck.entity.user.UserInput;
import com.el2ck.repository.metadata.MetadataRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * ClickHouse 用户与权限管理(对应 Go internal/service/user_service.go)。
 *
 * 通过 system.users / system.grants 系统表读取用户与授权;创建/更新走 CREATE/ALTER USER 与 GRANT。
 * 用户备注是平台元数据,存 SQLite user_remarks,不与 ClickHouse 系统表混存。
 * 主机规则支持 "IP x / LIKE x / NAME x / REGEXP x / ANY / NONE / LOCAL" 前缀写法。
 */
public class UserService {

    /** 连接服务(取客户端 + 只读判定)。 */
    private final ConnectionService connSvc;
    private final com.el2ck.service.ck.CkIdentityService identities;
    // 元数据仓库(SQLite):读写用户备注等平台元数据
    private final MetadataRepository repository;

    /** JSON 解析(把驱动可能返回的 JSON 数组字符串还原成列表)。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 授权对象(库/表)允许的字符:* 作为通配(对应 Go grantObjRe)。 */
    private static final Pattern GRANT_OBJ_RE = Pattern.compile("^[*A-Za-z_][*A-Za-z0-9_]*$");

    /**
     * 注入连接配置、平台元数据和 CK 身份管理依赖。
     *
     * @param connSvc 提供 CK 配置和只读判定
     * @param repository 保存用户备注等平台元数据
     * @param identities 使用受控管理身份执行用户和授权管理
     */
    public UserService(ConnectionService connSvc, MetadataRepository repository,
                       com.el2ck.service.ck.CkIdentityService identities) {
        // 注入连接服务(提供只读判定)
        this.connSvc = connSvc;
        // 注入元数据仓库
        this.repository = repository;
        // CK 用户管理使用配置的管理身份(admin),避免业务账号 el-admin 权限不足
        this.identities = identities;
    }

    // =====================================================================
    // 读取
    // =====================================================================

    /** 返回当前登录账号角色；用户列表只允许管理员读取。 */
    public String currentRole(long connId) {
        CkConfig cfg = connSvc.effectiveConfig(connId);
        // 平台侧显式配置的只读连接优先级最高,不必再问 ClickHouse
        if (cfg.isReadOnly()) return "只读";
        CkClient client = identities.managementClient(connId);
        // 先按"当前账号自身的授权"判定(比 readonly 设置更贴近真实能力)
        String grantRole = currentGrantRole(client, identities.managementUsername(connId));
        if ("管理员".equals(grantRole)) return grantRole;
        if ("只读".equals(grantRole)) return grantRole;
        try {
            // 自身授权读不出来时,退回服务端 readonly 设置(>0 即受限)
            QueryResult result = query(client, "SELECT getSetting('readonly')");
            if (!result.getRows().isEmpty() && !result.getRows().get(0).isEmpty()
                    && isReadOnlySetting(result.getRows().get(0).get(0))) return "只读";
        } catch (ApiException ignored) {
            // 无权读取设置时按非管理员的最小权限返回只读。
            return "只读";
        }
        // 以上都未命中:账号既非管理员也未被限制,按可读写返回
        return "读写";
    }

    /** 根据当前登录用户自己的授权判断角色，避免只看 readonly 设置造成误判。 */
    private static String currentGrantRole(CkClient client, String username) {
        if (username == null || username.trim().isEmpty()) return null;
        try {
            QueryResult result = query(client,
                    "SELECT access_type FROM system.grants WHERE user_name = ?",
                    java.util.Collections.<Object>singletonList(username));
            boolean hasSelect = false;
            boolean hasWrite = false;
            for (List<Object> row : result.getRows()) {
                if (row.isEmpty()) continue;
                String access = anyStr(row.get(0)).toUpperCase(java.util.Locale.ROOT);
                if (access.contains("ALL") || access.contains("CREATE USER")
                        || access.contains("ALTER USER") || access.contains("DROP USER")
                        || access.contains("GRANT")) return "管理员";
                if (access.contains("INSERT") || access.contains("ALTER")
                        || access.contains("UPDATE") || access.contains("DELETE")) hasWrite = true;
                if (access.contains("SELECT")) hasSelect = true;
            }
            if (hasWrite) return "读写";
            if (hasSelect) return "只读";
        } catch (ApiException ignored) {
            // 普通账号可能没有 system.grants 查询权限，下面使用 SHOW GRANTS 读取自身授权。
        }
        try {
            QueryResult result = query(client, "SHOW GRANTS FOR " + Sql.quoteIdent(username));
            boolean hasSelect = false;
            boolean hasWrite = false;
            boolean hasGrant = false;
            for (List<Object> row : result.getRows()) {
                String grant = row.isEmpty() ? "" : anyStr(row.get(0)).toUpperCase(java.util.Locale.ROOT);
                if (grant.contains("ALL") || grant.contains("CREATE USER")
                        || grant.contains("ALTER USER") || grant.contains("DROP USER")
                        || grant.contains("GRANT OPTION")) hasGrant = true;
                if (grant.contains("INSERT") || grant.contains("ALTER UPDATE")
                        || grant.contains("ALTER DELETE") || grant.contains("UPDATE")
                        || grant.contains("DELETE")) hasWrite = true;
                if (grant.contains("SELECT")) hasSelect = true;
            }
            if (hasGrant) return "管理员";
            if (hasWrite) return "读写";
            if (hasSelect) return "只读";
        } catch (ApiException ignored) {
            // 两种授权读取方式均不可用时，继续由 readonly 设置兜底。
        }
        return null;
    }

    /** 列出全部用户(default 恒置顶,其余按字母序)。备注取自平台元数据。 */
    public List<UserInfo> listUsers(long connId) {
        CkClient client = identities.managementClient(connId);
        // 查询 system.users:用户名/认证类型 + 三类主机列,按名字排序
        QueryResult res;
        try {
            res = query(client,
                    "SELECT name, auth_type, host_ip, host_names, host_names_like FROM system.users ORDER BY name");
        } catch (ApiException e) {
            // 此入口仅限平台管理员且使用管理身份;权限不足不能伪装成“暂无用户”。
            if (isSystemPrivilegeDenied(e, "system.users")) {
                throw new ApiException(502, "CK 管理身份缺少查看用户信息的权限，请检查管理账号的 SHOW ACCESS 授权");
            }
            throw e;
        }
        // 备注存平台元数据,不与 ClickHouse 系统表混存。
        Map<String, String> remarks = repository.getUserRemarks(connId);
        // 一次读取授权并归类角色；没有 system.grants 权限时安全回退为只读。
        Map<String, String> roles = loadRoles(client);
        // 待返回的用户列表
        List<UserInfo> out = new ArrayList<>();
        // 逐行把查询结果转成 UserInfo
        for (List<Object> row : res.getRows()) {
            // 行不足 5 列说明数据异常,跳过该行防越界
            if (row.size() < 5) {
                continue;
            }
            // 构造用户对象
            UserInfo u = new UserInfo();
            // 第 0 列:用户名
            u.setName(String.valueOf(row.get(0)));
            // 第 1 列:认证类型
            u.setAuthType(String.valueOf(row.get(1)));
            u.setRole(roles.getOrDefault(u.getName(), "只读"));
            // 还原主机限制:三类主机列分别补前缀
            List<String> hosts = new ArrayList<>();
            // 三个主机列分别按前缀还原:IP / NAME / LIKE。
            hosts.addAll(anyToStrs(row.get(2), "IP "));
            hosts.addAll(anyToStrs(row.get(3), "NAME "));
            hosts.addAll(anyToStrs(row.get(4), "LIKE "));
            // 无任何主机限制时展示为 ANY(允许任意主机)
            if (hosts.isEmpty()) {
                hosts.add("ANY");
            }
            // 写回还原后的主机列表
            u.setHosts(hosts);
            // 从平台元数据取该用户备注(可能没有)
            String r = remarks.get(u.getName());
            // 备注为空则以空串兜底
            u.setRemark(r == null ? "" : r);
            // 加入结果列表
            out.add(u);
        }
        // default 用户恒置顶,其余按字母序。
        out.sort((a, b) -> {
            if ("default".equals(a.getName())) {
                return -1;
            }
            if ("default".equals(b.getName())) {
                return 1;
            }
            return a.getName().compareTo(b.getName());
        });
        return out;
    }

    /** 按用户授权归类为只读、读写或管理员。 */
    private static Map<String, String> loadRoles(CkClient client) {
        Map<String, String> roles = new LinkedHashMap<>();
        try {
            QueryResult result = query(client,
                    "SELECT user_name, access_type FROM system.grants WHERE user_name != ''");
            Map<String, Boolean> writable = new LinkedHashMap<>();
            Map<String, Boolean> admin = new LinkedHashMap<>();
            for (List<Object> row : result.getRows()) {
                if (row.size() < 2) continue;
                String user = anyStr(row.get(0));
                String access = anyStr(row.get(1)).toUpperCase(java.util.Locale.ROOT);
                if (user.isEmpty()) continue;
                if (access.contains("ALL") || access.contains("CREATE USER")
                        || access.contains("ALTER USER") || access.contains("DROP USER")
                        || access.contains("GRANT")) {
                    admin.put(user, Boolean.TRUE);
                } else if (access.contains("INSERT") || access.contains("ALTER")
                        || access.contains("UPDATE") || access.contains("DELETE")) {
                    writable.put(user, Boolean.TRUE);
                }
            }
            // 先写"读写",再用管理员覆盖:同一用户既被判定写权限又命中管理权限时,以管理员为准
            for (String user : writable.keySet()) roles.put(user, "读写");
            for (String user : admin.keySet()) roles.put(user, "管理员");
        } catch (ApiException e) {
            if (!isSystemPrivilegeDenied(e, "system.grants")) throw e;
        }
        // 部分 ClickHouse 版本通过 system.users.access_management 标识管理员，
        // 这类管理员未必在 system.grants 中产生一条 ALL 授权，补充读取该标记。
        try {
            QueryResult result = query(client,
                    "SELECT name, access_management FROM system.users");
            for (List<Object> row : result.getRows()) {
                if (row.size() >= 2 && isTrue(row.get(1))) {
                    roles.put(anyStr(row.get(0)), "管理员");
                }
            }
        } catch (ApiException e) {
            // access_management 在旧版本中可能不存在，角色补充查询失败不影响用户列表。
        }
        return roles;
    }

    /** 兼容 ClickHouse 返回的 UInt8、Boolean 或字符串布尔值。 */
    private static boolean isTrue(Object value) {
        String text = anyStr(value).trim();
        return "1".equals(text) || "true".equalsIgnoreCase(text) || "yes".equalsIgnoreCase(text);
    }

    /** ClickHouse readonly 设置 1/2 均表示限制写操作，只有 0 表示不限制。 */
    private static boolean isReadOnlySetting(Object value) {
        String text = anyStr(value).trim();
        try {
            return Integer.parseInt(text) > 0;
        } catch (NumberFormatException ignored) {
            return isTrue(value);
        }
    }

        /** 读取某用户的授权规则(还原 * 通配)。 */
    public List<GrantRule> getGrants(long connId, String user) {
        CkClient client = identities.managementClient(connId);
        // 查询 system.grants 该用户全部授权,按库/表排序
        QueryResult res;
        try {
            res = query(client,
                    "SELECT * FROM system.grants WHERE user_name = ? ORDER BY database, table",
                    java.util.Collections.<Object>singletonList(user));
        } catch (ApiException e) {
            if (isSystemPrivilegeDenied(e, "system.grants")) {
                throw new ApiException(502, "CK 管理身份缺少查看授权信息的权限，请检查管理账号的 SHOW ACCESS 授权");
            }
            throw e;
        }
        // 25.3 没有 is_wildcard 列；按实际列名读取，旧版的空库/表仍还原为 *。
        int dbIndex = res.getColumns().indexOf("database");
        int tableIndex = res.getColumns().indexOf("table");
        int accessIndex = res.getColumns().indexOf("access_type");
        int wildcardIndex = res.getColumns().indexOf("is_wildcard");
        // 按 (db, tbl, 是否通配) 聚合 access_type。
        Map<GrantKey, List<String>> grouped = new LinkedHashMap<>();
        List<GrantKey> order = new ArrayList<>();
        // 逐行聚合:以 (库,表,是否通配) 为分组键
        for (List<Object> row : res.getRows()) {
            GrantKey k = new GrantKey(anyStr(dbIndex >= 0 ? row.get(dbIndex) : null),
                    anyStr(tableIndex >= 0 ? row.get(tableIndex) : null),
                    wildcardIndex >= 0 && isTrue(row.get(wildcardIndex)));
            // 键首次出现:登记分组并维护出现顺序
            if (!grouped.containsKey(k)) {
                grouped.put(k, new ArrayList<String>());
                order.add(k);
            }
            // 把该行权限类型追加进对应分组
            grouped.get(k).add(anyStr(accessIndex >= 0 ? row.get(accessIndex) : null));
        }
        List<GrantRule> rules = new ArrayList<>();
        // 按出现顺序逐条还原授权规则
        for (GrantKey k : order) {
            GrantRule g = new GrantRule();
            // 还原库/表显示名(补回省略的 * 通配)
            String[] d = reconstructGrant(k.db, k.tbl, k.wildcard);
            // 还原后的库名
            g.setDatabase(d[0]);
            g.setTable(d[1]);
            // 把该组权限类型聚合为 select/readwrite/all
            g.setAccess(mapAccess(grouped.get(k)));
            // 加入规则列表
            rules.add(g);
        }
        return rules;
    }

    /** 授权行分组的复合键(库 + 表 + 是否通配)。 */
    private static final class GrantKey {
        final String db;
        final String tbl;
        final boolean wildcard;

        // 分组键构造:记录库/表/是否通配
        GrantKey(String db, String tbl, boolean wildcard) {
            this.db = db;
            this.tbl = tbl;
            this.wildcard = wildcard;
        }

        /**
         * 按数据库、表及通配标记比较授权对象键。
         *
         * @param o 待比较对象
         * @return 三个字段均相同时为 true
         */
        @Override
        public boolean equals(Object o) {
            if (!(o instanceof GrantKey)) {
                return false;
            }
            GrantKey k = (GrantKey) o;
            // 库/表/通配三个字段全等才视为同一键
            return wildcard == k.wildcard && db.equals(k.db) && tbl.equals(k.tbl);
        }

        /**
         * 生成与 {@link #equals(Object)} 一致的授权对象键散列。
         *
         * @return 组合数据库、表和通配标记后的散列值
         */
        @Override
        public int hashCode() {
            // 从库名起算散列
            int r = db.hashCode();
            // 叠加表名散列
            r = 31 * r + tbl.hashCode();
            // 叠加通配标记散列
            r = 31 * r + (wildcard ? 1 : 0);
            return r;
        }
    }

    // =====================================================================
    // 变更
    // =====================================================================

    /** 创建用户并授权。 */
    public void createUser(long connId, UserInput in) {
        // 请求对象为空直接拒绝
        if (in == null) {
            throw ApiException.validation("请求内容不能为空");
        }
        // 校验用户名/密码/授权规则
        validate(in);
        CkClient client = identities.managementClient(connId);
        // 只有管理员可以创建用户，读写/只读账号即使直接调用接口也拒绝。
        checkUserAdmin(connId, client);
        String uname = Sql.quoteIdent(in.getName()); // 非法用户名已由 validate 拦截,这里二次防护。
        // 组装 CREATE USER:密码按 sha256_password 存放,主机规则单拼
        String sql = "CREATE USER " + uname
                + " IDENTIFIED WITH sha256_password BY " + Sql.quoteString(def(in.getPassword()))
                + " HOST " + hostClause(in.getHosts());
        // 执行创建用户语句
        exec(client, sql);
        // 保存平台用户备注
        repository.setUserRemark(connId, in.getName(), def(in.getRemark()));
        // 按入参重建用户授权(先清后建)
        applyGrants(client, in.getName(), in.getGrants());
    }

    /** 更新用户:密码/主机/授权(留空则不修改对应项)。 */
    public void updateUser(long connId, String name, UserInput in) {
        // default 内置用户禁止修改
        if ("default".equals(name)) {
            throw ApiException.validation("default 用户不允许修改");
        }
        // 请求对象为空直接拒绝
        if (in == null) {
            throw ApiException.validation("请求内容不能为空");
        }
        // 校验入参授权部分(密码/主机留空表示不修改)
        validateGrants(in.getGrants());
        CkClient client = identities.managementClient(connId);
        // 只有管理员可以修改用户及授权。
        checkUserAdmin(connId, client);
        // 转义用户名防注入
        String uname = Sql.quoteIdent(name);
        // 收集待拼到 ALTER USER 的变更片段
        List<String> parts = new ArrayList<>();
        // 仅当传入非空密码时才追加密码子句
        if (in.getPassword() != null && !in.getPassword().isEmpty()) {
            parts.add("IDENTIFIED WITH sha256_password BY " + Sql.quoteString(in.getPassword()));
        }
        // hosts 非 null 表示页面明确提交了主机限制;空数组也要写回 ANY,
        // 否则用户在页面清空旧限制时会被静默忽略。
        if (in.getHosts() != null) {
            parts.add("HOST " + hostClause(in.getHosts()));
        }
        // 存在任何要改的项才执行 ALTER USER
        if (!parts.isEmpty()) {
            exec(client, "ALTER USER " + uname + " " + String.join(" ", parts));
        }
        // 更新平台用户备注
        repository.setUserRemark(connId, name, def(in.getRemark()));
        // 传入了授权或明确要求清空时才重建授权,否则保留旧授权
        if ((in.getGrants() != null && !in.getGrants().isEmpty()) || Boolean.TRUE.equals(in.getClearGrants())) {
            applyGrants(client, name, in.getGrants());
        }
    }

    /** 删除用户(连带清理平台备注)。 */
    public void dropUser(long connId, String name) {
        // default 内置用户禁止删除
        if ("default".equals(name)) {
            throw ApiException.validation("default 用户不允许删除");
        }
        CkClient client = identities.managementClient(connId);
        // 只有管理员可以删除用户。
        checkUserAdmin(connId, client);
        String uname = Sql.quoteIdent(name);
        // 删除用户;不存在也静默通过
        exec(client, "DROP USER IF EXISTS " + uname);
        // 同步清理平台备注
        repository.deleteUserRemark(connId, name);
    }

    // =====================================================================
    // 内部实现(授权/校验/主机)
    // =====================================================================

    /** 只读连接禁止用户管理。 */
    private void checkWritable(long connId) {
        // 取该连接生效配置(只读开关位于其中)
        CkConfig cfg = connSvc.effectiveConfig(connId);
        // 只读模式拒绝用户管理操作
        if (cfg.isReadOnly()) {
            throw ApiException.readOnly("当前连接为只读模式,不允许用户管理");
        }
    }

    /** 入参校验:用户名/密码/每条授权的权限与对象。 */
    private void validate(UserInput in) {
        // 用户名必须是合法标识符(字母/数字/下划线,不以数字开头)
        if (!Sql.validIdent(def(in.getName()))) {
            throw ApiException.validation("用户名仅允许字母/数字/下划线且不能以数字开头");
        }
        // 密码必填
        if (in.getPassword() == null || in.getPassword().isEmpty()) {
            throw ApiException.validation("密码不能为空");
        }
        // 校验每条授权的权限与对象
        validateGrants(in.getGrants());
    }

    /** 逐条校验授权:权限类型须可映射为 ClickHouse 权限,库/表对象名须合法。 */
    private void validateGrants(List<GrantRule> grants) {
        // 授权列表为空则跳过校验
        if (grants != null) {
            // 逐条校验授权
            for (GrantRule g : grants) {
                // 授权对象为空拒绝
                if (g == null) {
                    throw ApiException.validation("授权规则不能为空");
                }
                // 校验权限类型能否映射为 ClickHouse 权限
                try {
                    accessToPrivs(def(g.getAccess()));
                } catch (IllegalArgumentException e) {
                    throw ApiException.validation(e.getMessage());
                }
                // 校验库/表对象名是否合法
                try {
                    grantObject(g.getDatabase(), g.getTable());
                } catch (IllegalArgumentException e) {
                    throw ApiException.validation(e.getMessage());
                }
            }
        }
    }

    /**
     * 重建授权:<b>先把全部 GRANT 语句算出并校验,全通过后</b>才 REVOKE ALL 再逐条 GRANT
     * (ClickHouse 无事务,失败可能半应用,与 Go 版一致)。
     *
     * <p>⚠️ 校验必须在 REVOKE 之前全部做完:库名/表名是界面自由文本,填错一个字符(库名 my-db)
     * 就会走到校验失败;若校验发生在 REVOKE 之后,该用户原有权限已被清空且<b>无法回滚</b>,
     * 用户只看到一条报错。对齐 Go applyGrants,由 {@code user_grants_order_test} 钉住。
     */
    private void applyGrants(CkClient client, String user, List<GrantRule> grants) {
        String uname = Sql.quoteIdent(user);
        // 先把全部 GRANT 语句算出并校验(库名/表名/权限非法在此抛错,此时尚未动库)。
        List<String> grantStmts = buildGrantStatements(uname, grants);
        // 全部合法后才动库:先撤销旧授权,再逐条 GRANT 重建。
        exec(client, "REVOKE ALL ON *.* FROM " + uname);
        for (String stmt : grantStmts) {
            exec(client, stmt);
        }
    }

    /**
     * 把授权规则编译成 GRANT 语句列表,并在此完成全部校验(库名/表名/权限非法即抛)。
     * 抽成纯函数是为了:① 保证「校验在 REVOKE 之前全部完成」这条不变量;② 可脱离真实连接单测。
     */
    static List<String> buildGrantStatements(String uname, List<GrantRule> grants) {
        List<String> stmts = new ArrayList<>();
        if (grants == null) {
            return stmts;
        }
        for (GrantRule g : grants) {
            // 权限类型转 ClickHouse 权限字面量(非法 access 在此抛)
            String privs = accessToPrivs(def(g.getAccess()));
            // 组装配授权的 库.表 对象(非法库/表名在此抛)
            String obj = grantObject(g.getDatabase(), g.getTable());
            stmts.add("GRANT " + privs + " ON " + obj + " TO " + uname);
        }
        return stmts;
    }

    /**
     * 组装 HOST 子句,多个主机用逗号分隔(值按字符串字面量转义,防注入)。
     * 没有有效主机时回退 ANY。对应 Go hostClause。
     */
    static String hostClause(List<String> hosts) {
        // 清洗后的主机片段集合
        List<String> clean = new ArrayList<>();
        // hosts 可为 null,此时集合为空
        if (hosts != null) {
            // 逐条清洗/组装主机规则
            for (String h : hosts) {
                // 空主机条目跳过
                if (h == null) {
                    continue;
                }
                // 去掉首尾空白
                h = h.trim();
                // 空白条目跳过
                if (h.isEmpty()) {
                    continue;
                }
                // 解析主机为 [类型,值] 二元组
                String[] kv = parseHost(h);
                // 读取主机类型
                String kind = kv[0];
                String val = kv[1];
                // 四类带值主机规则:值按字符串字面量转义,防止注入
                if ("IP".equals(kind) || "LIKE".equals(kind) || "NAME".equals(kind) || "REGEXP".equals(kind)) {
                    clean.add(kind + " " + Sql.quoteString(val));
                // 三类关键字主机规则:无值,原样输出
                } else if ("ANY".equals(kind) || "NONE".equals(kind) || "LOCAL".equals(kind)) {
                    clean.add(kind);
                }
            }
        }
        // 无有效主机时回退 ANY(不限来源主机)
        if (clean.isEmpty()) {
            return "ANY";
        }
        // 多个主机片段以逗号连接成 HOST 子句
        return String.join(", ", clean);
    }

    /**
     * 解析一条主机规则。带前缀按前缀;否则自动识别:
     * IPv4/IPv6/CIDR → IP;含 %/* → LIKE;其余 → NAME。对应 Go parseHost。
     *
     * @return [kind, value];kind ∈ IP/LIKE/NAME/REGEXP/ANY/NONE/LOCAL
     */
    static String[] parseHost(String h) {
        // 去掉首尾空白
        h = normalizeHostValue(h);
        // 转大写便于前缀与关键字匹配
        String up = h.toUpperCase();
        // 显式前缀:IP / LIKE / NAME / REGEXP(值去掉两端单引号)。
        // 依次探测显式前缀:IP / LIKE / NAME / REGEXP
        for (String kw : new String[]{"IP ", "LIKE ", "NAME ", "REGEXP "}) {
            // 命中前缀:取前缀后的值并去掉两端单引号
            if (up.startsWith(kw)) {
                String val = h.substring(kw.length()).trim();
                val = trimQuotes(val);
                return new String[]{kw.trim(), val};
            }
        }
        // 关键字类型(ANY/NONE/LOCAL)直接归类
        switch (up) {
            case "ANY":
            case "NONE":
            case "LOCAL":
                return new String[]{up, ""};
            default:
                break;
        }
        // IPv4/IPv6 地址识别为 IP 规则
        if (isIp(h)) {
            return new String[]{"IP", h};
        }
        // CIDR 网段识别为 IP 规则
        if (isCidr(h)) {
            return new String[]{"IP", h};
        }
        // 含 % 或 * 通配符识别为 LIKE 规则
        if (h.indexOf('%') >= 0 || h.indexOf('*') >= 0) {
            return new String[]{"LIKE", h};
        }
        // 其余一律按主机名 NAME 处理
        return new String[]{"NAME", h};
    }

    /** 去掉字符串两端单引号(host 值还原用)。 */
    private static String trimQuotes(String s) {
        // 定义左右指针,分别从串首与串尾扫描
        int b = 0, e = s.length();
        // 左指针右移,跳过开头的连续单引号
        while (b < e && s.charAt(b) == '\'') {
            b++;
        }
        // 右指针左移,跳过结尾的连续单引号
        while (e > b && s.charAt(e - 1) == '\'') {
            e--;
        }
        // 返回剔除两端单引号后的子串
        return s.substring(b, e);
    }

    // ---- IP/CIDR 探测(等价 Go net.ParseIP/net.ParseCIDR) ----

    // IPv4 或 IPv6 均视为 IP
    /**
     * 判断字符串是否为 IPv4 或 IPv6 地址。
     *
     * @param s 待检查文本
     * @return 合法 IP 时为 true
     */
    private static boolean isIp(String s) {
        return isIpv4(s) || isIpv6(s);
    }

    // IPv4 判断:恰 4 段十进制,每段 0~255
    /**
     * 按四段十进制且每段 0 到 255 的规则判断 IPv4。
     *
     * @param s 待检查文本
     * @return 合法 IPv4 时为 true
     */
    private static boolean isIpv4(String s) {
        // 按点切分;-1 保留末尾空段便于判错
        String[] parts = s.split("\\.", -1);
        // 段数非 4 即非 IPv4
        if (parts.length != 4) {
            return false;
        }
        for (String p : parts) {
            // 空段或超过 3 位数字则非法
            if (p.isEmpty() || p.length() > 3) {
                return false;
            }
            // 逐字符累加十进制并顺带校验纯数字
            int v = 0;
            for (int i = 0; i < p.length(); i++) {
                char ch = p.charAt(i);
                // 出现非数字字符即非法
                if (ch < '0' || ch > '9') {
                    return false;
                }
                v = v * 10 + (ch - '0');
            }
            // 段值超过 255 非法
            if (v > 255) {
                return false;
            }
        }
        return true;
    }

    // IPv6 判断:仅十六进制与冒号,按压缩形式判定
    /**
     * 按十六进制字符和冒号数量判断宽松 IPv6 写法。
     *
     * @param s 待检查文本
     * @return 符合本类主机规则所需语法时为 true
     */
    private static boolean isIpv6(String s) {
        // 含非十六进制/冒号字符直接排除
        if (!s.matches("[0-9A-Fa-f:]+")) {
            return false;
        }
        int first = s.indexOf("::");
        int last = s.lastIndexOf("::");
        // 出现两处及以上 :: 非法(IPv6 只允许一处压缩)
        if (first != last) {
            return false; // 只允许一个 "::"
        }
        // 统计冒号总数
        int colonCount = countChar(s, ':');
        // 含 :: 的压缩写法:冒号数在 2~7 视为合法(宽松判定)
        if (first >= 0) {
            return colonCount >= 2 && colonCount <= 7;
        }
        // 无 :: 压缩时须恰好 7 个冒号(标准 8 组)
        return colonCount == 7;
    }

    // CIDR 判断:host/prefix,prefix 须落在合法掩码区间
    /**
     * 判断 host/prefix 形式的 IPv4 或 IPv6 CIDR。
     *
     * @param s 待检查文本
     * @return 主机合法且前缀位数位于 0 到对应地址宽度时为 true
     */
    private static boolean isCidr(String s) {
        // 定位斜杠分隔符
        int slash = s.indexOf('/');
        // 无斜杠或斜杠位于首尾则非法
        if (slash <= 0 || slash == s.length() - 1) {
            return false;
        }
        String host = s.substring(0, slash);
        String prefix = s.substring(slash + 1);
        // 主机部分必须本身为合法 IP
        if (!isIp(host)) {
            return false;
        }
        try {
            // 解析前缀位数为整数
            int bits = Integer.parseInt(prefix);
            // IPv6 掩码上限 128,IPv4 为 32
            int max = host.indexOf(':') >= 0 ? 128 : 32;
            // 位数须落在 0~上限 之间
            return bits >= 0 && bits <= max;
        // 前缀非数字则视为非法 CIDR
        } catch (NumberFormatException e) {
            return false;
        }
    }

    // 统计字符 c 在串中的出现次数
    /**
     * 统计字符在字符串中的出现次数。
     *
     * @param s 文本
     * @param c 目标字符
     * @return 出现次数
     */
    private static int countChar(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }

    /** 授权对象(库/表)校验:允许字母数字下划线与 * 通配。 */
    static String grantObject(String db, String tbl) {
        // 库名空值兜底空串
        String d = def(db);
        // 库名为空按全库 * 处理
        if (d.isEmpty()) {
            d = "*";
        }
        // 表名空值兜底空串
        String t = def(tbl);
        // 表名为空按全表 * 处理
        if (t.isEmpty()) {
            t = "*";
        }
        // 库/表名必须匹配白名单:字母数字下划线及 * 通配
        if (!GRANT_OBJ_RE.matcher(d).matches() || !GRANT_OBJ_RE.matcher(t).matches()) {
            throw new IllegalArgumentException("库/表名仅允许字母、数字、下划线和 *");
        }
        // 拼成 db.table 授权对象
        return d + "." + t;
    }

    /** 权限类型 → ClickHouse 权限字面量。 */
    static String accessToPrivs(String a) {
        // 权限类型转小写便于匹配
        String low = a == null ? "" : a.toLowerCase();
        switch (low) {
            case "select":
                return "SELECT";
            case "readwrite":
                return "SELECT, INSERT";
            case "all":
                return "ALL";
            default:
                throw new IllegalArgumentException("无效的权限类型: " + def(a));
        }
    }

    /** 把一组 access_type 聚合为规则权限(与 Go mapAccess 一致,含兜底)。 */
    private static String mapAccess(List<String> types) {
        // 记录是否出现 ALL/SELECT/INSERT 三类权限
        boolean hasAll = false, hasSelect = false, hasInsert = false;
        // 遍历该行解析出的所有权限类型
        for (String t : types) {
            // 转大写参与匹配
            String up = t == null ? "" : t.toUpperCase();
            // 类型串含 ALL 即标记全权限出现
            if (up.contains("ALL")) {
                hasAll = true;
            }
            // system.grants.access_type 可能是 "SELECT","INSERT" 分开行,也可能一个串含逗号。
            // 类型串可能形如 "SELECT,INSERT",按逗号拆开逐段判定
            for (String tok : up.split(",")) {
                tok = tok.trim();
                // 命中 SELECT 标记只读
                if ("SELECT".equals(tok)) {
                    hasSelect = true;
                // 命中 INSERT 标记可写
                } else if ("INSERT".equals(tok)) {
                    hasInsert = true;
                // 命中 ALL 标记全权限
                } else if ("ALL".equals(tok)) {
                    hasAll = true;
                }
            }
        }
        // 出现过 ALL 则整体还原为 all
        if (hasAll) {
            return "all";
        }
        // SELECT 与 INSERT 同时出现 → readwrite
        if (hasSelect && hasInsert) {
            return "readwrite";
        }
        // 仅 SELECT → select
        if (hasSelect) {
            return "select";
        }
        // 仅 INSERT 按更宽 readwrite 还原,避免丢写权限
        if (hasInsert) {
            return "readwrite"; // 仅写入,还原为读写(更宽,避免丢权限)。
        }
        // 兜底:默认授予 all
        return "all";
    }

    /** 还原 system.grants 的一行为带 * 通配的规则(与 Go reconstructGrant 一致)。 */
    private static String[] reconstructGrant(String db, String tbl, boolean wildcard) {
        String d = def(db);
        if (d.isEmpty()) {
            d = "*";
        }
        String t = def(tbl);
        if (wildcard) {
            if (t.isEmpty()) {
                // 库通配,如 db_*.*。
                d = d + "*";
                t = "*";
            } else {
                // 表通配,如 db.t_*。
                t = t + "*";
            }
        } else if (t.isEmpty()) {
            t = "*";
        }
        // 返回还原后的 (库, 表) 二元组
        return new String[]{d, t};
    }

    // ---- 主机列值解析辅助 ----

    /**
     * 把主机列值转成带前缀的字符串列表(等价 Go anyToStrs)。
     * 驱动对 Array(String) 可能返回集合、Java 数组或 JSON 字符串,这里统一兼容。
     */
    static List<String> anyToStrs(Object v, String prefix) {
        // 还原后的字符串列表
        List<String> out = new ArrayList<>();
        // 空值按空列表处理
        if (v == null) {
            return out;
        }
        // 驱动返回集合:逐元素加前缀
        if (v instanceof Collection) {
            for (Object e : (Collection<?>) v) {
                if (e != null) {
                    out.add(prefix + normalizeHostValue(String.valueOf(e)));
                }
            }
        // 驱动返回 Java 数组:反射方式遍历
        } else if (v.getClass().isArray()) {
            // 取数组长度
            int len = Array.getLength(v);
            for (int i = 0; i < len; i++) {
                Object e = Array.get(v, i);
                if (e != null) {
                    out.add(prefix + normalizeHostValue(String.valueOf(e)));
                }
            }
        // 驱动返回字符串:可能是 JSON 数组序列化结果
        } else if (v instanceof String) {
            // 尝试解析为 JSON 字符串数组(如 "[\"a\",\"b\"]")。
            List<String> arr = tryJsonArray((String) v);
            // 解析成功则逐元素加前缀
            if (arr != null) {
                for (String e : arr) {
                    out.add(prefix + normalizeHostValue(e));
                }
            } else {
                out.add(prefix + normalizeHostValue((String) v));
            }
        } else {
            out.add(prefix + normalizeHostValue(String.valueOf(v)));
        }
        return out;
    }

    /** ClickHouse 部分版本把 IPv6 任意地址返回为 [::]，回写 HOST 前去掉展示用方括号。 */
    private static String normalizeHostValue(String value) {
        String text = value == null ? "" : value.trim();
        if (text.length() >= 2 && text.charAt(0) == '[' && text.charAt(text.length() - 1) == ']'
                && text.indexOf(':') >= 0) {
            return text.substring(1, text.length() - 1);
        }
        return text;
    }

    /** 尝试把字符串解析为 JSON 数组;非数组 JSON 返回 null。 */
    private static List<String> tryJsonArray(String s) {
        try {
            // 去掉首尾空白
            String t = s.trim();
            // 非 [ 开头即不是 JSON 数组,返回 null
            if (!t.startsWith("[")) {
                return null;
            }
            // 尝试把 JSON 数组字符串还原成 List
            List<Object> list = JSON.readValue(t, List.class);
            List<String> out = new ArrayList<>(list.size());
            for (Object o : list) {
                // 元素为 null 用空串兜底
                out.add(o == null ? "" : String.valueOf(o));
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /** 任意单元格 → 字符串(null → "")。 */
    private static String anyStr(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    /** null 兜底空串。 */
    private static String def(String s) {
        return s == null ? "" : s;
    }

    /** 查询(带可空参数)。 */
    private static QueryResult query(CkClient client, String sql) {
        // 无参数查询委托三参版本,参数传 null
        return query(client, sql, null);
    }

    /**
     * 执行带可选参数列表的 CK 查询，并统一转换运行时错误。
     *
     * @param client CK 客户端
     * @param sql 参数化 SQL
     * @param args 参数列表；null 表示无参数查询
     * @return CK 查询结果
     * @throws com.el2ck.exception.ApiException 查询失败时转换为 502
     */
    private static QueryResult query(CkClient client, String sql, List<Object> args) {
        try {
            // 有参数走参数化查询,否则直接查询
            return args == null ? client.query(sql) : client.query(sql, args);
        } catch (RuntimeException e) {
            throw ApiException.badGateway(Sql.messageOf(e));
        }
    }

    /**
     * 校验当前登录账号具备用户管理权限。
     * 应用层仅拦截显式配置的只读连接;真正的权限判定由 ClickHouse 在执行
     * CREATE/ALTER/DROP/GRANT 时给出,权限不足统一由 {@link #exec} 转成业务提示
     * (不预先查询 system.users/system.grants,避免误伤被自定义权限限制的合法管理员)。
     */
    private void checkUserAdmin(long connId, CkClient client) {
        // 连接若配置为只读,用户管理操作一律拒绝。
        checkWritable(connId);
    }

    /** 查询当前账号是否具备用户管理权限。 */
    private boolean hasAdminPrivilege(long connId, CkClient client) {
        String username = identities.managementUsername(connId);
        boolean admin = false;
        // access_management 是 ClickHouse 对用户管理权限最可靠的标识。
        try {
            QueryResult result = query(client,
                    "SELECT access_management FROM system.users WHERE name = ?",
                    java.util.Collections.<Object>singletonList(username));
            for (List<Object> row : result.getRows()) {
                if (!row.isEmpty() && isTrue(row.get(0))) {
                    admin = true;
                    break;
                }
            }
        } catch (ApiException ignored) {
            // 兼容没有 access_management 字段或无权读取该列的 ClickHouse 版本，继续检查 grants。
        }
        if (!admin) {
            try {
                QueryResult result = query(client,
                        "SELECT access_type FROM system.grants WHERE user_name = ?",
                        java.util.Collections.<Object>singletonList(username));
                for (List<Object> row : result.getRows()) {
                    if (!row.isEmpty()) {
                        String access = anyStr(row.get(0)).toUpperCase(java.util.Locale.ROOT);
                        if (access.contains("ALL") || access.contains("CREATE USER")
                                || access.contains("ALTER USER") || access.contains("DROP USER")
                                || access.contains("GRANT")) {
                            admin = true;
                            break;
                        }
                    }
                }
            } catch (ApiException ignored) {
                // 无 system.grants 查询权限时按非管理员处理，遵循最小权限原则。
            }
        }
        return admin;
    }

    /** 判断是否为查询 ClickHouse 系统表时的权限不足，而非真实连接/SQL 故障。 */
    private static boolean isSystemPrivilegeDenied(ApiException e, String systemTable) {
        String message = Sql.messageOf(e).toLowerCase(java.util.Locale.ROOT);
        return message.contains("not enough privileges")
                && message.contains(systemTable.toLowerCase(java.util.Locale.ROOT));
    }

    /** 执行写语句。 */
    private static void exec(CkClient client, String sql) {
        try {
            // 直接执行写语句
            client.exec(sql);
        } catch (RuntimeException e) {
            // 只读账号执行 CREATE/ALTER/DROP/GRANT 时会返回很长的权限堆栈，
            // 转成稳定的业务提示，避免前端展示底层实现细节；权限不足不能被应用层绕过。
            if (isUserPrivilegeDenied(e)) {
                throw ApiException.readOnly("当前 ClickHouse 账号没有用户管理权限（只读账号不能修改、删除或授权用户）");
            }
            throw ApiException.badGateway(Sql.messageOf(e));
        }
    }

    /** 判断用户管理语句是否因 ClickHouse 账号权限/只读限制被拒绝。 */
    private static boolean isUserPrivilegeDenied(Throwable e) {
        String message = Sql.messageOf(e).toLowerCase(java.util.Locale.ROOT);
        return message.contains("not enough privileges")
                || message.contains("read_only")
                || message.contains("readonly")
                || message.contains("access denied")
                || message.contains("permission denied");
    }
}
