/**
 * 【文件职责】本文件定义认证与资源授权模块的业务服务 AccountSecurityService。承载该模块的核心业务规则，协调仓储、连接器或外部服务，并统一转换业务异常和操作状态。
 */

package com.el2ck.service.auth;
import com.el2ck.config.AuthContext;
import com.el2ck.entity.auth.AppUser;
import com.el2ck.exception.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import javax.sql.DataSource;
import java.util.*;
/**
 * AccountSecurityService 维护登录会话安全状态、身份版本、注销审计和密码变更规则。
 *
 * <p>方法的数据库写入、外部系统调用、事务、重试与并发边界以各方法实现为准；调用方应在入口层完成身份和参数校验。
 */
@Service
public class AccountSecurityService {
    private final JdbcTemplate jdbc;
    private final AppUserService users;
    private final com.el2ck.service.ck.CkIdentityService ckIdentities;
    /**
     * 创建账号安全与登录会话业务服务并装配后续调用所需的依赖。
     *
     * 依赖由 Spring 容器统一管理；本构造器只保存引用或初始化轻量适配对象，不执行远程业务操作。
     *
     * @param ds 平台元数据 JDBC 数据源，用于读取账号安全、归属和授权记录
     * @param users 平台用户服务，负责账号持久化、角色和密码校验
     * @param ckIdentities ClickHouse 用户身份服务，用于解析用户绑定与执行凭据
     */
    public AccountSecurityService(DataSource ds, AppUserService users,
                                  @org.springframework.context.annotation.Lazy com.el2ck.service.ck.CkIdentityService ckIdentities) {
        this.jdbc = new JdbcTemplate(ds); this.users = users; this.ckIdentities = ckIdentities;
    }
    /**
     * 读取用户的安全状态。
     *
     * 不存在安全状态行时返回兼容默认值：启用、无需首次改密、认证版本为 0；读取操作不创建数据库记录。
     *
     * @param id 用户 ID
     * @return 包含 enabled、must_change_password 和 auth_version 的状态映射
     */
    public Map<String,Object> state(long id) {
        List<Map<String,Object>> rows = jdbc.queryForList("SELECT enabled, must_change_password, auth_version FROM el_user_security WHERE user_id=?", id);
        if (!rows.isEmpty()) return rows.get(0);
        Map<String,Object> s = new HashMap<>();
        s.put("enabled", true); s.put("must_change_password", false); s.put("auth_version", 0L);
        return s;
    }
    /**
     * 判断用户当前是否允许登录。
     *
     * 该判断读取安全状态中的启用标志，并把数据库布尔值或非零数值统一解释为 true。
     *
     * @param id 用户 ID
     * @return 用户启用时为 true
     */
    public boolean enabled(long id) { return flag(state(id).get("enabled")); }
    /** 管理员账号不强制修改初始密码;普通用户仍按 must_change_password 约束。 */
    public boolean mustChange(long id) {
        if (isAdmin(id)) return false;
        return flag(state(id).get("must_change_password"));
    }
    /**
     * 判断用户是否为内置 admin 账号。
     *
     * @param id 用户 ID
     * @return 角色为 admin 时为 true
     */
    private boolean isAdmin(long id) {
        List<String> roles = jdbc.queryForList("SELECT role FROM app_users WHERE id=?", String.class, id);
        return !roles.isEmpty() && "admin".equals(roles.get(0));
    }
    /**
     * 读取用户当前认证版本。
     *
     * 认证版本变化会使旧会话失效；调用方常用该值确认账号安全状态是否仍然有效。
     *
     * @param id 用户 ID
     * @return 单调递增的认证版本号
     */
    public long version(long id) { return ((Number)state(id).get("auth_version")).longValue(); }
    /**
     * 将数据库布尔标志解释为 boolean，兼容 Boolean 和非零数值。
     *
     * @param v 数据库原始值
     * @return 真值标志
     */
    private boolean flag(Object v) { return Boolean.TRUE.equals(v) || v instanceof Number && ((Number)v).intValue() != 0; }
    /**
     * 确保用户安全状态行存在；已存在时不覆盖其现有字段。
     *
     * @param id 用户 ID
     */
    private void ensure(long id) {
        jdbc.update("INSERT INTO el_user_security(user_id) VALUES(?) ON DUPLICATE KEY UPDATE user_id=VALUES(user_id)", id);
    }
    /**
     * 写入一条资源访问审计记录。
     *
     * 调用者身份取自 AuthContext；没有请求身份时把目标用户作为操作者，以兼容登录等尚无上下文的流程。
     *
     * @param action 审计动作名
     * @param type 被操作资源类型
     * @param resourceId 资源 ID；登录等用户事件可为空
     * @param target 目标用户 ID，可为空
     */
    public void audit(String action, String type, Long resourceId, Long target) {
        AuthContext.Principal p = AuthContext.get();
        jdbc.update("INSERT INTO el_access_audit(actor_user_id,action,resource_type,resource_id,target_user_id) VALUES(?,?,?,?,?)",
            p == null ? target : p.getUserId(), action, type, resourceId, target);
    }
    /**
     * 使用户现有认证会话失效。
     *
     * 事务内确保安全状态行存在并把 auth_version 加一；本方法不直接删除会话，后续请求会因版本不一致被拒绝。
     *
     * @param id 用户 ID
     */
    @Transactional
    public void revoke(long id) {
        ensure(id); jdbc.update("UPDATE el_user_security SET auth_version=auth_version+1 WHERE user_id=?", id);
    }
    /**
     * 构造账号对外展示的安全身份视图。
     *
     * 合并用户基本字段、启用状态、首次改密要求和认证版本；不包含密码摘要或盐值。
     *
     * @param u 平台用户实体
     * @return 可直接返回给前端的脱敏身份映射
     */
    public Map<String,Object> identity(AppUser u) {
        Map<String,Object> r = new LinkedHashMap<>();
        r.put("userId",u.getId()); r.put("username",u.getUsername()); r.put("role",u.getRole());
        r.put("enabled",enabled(u.getId())); r.put("mustChangePassword",mustChange(u.getId()));
        // Keep the UI aligned with ResourceBoundaryInterceptor until execution has database-level isolation.
        // 真实 CK 身份可用后,任意 SQL 入口按用户绑定账号执行。没有 active 绑定时前端先收起执行入口。
        List<Map<String,Object>> bindings;
        try { bindings = ckIdentities.bindings(u.getId()); }
        catch (RuntimeException e) { bindings = Collections.emptyList(); }
        boolean ready = false;
        for (Map<String,Object> b : bindings) if ("active".equals(String.valueOf(b.get("status")))) { ready = true; break; }
        r.put("capabilities",Collections.singletonMap("sqlExecution",ready));
        r.put("ckBindings",bindings);
        return r;
    }
    /**
     * 记录一次成功登录。
     *
     * 确保安全状态行存在，更新最后登录时间并写入登录审计；调用前应由认证流程完成密码校验。
     *
     * @param id 已通过认证的用户 ID
     */
    public void loggedIn(long id) {
        ensure(id); jdbc.update("UPDATE el_user_security SET last_login_at=CURRENT_TIMESTAMP WHERE user_id=?",id);
        audit("login","user",id,id);
    }
    /**
     * 创建账号并初始化安全状态。
     *
     * 事务内委托用户服务创建账号，标记首次改密要求并写审计；任一步失败时整体回滚。
     *
     * @param name 用户名
     * @param password 初始密码明文
     * @param role 角色标识
     * @return 已持久化的平台用户
     */
    @Transactional
    public AppUser create(String name, String password, String role) {
        AppUser u=users.create(name,password,role); ensure(u.getId());
        jdbc.update("UPDATE el_user_security SET must_change_password=TRUE WHERE user_id=?",u.getId());
        audit("user.create","user",u.getId(),u.getId()); return u;
    }
    /**
     * 更新账号角色和启用状态。
     *
     * 事务先锁定管理员集合，防止并发操作同时禁用或降级最后一个有效管理员；成功后递增认证版本并写审计。
     *
     * @param id 用户 ID
     * @param role 目标角色
     * @param enabled 目标启用状态
     */
    @Transactional
    public void update(long id, String role, boolean enabled) {
        jdbc.queryForList("SELECT id FROM app_users WHERE role='admin' ORDER BY id FOR UPDATE");
        AppUser u=users.get(id);
        if ("admin".equals(u.getRole()) && enabled(id) && (!enabled || !"admin".equals(role))) {
            Long n=jdbc.queryForObject("SELECT COUNT(*) FROM app_users u LEFT JOIN el_user_security s ON s.user_id=u.id WHERE u.role='admin' AND COALESCE(s.enabled,TRUE)=TRUE",Long.class);
            if(n==null || n<=1) throw ApiException.validation("不能禁用或降级最后一个有效管理员");
        }
        users.updateRole(id,role); ensure(id);
        jdbc.update("UPDATE el_user_security SET enabled=?,auth_version=auth_version+1 WHERE user_id=?",enabled,id);
        audit("user.update","user",id,id);
    }
    /**
     * 由管理员重置账号密码。
     *
     * 事务内更新密码摘要、递增认证版本并强制首次改密；旧会话将在后续鉴权时失效。
     *
     * @param id 用户 ID
     * @param password 新密码明文
     */
    @Transactional
    public void resetPassword(long id,String password) {
        users.resetPassword(id,password); revoke(id);
        jdbc.update("UPDATE el_user_security SET must_change_password=TRUE WHERE user_id=?",id);
        audit("password.reset","user",id,id);
    }
    /**
     * 修改当前登录用户的密码。
     *
     * 先用旧密码校验身份，成功后更新摘要、递增认证版本并清除首次改密要求；旧密码错误会抛出业务异常且不更新状态。
     *
     * @param oldPassword 当前密码明文
     * @param password 新密码明文
     */
    @Transactional
    public void changePassword(String oldPassword,String password) {
        long id=AuthContext.userId();
        users.changeOwnPassword(users.get(id).getUsername(),oldPassword,password); revoke(id);
        jdbc.update("UPDATE el_user_security SET must_change_password=FALSE WHERE user_id=?",id);
        audit("password.change","user",id,id);
    }
}
