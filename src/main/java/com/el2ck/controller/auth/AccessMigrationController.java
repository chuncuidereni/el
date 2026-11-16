/**
 * 【文件职责】本文件定义认证与资源授权模块的HTTP 控制器 AccessMigrationController。接收 Web 请求并完成路由与参数适配，统一封装响应；核心校验、权限和持久化逻辑委托给服务层。
 */

package com.el2ck.controller.auth;
import com.el2ck.config.AuthContext;
import com.el2ck.controller.common.ApiResponse;
import com.el2ck.exception.ApiException;
import com.el2ck.service.auth.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import javax.sql.DataSource;
import java.util.*;

/** Explicit, audited initial ownership assignment. Never assigns to the first visitor. */
@RestController
@RequestMapping("/api/access-migration")
public class AccessMigrationController {
    private final JdbcTemplate jdbc;
    private final ResourceAccessService access;
    private final AppUserService users;
    private final AccountSecurityService accounts;
    @org.springframework.beans.factory.annotation.Autowired
    private com.el2ck.service.connection.ConnectionService connections;
    @org.springframework.beans.factory.annotation.Autowired
    private com.el2ck.service.ck.CkAccessProvisioner provisioner;
    @org.springframework.beans.factory.annotation.Autowired
    private com.el2ck.service.ck.CkIdentityService identities;
    /**
     * 创建资源归属迁移 HTTP 控制器并装配后续调用所需的依赖。
     *
     * 依赖由 Spring 容器统一管理；本构造器只保存引用或初始化轻量适配对象，不执行远程业务操作。
     *
     * @param ds 平台元数据 JDBC 数据源，用于读取账号安全、归属和授权记录
     * @param access 资源访问服务，用于校验资源所有权、可见范围和共享关系
     * @param users 平台用户服务，负责账号持久化、角色和密码校验
     * @param accounts 账号安全服务，用于校验启用状态、首次改密和会话版本
     */
    public AccessMigrationController(DataSource ds,ResourceAccessService access,AppUserService users,AccountSecurityService accounts) {
        this.jdbc=new JdbcTemplate(ds);this.access=access;this.users=users;this.accounts=accounts;
    }
    /**
     * 要求当前请求具备内置管理员角色；未登录或非管理员统一返回 403。
     */
    private void admin() {if(AuthContext.get()==null || !"admin".equals(AuthContext.get().getRole()))throw new ApiException(403,"仅管理员可以分配历史资源");}
    /**
     * 处理 {@code GET /api/access-migration/datasources} 请求：列出尚未分配所有者的数据源资源。
     *
     * 供管理员迁移历史资源归属；返回内容只包含元数据，不修改资源。
     *
     * @return 统一 {@link com.el2ck.controller.common.ApiResponse}；成功时封装业务结果，失败由全局异常处理器转换
     */
    @GetMapping("/datasources")
    public ApiResponse unassigned() {
        admin();return ApiResponse.ok(jdbc.queryForList("SELECT d.id,d.name,d.type FROM data_sources d WHERE NOT EXISTS(SELECT 1 FROM el_resource_owners o WHERE o.resource_type='datasource' AND o.resource_id=d.id) ORDER BY d.id"));
    }
    /**
     * 处理 {@code POST /api/access-migration/datasources/{id}} 请求：为未归属的数据源指定所有者。
     *
     * 该端点开启事务；归属写入与审计在同一事务中完成，校验失败时整体回滚。
     *
     * @param id 用户 ID
     * @param input input 参数；其具体约束由该端点调用链校验
     * @return 统一 {@link com.el2ck.controller.common.ApiResponse}；成功时封装业务结果，失败由全局异常处理器转换
     */
    @PostMapping("/datasources/{id}")
    @Transactional
    public ApiResponse assign(@PathVariable long id,@RequestBody AssignInput input) {
        admin();users.get(input.userId);
        if(!accounts.enabled(input.userId))throw ApiException.validation("不能分配给禁用用户");
        if(jdbc.queryForList("SELECT id FROM data_sources WHERE id=? FOR UPDATE",id).isEmpty())throw ApiException.notFound("数据源不存在");
        if(access.owner("datasource",id)>0)throw ApiException.validation("资源已有所有者，不能重复分配");
        jdbc.update("INSERT INTO el_resource_owners(resource_type,resource_id,owner_user_id) VALUES('datasource',?,?)",id,input.userId);
        jdbc.update("UPDATE data_sources SET group_id=0 WHERE id=?",id);
        accounts.audit("ownership.assign","datasource",id,input.userId);
        return ApiResponse.ok();
    }
    /**
     * 处理 {@code POST /api/access-migration/tables} 请求：登记一个既有 ClickHouse 物理表并建立资产归属。
     *
     * 服务层校验实例、库表和所有者后创建资产记录；重复或非法表会返回业务错误。
     *
     * @param input input 参数；其具体约束由该端点调用链校验
     * @return 统一 {@link com.el2ck.controller.common.ApiResponse}；成功时封装业务结果，失败由全局异常处理器转换
     */
    @PostMapping("/tables")
    public ApiResponse registerTable(@RequestBody TableInput input) {
        admin();users.get(input.userId);
        if(!accounts.enabled(input.userId))throw ApiException.validation("不能分配给禁用用户");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM connections WHERE id=?",Long.class,input.connectionId)==0)throw ApiException.notFound("连接不存在");
        if(input.database==null || input.database.trim().isEmpty() || input.table==null || input.table.trim().isEmpty())
            throw ApiException.validation("请选择数据库和表");
        com.el2ck.ckpool.CkClient probe = identities != null
                ? identities.managementClient(input.connectionId) : connections.client(input.connectionId);
        if(probe.query("SELECT name FROM system.tables WHERE database=? AND name=?",
                Arrays.<Object>asList(input.database,input.table)).getRows().isEmpty())
            throw ApiException.notFound("指定的表不存在");
        // Catalog registration grants access only to this exact table; no implicit database-wide visibility.
        long id=access.registerTable("clickhouse",input.connectionId,input.database,input.table,input.userId);
        // 历史表的创建者未知,不能把接收人伪装成创建人。
        jdbc.update("UPDATE el_data_assets SET creator_user_id=NULL,creator_name_snapshot='历史数据',origin_type='history',updated_at=CURRENT_TIMESTAMP WHERE id=?",id);
        // 分配后立即建立真实 CK 表级授权;失败时保留资产但返回错误,管理员可重试。
        provisioner.grantOwner(id);
        return ApiResponse.ok(Collections.singletonMap("id",id));
    }
    public static class AssignInput {public long userId;}
    /**
     * 处理 {@code GET /api/access-migration/sql-queries} 请求：列出尚未分配所有者的 SQL 查询定义。
     *
     * 用于管理员迁移旧查询资源，返回查询元数据供人工确认。
     *
     * @return 统一 {@link com.el2ck.controller.common.ApiResponse}；成功时封装业务结果，失败由全局异常处理器转换
     */
    @GetMapping("/sql-queries")
    public ApiResponse unassignedSqlQueries() {
        admin();
        return ApiResponse.ok(jdbc.queryForList("SELECT q.id,q.name FROM sql_queries q WHERE NOT EXISTS(SELECT 1 FROM el_resource_owners o WHERE o.resource_type='sql-query' AND o.resource_id=q.id) ORDER BY q.id"));
    }
    /**
     * 处理 {@code POST /api/access-migration/sql-queries/{id}} 请求：为未归属的 SQL 查询指定所有者。
     *
     * 端点开启事务，归属和审计同步提交；任何一步失败都会回滚。
     *
     * @param id 用户 ID
     * @param input input 参数；其具体约束由该端点调用链校验
     * @return 统一 {@link com.el2ck.controller.common.ApiResponse}；成功时封装业务结果，失败由全局异常处理器转换
     */
    @PostMapping("/sql-queries/{id}")
    @Transactional
    public ApiResponse assignSqlQuery(@PathVariable long id,@RequestBody AssignInput input) {
        admin();users.get(input.userId);
        if(!accounts.enabled(input.userId))throw ApiException.validation("不能分配给禁用用户");
        if(jdbc.queryForList("SELECT id FROM sql_queries WHERE id=? FOR UPDATE",id).isEmpty())throw ApiException.notFound("SQL 查询不存在");
        if(access.owner("sql-query",id)>0)throw ApiException.validation("SQL 查询已有所有者，不能重复分配");
        jdbc.update("UPDATE sql_queries SET group_id=0 WHERE id=?",id);
        jdbc.update("INSERT INTO el_resource_owners(resource_type,resource_id,owner_user_id) VALUES('sql-query',?,?)",id,input.userId);
        accounts.audit("ownership.assign","sql-query",id,input.userId);
        return ApiResponse.ok();
    }
    /**
     * 将访问迁移接口中的资源类型映射为对应任务表的物理表名。
     *
     * <p>该映射只接受四类已知任务；未知类型属于客户端参数错误，统一抛出 400，
     * 避免把外部输入直接拼进 SQL 标识符。
     *
     * @param type 任务资源类型
     * @return 对应的元数据任务表名
     */
    private String taskTable(String type) {
        switch(type) {
            case "sync-task":return "sync_tasks";
            case "workflow":return "wf_tasks";
            case "arb-workflow":return "arb_workflows";
            case "query-workflow":return "query_workflows";
            default:throw ApiException.validation("不支持的任务类型");
        }
    }
    /**
     * 处理 {@code GET /api/access-migration/tasks} 请求：按任务类型列出尚未分配所有者的后台任务。
     *
     * type 决定查询同步任务还是其他受支持任务；未知类型由服务层拒绝。
     *
     * @param type 资源类型或数据源类型
     * @return 统一 {@link com.el2ck.controller.common.ApiResponse}；成功时封装业务结果，失败由全局异常处理器转换
     */
    @GetMapping("/tasks")
    public ApiResponse unassignedTasks(@RequestParam String type) {
        admin();String table=taskTable(type);
        return ApiResponse.ok(jdbc.queryForList("SELECT t.id,t.name FROM "+table+" t WHERE NOT EXISTS(SELECT 1 FROM el_resource_owners o WHERE o.resource_type=? AND o.resource_id=t.id) ORDER BY t.id",type));
    }
    /**
     * 处理 {@code POST /api/access-migration/tasks/{type}/{id}} 请求：为指定类型的未归属任务指定所有者。
     *
     * 端点开启事务；会校验任务存在、所有者有效并写入审计，失败时回滚。
     *
     * @param type 资源类型或数据源类型
     * @param id 用户 ID
     * @param input input 参数；其具体约束由该端点调用链校验
     * @return 统一 {@link com.el2ck.controller.common.ApiResponse}；成功时封装业务结果，失败由全局异常处理器转换
     */
    @PostMapping("/tasks/{type}/{id}")
    @Transactional
    public ApiResponse assignTask(@PathVariable String type,@PathVariable long id,@RequestBody AssignInput input) {
        admin();String table=taskTable(type);users.get(input.userId);
        if(!accounts.enabled(input.userId))throw ApiException.validation("不能分配给禁用用户");
        if(jdbc.queryForList("SELECT id FROM "+table+" WHERE id=? FOR UPDATE",id).isEmpty())throw ApiException.notFound("任务不存在");
        if(access.owner(type,id)>0)throw ApiException.validation("任务已有所有者，不能重复分配");
        if("sync-task".equals(type)) {
            Long sourceId=jdbc.queryForObject("SELECT data_source_id FROM sync_tasks WHERE id=?",Long.class,id);
            if(sourceId==null || access.owner("datasource",sourceId)!=input.userId)throw ApiException.validation("请先将该任务的数据源分配给同一用户");
        }
        // Historical tasks stay disabled until the owner reviews and explicitly enables them.
        jdbc.update("UPDATE "+table+" SET enabled=0 WHERE id=?",id);
        jdbc.update("INSERT INTO el_resource_owners(resource_type,resource_id,owner_user_id) VALUES(?,?,?)",type,id,input.userId);
        accounts.audit("ownership.assign",type,id,input.userId);
        return ApiResponse.ok();
    }
    public static class TableInput {public long userId;public long connectionId;public String database;public String table;}
}
