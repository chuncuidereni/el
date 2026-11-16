/**
 * 【文件职责】本文件定义用户与授权规则模块的HTTP 控制器 UserController。接收 Web 请求并完成路由与参数适配，统一封装响应；核心校验、权限和持久化逻辑委托给服务层。
 */

package com.el2ck.controller.user;

import com.el2ck.controller.common.ApiResponse;
import com.el2ck.controller.common.ApiSupport;

import com.el2ck.entity.user.UserInput;
import com.el2ck.service.user.UserService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.Map;

/**
 * ClickHouse 用户/授权 HTTP 接口(对应 Go internal/api/user_api.go)。
 *
 * 供前端「数据仓库工作台」的「用户管理」标签(/workspace)使用:用户列表、当前连接账号角色
 * (capability)、授权规则(grants)与用户增删改;Basic Auth 保护,实际能力受连接账号在
 * 目标 ClickHouse 上的权限约束。
 */
@RestController
@RequestMapping("/api/connections/{id}/users")
public class UserController {

    // 依赖的用户服务:负责目标库用户与授权规则管理
    private final UserService svc;

    // 构造器注入用户服务
    /**
     * 创建ClickHouse 用户管理 HTTP 控制器并装配后续调用所需的依赖。
     *
     * 依赖由 Spring 容器统一管理；本构造器只保存引用或初始化轻量适配对象，不执行远程业务操作。
     *
     * @param svc 该控制器对应的领域业务服务，承载实际校验和数据处理
     */
    public UserController(UserService svc) {
        // 保存服务引用供接口调用
        this.svc = svc;
    }

    /** GET /api/connections/{id}/users 用户列表。 */
    @GetMapping
    public ApiResponse list(@PathVariable("id") String id) {
        // 列出目标连接下的全部用户
        return ApiResponse.ok(svc.listUsers(ApiSupport.parseId(id)));
    }

    /** GET /api/connections/{id}/users/capability 当前账号角色。 */
    @GetMapping("/capability")
    public ApiResponse capability(@PathVariable("id") String id) {
        // "当前账号"指该连接配置里的 ClickHouse 账号(非平台登录用户),服务层综合授权与 readonly 设置返回
        // 中文角色「管理员/读写/只读」;前端据此决定用户管理页的按钮是否可用
        String role = svc.currentRole(ApiSupport.parseId(id));
        // 包成 {role:"..."} 以对齐 Go 前端的键名
        return ApiResponse.ok(Collections.singletonMap("role", role));
    }

    /** GET /api/connections/{id}/users/{name}/grants 用户授权规则。 */
    @GetMapping("/{name}/grants")
    public ApiResponse grants(@PathVariable("id") String id, @PathVariable("name") String name) {
        // 查询指定用户的授权规则列表
        return ApiResponse.ok(svc.getGrants(ApiSupport.parseId(id), name));
    }

    /** POST /api/connections/{id}/users 创建用户。 */
    @PostMapping
    public ApiResponse create(@PathVariable("id") String id, @RequestBody(required = false) UserInput in) {
        // 请求体可能为空,取用户名做空保护
        String name = in == null ? null : in.getName();
        // 在目标连接上创建用户并授权
        svc.createUser(ApiSupport.parseId(id), in);
        // 返回新建用户名供前端确认
        return ApiResponse.ok(Collections.singletonMap("name", name));
    }

    /** PUT /api/connections/{id}/users/{name} 更新用户。 */
    @PutMapping("/{name}")
    public ApiResponse update(@PathVariable("id") String id, @PathVariable("name") String name,
                              @RequestBody(required = false) UserInput in) {
        // 更新指定用户的密码/授权
        svc.updateUser(ApiSupport.parseId(id), name, in);
        // 更新成功返回 {ok:true}
        return ApiResponse.ok(okMap());
    }

    /** DELETE /api/connections/{id}/users/{name} 删除用户。 */
    @DeleteMapping("/{name}")
    public ApiResponse delete(@PathVariable("id") String id, @PathVariable("name") String name) {
        // 删除指定用户
        svc.dropUser(ApiSupport.parseId(id), name);
        // 删除成功返回 {ok:true}
        return ApiResponse.ok(okMap());
    }

    // 便捷工具:组装单键 {ok:true} 的 Map
    /**
     * 构造控制器成功响应使用的单键映射。
     *
     * @return 仅包含 {@code ok=true} 的不可变映射
     */
    private static Map<String, Boolean> okMap() {
        // 布尔单键 Map 表示操作成功
        return Collections.singletonMap("ok", Boolean.TRUE);
    }
}
