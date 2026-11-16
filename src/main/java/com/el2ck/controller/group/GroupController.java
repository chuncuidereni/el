/**
 * 【文件职责】本文件定义分组管理模块的HTTP 控制器 GroupController。接收 Web 请求并完成路由与参数适配，统一封装响应；核心校验、权限和持久化逻辑委托给服务层。
 */

package com.el2ck.controller.group;

import com.el2ck.controller.common.ApiResponse;
import com.el2ck.controller.common.ApiSupport;

import com.el2ck.exception.ApiException;
import com.el2ck.service.group.GroupService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;

/**
 * 分组管理 HTTP 接口(对应 Go internal/api/ds_group_api.go 与 internal/api/group_api.go)。
 *
 * 含两套彼此独立的分组:数据源分组(/api/datasource-groups、/api/datasources/{id}/group)
 * 与数据库分组(/api/connections/{id}/groups、/api/groups/{id});两者路径前缀不同,
 * 故类上只声明 /api。名称合法性、连接与分组的记录存在性校验均在 GroupService 内完成。
 *
 * 供前端「数据源管理」与「连接管理」页的分组归类/筛选调用;Basic Auth 保护(配置了 user 时)。
 */
@RestController
@RequestMapping("/api")
public class GroupController {
    // 依赖的分组服务:分组增删改查,以及"数据源/库 挂到哪个分组"的关联维护
    private final GroupService service;

    // 构造器注入分组服务
    /**
     * 创建数据源分组 HTTP 控制器并装配后续调用所需的依赖。
     *
     * 依赖由 Spring 容器统一管理；本构造器只保存引用或初始化轻量适配对象，不执行远程业务操作。
     *
     * @param service 该控制器对应的领域业务服务，承载实际校验和数据处理
     */
    public GroupController(GroupService service) {
        // 保存服务引用供各接口调用
        this.service = service;
    }

    // 分组新增/重命名共用的请求体(两套分组字段相同)
    public static class NameRequest {
        // 分组名称:必填,空白或超过 50 字由服务层判 400
        public String name;
        // 分组备注:可空,保存前会去空白
        public String remark;
    }

    // "设置分组包含哪些数据库"的请求体
    public static class DatabasesRequest {
        // 库名清单;为 null 视为空清单,即清空该分组原有的全部绑定
        public List<String> dbs;
    }

    // "把数据源归入某分组"的请求体
    /**
     * 将数据源绑定到分组时使用的请求体。
     *
     * <p>{@code groupId=0} 表示移出当前分组；字段为 null 时控制器返回 400。
     */
    public static class DataSourceGroupRequest {
        // 目标分组 ID:0 表示默认分组(即移出分组),负数由服务层判 400
        public Long groupId;
    }

    /** GET /api/datasource-groups 数据源分组列表(含各自的数据源数量,供筛选下拉使用)。 */
    @GetMapping("/datasource-groups")
    public ApiResponse listDataSourceGroups() {
        return ApiResponse.ok(service.listDataSourceGroups());
    }

    /** POST /api/datasource-groups 新增数据源分组;名称为空或超长由服务层判 400。 */
    @PostMapping("/datasource-groups")
    public ApiResponse createDataSourceGroup(@RequestBody(required = false) NameRequest body) {
        // 请求体可缺省(required=false):逐字段做空保护,整段缺失等同于名称为空 → 服务层 400
        return ApiResponse.ok(service.createDataSourceGroup(body == null ? null : body.name,
                body == null ? null : body.remark));
    }

    /** PUT /api/datasource-groups/{id} 重命名数据源分组或改备注;分组不存在由服务层判 404。 */
    @PutMapping("/datasource-groups/{id}")
    public ApiResponse updateDataSourceGroup(@PathVariable String id, @RequestBody(required = false) NameRequest body) {
        // 同新增:请求体可为空;目标分组不存在时由服务层抛 404
        return ApiResponse.ok(service.updateDataSourceGroup(ApiSupport.parseId(id),
                body == null ? null : body.name, body == null ? null : body.remark));
    }

    /** DELETE /api/datasource-groups/{id} 删除数据源分组;回显被删除的分组 ID。 */
    @DeleteMapping("/datasource-groups/{id}")
    public ApiResponse deleteDataSourceGroup(@PathVariable String id) {
        long groupId = ApiSupport.parseId(id);
        service.deleteDataSourceGroup(groupId);
        return ApiResponse.ok(Collections.singletonMap("id", groupId));
    }

    /** PUT /api/datasources/{id}/group 把单个数据源归入某分组;groupId=0 表示移出分组回到默认分组。 */
    @PutMapping("/datasources/{id}/group")
    public ApiResponse setDataSourceGroup(@PathVariable String id,
                                           @RequestBody(required = false) DataSourceGroupRequest body) {
        // 未传请求体或未带 groupId 一律 400;groupId=0 是合法值,表示移出分组回到默认分组
        if (body == null || body.groupId == null) throw ApiException.validation("请指定 groupId，0 表示默认分组");
        // 数据源不存在 / 目标分组不存在由服务层抛 404
        service.setDataSourceGroup(ApiSupport.parseId(id), body.groupId);
        return ApiResponse.ok(Collections.singletonMap("ok", true));
    }

    /** GET /api/connections/{id}/groups 指定连接下的数据库分组列表(含成员库);连接不存在由服务层判 404。 */
    @GetMapping("/connections/{id}/groups")
    public ApiResponse listDatabaseGroups(@PathVariable String id) {
        return ApiResponse.ok(service.listDatabaseGroups(ApiSupport.parseId(id)));
    }

    /** POST /api/connections/{id}/groups 在指定连接下新增数据库分组;连接须存在(否则 404)。 */
    @PostMapping("/connections/{id}/groups")
    public ApiResponse createDatabaseGroup(@PathVariable String id, @RequestBody(required = false) NameRequest body) {
        // 连接须存在(否则 404);请求体可缺省,空保护后按空名称由服务层校验
        return ApiResponse.ok(service.createDatabaseGroup(ApiSupport.parseId(id), body == null ? null : body.name,
                body == null ? null : body.remark));
    }

    /** PUT /api/groups/{id} 重命名数据库分组或改备注;分组不存在由服务层判 404。 */
    @PutMapping("/groups/{id}")
    public ApiResponse updateDatabaseGroup(@PathVariable String id, @RequestBody(required = false) NameRequest body) {
        // 同新增:请求体可为空;分组不存在时由服务层抛 404
        return ApiResponse.ok(service.updateDatabaseGroup(ApiSupport.parseId(id), body == null ? null : body.name,
                body == null ? null : body.remark));
    }

    /** DELETE /api/groups/{id} 删除数据库分组;回显被删除的分组 ID。 */
    @DeleteMapping("/groups/{id}")
    public ApiResponse deleteDatabaseGroup(@PathVariable String id) {
        long groupId = ApiSupport.parseId(id);
        service.deleteDatabaseGroup(groupId);
        return ApiResponse.ok(Collections.singletonMap("id", groupId));
    }

    /** PUT /api/groups/{id}/dbs 整体覆盖分组绑定的成员数据库(传空清单即清空绑定);分组不存在由服务层判 404。 */
    @PutMapping("/groups/{id}/dbs")
    public ApiResponse setGroupDatabases(@PathVariable String id, @RequestBody(required = false) DatabasesRequest body) {
        // 请求体必填(要取 dbs 字段);注意 dbs 为 null 不等于"不改动",服务层按空清单处理即清空绑定
        if (body == null) throw ApiException.validation("请指定 dbs 数组");
        // 库名去空白、去重后整体覆盖式写入;分组不存在由服务层抛 404
        service.setGroupDatabases(ApiSupport.parseId(id), body.dbs);
        return ApiResponse.ok(Collections.singletonMap("ok", true));
    }
}
