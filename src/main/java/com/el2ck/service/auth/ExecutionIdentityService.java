/**
 * 【文件职责】本文件定义认证与资源授权模块的业务服务 ExecutionIdentityService。承载该模块的核心业务规则，协调仓储、连接器或外部服务，并统一转换业务异常和操作状态。
 */

package com.el2ck.service.auth;

import com.el2ck.config.AuthContext;
import com.el2ck.entity.auth.AppUser;
import com.el2ck.exception.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import javax.sql.DataSource;
import java.util.*;
import java.util.function.Supplier;

/** Background jobs resolve a persisted owner at execution time; callers cannot provide a user ID. */
@Service
public class ExecutionIdentityService {
    private final ResourceAccessService access;
    private final AppUserService users;
    private final AccountSecurityService accounts;
    private final JdbcTemplate jdbc;
    /**
     * 创建后台任务所有者身份执行服务并装配后续调用所需的依赖。
     *
     * 依赖由 Spring 容器统一管理；本构造器只保存引用或初始化轻量适配对象，不执行远程业务操作。
     *
     * @param access 资源访问服务，用于校验资源所有权、可见范围和共享关系
     * @param users 平台用户服务，负责账号持久化、角色和密码校验
     * @param accounts 账号安全服务，用于校验启用状态、首次改密和会话版本
     * @param ds 平台元数据 JDBC 数据源，用于读取账号安全、归属和授权记录
     */
    public ExecutionIdentityService(ResourceAccessService access,AppUserService users,AccountSecurityService accounts,DataSource ds) {
        this.access=access;this.users=users;this.accounts=accounts;this.jdbc=new JdbcTemplate(ds);
    }
    /**
     * 以持久化资源所有者的身份执行一段受控操作。
     *
     * 执行前校验所有者已分配、账号启用且完成首次改密；临时设置 AuthContext，并在 finally 中恢复原身份。该方法不会接受调用方传入的用户 ID。
     *
     * @param type 资源类型
     * @param resourceId 资源 ID
     * @param action 需要以所有者身份执行的回调
     * @return 回调返回的业务结果
     */
    public <T> T asOwner(String type,long resourceId,Supplier<T> action) {
        long owner=access.owner(type,resourceId);
        if(owner<=0) throw new ApiException(403,"任务尚未分配所有者");
        AppUser user=users.get(owner);
        if(!accounts.enabled(owner) || accounts.mustChange(owner)) throw new ApiException(403,"任务所有者未启用或尚未完成首次改密");
        AuthContext.Principal previous=AuthContext.get();
        try {
            AuthContext.set(owner,user.getUsername(),user.getRole());
            return action.get();
        } finally {
            if(previous==null) AuthContext.clear();
            else AuthContext.set(previous.getUserId(),previous.getUsername(),previous.getRole());
        }
    }
    /** Internal scheduler inventory contains scheduling metadata only, never business data. */
    public Map<Long,String> scheduledSyncs() {
        Map<Long,String> result=new LinkedHashMap<>();
        jdbc.query("SELECT t.id,t.cron_expr FROM sync_tasks t JOIN el_resource_owners o ON o.resource_type='sync-task' AND o.resource_id=t.id JOIN app_users u ON u.id=o.owner_user_id LEFT JOIN el_user_security s ON s.user_id=u.id WHERE t.enabled=1 AND t.schedule_type='scheduled' AND COALESCE(s.enabled,TRUE)=TRUE AND COALESCE(s.must_change_password,FALSE)=FALSE",
            rs->{result.put(rs.getLong(1),rs.getString(2));});
        return result;
    }
}
