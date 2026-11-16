/**
 * 【文件职责】本文件定义表管理与数据编辑模块的HTTP 控制器 TableGroupController。接收 Web 请求并完成路由与参数适配，统一封装响应；核心校验、权限和持久化逻辑委托给服务层。
 */

package com.el2ck.controller.table;

import com.el2ck.controller.common.ApiResponse;
import com.el2ck.controller.common.ApiSupport;

import com.el2ck.exception.ApiException;
import com.el2ck.service.table.TableGroupService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;

/**
 * 表分组 HTTP 接口(对应 Go internal/api/tbl_group_api.go)。
 *
 * 五条路径分属三个前缀(/api/connections/{id}/databases/{db}/table-groups、/api/table-groups/{id}、
 * /api/connections/{id}/databases/{db}/tables/{table}/group),故类上只声明 /api。
 * 名称合法性、连接与分组的记录存在性校验均在 TableGroupService 内完成。
 *
 * 供前端「数据仓库工作台」库树按分组归类表;Basic Auth 保护(配置了 user 时)。
 */
@RestController
@RequestMapping("/api")
public class TableGroupController {
    // 依赖的表分组服务:分组的增删改查,以及"表挂到哪个分组"的关联维护
    private final TableGroupService service;

    // 构造器注入表分组服务
    /**
     * 创建表分组 HTTP 控制器并装配后续调用所需的依赖。
     *
     * 依赖由 Spring 容器统一管理；本构造器只保存引用或初始化轻量适配对象，不执行远程业务操作。
     *
     * @param service 该控制器对应的领域业务服务，承载实际校验和数据处理
     */
    public TableGroupController(TableGroupService service) {
        // 保存服务引用供各接口调用
        this.service = service;
    }

    // 分组新增/重命名共用的请求体(与数据库分组同构)
    public static class NameRequest {
        // 分组名称:必填,空白或超过 50 字由服务层判 400
        public String name;
        // 分组备注:可空,保存前会去空白
        public String remark;
    }

    // "把表归入某分组"的请求体
    public static class TableGroupRequest {
        // 目标分组 ID:0 表示移出分组回到「未分组」,负数由服务层判 400
        public Long groupId;
    }

    /** GET /api/connections/{id}/databases/{db}/table-groups 分组列表(含成员表)。 */
    @GetMapping("/connections/{id}/databases/{db}/table-groups")
    public ApiResponse list(@PathVariable("id") String id, @PathVariable("db") String db) {
        // 连接不存在由服务层抛 404
        return ApiResponse.ok(service.list(ApiSupport.parseId(id), db));
    }

    /** POST /api/connections/{id}/databases/{db}/table-groups 新增分组。 */
    @PostMapping("/connections/{id}/databases/{db}/table-groups")
    public ApiResponse create(@PathVariable("id") String id, @PathVariable("db") String db,
                              @RequestBody(required = false) NameRequest body) {
        // 请求体可缺省(required=false):整段缺失等同于名称为空 → 服务层 400
        return ApiResponse.ok(service.create(ApiSupport.parseId(id), db,
                body == null ? null : body.name, body == null ? null : body.remark));
    }

    /** PUT /api/table-groups/{id} 修改分组。 */
    @PutMapping("/table-groups/{id}")
    public ApiResponse update(@PathVariable String id, @RequestBody(required = false) NameRequest body) {
        // 同新增:请求体可为空;目标分组不存在时由服务层抛 404
        return ApiResponse.ok(service.update(ApiSupport.parseId(id),
                body == null ? null : body.name, body == null ? null : body.remark));
    }

    /** DELETE /api/table-groups/{id} 删除分组。 */
    @DeleteMapping("/table-groups/{id}")
    public ApiResponse delete(@PathVariable String id) {
        long groupId = ApiSupport.parseId(id);
        service.delete(groupId);
        // 与 Go 一致:回显被删除的分组 ID
        return ApiResponse.ok(Collections.singletonMap("id", groupId));
    }

    /** PUT /api/connections/{id}/databases/{db}/tables/{table}/group 设置表所属分组。 */
    @PutMapping("/connections/{id}/databases/{db}/tables/{table}/group")
    public ApiResponse setTableGroup(@PathVariable("id") String id, @PathVariable("db") String db,
                                     @PathVariable("table") String table,
                                     @RequestBody(required = false) TableGroupRequest body) {
        // 未传请求体或未带 groupId 一律 400;groupId=0 是合法值,表示移出分组
        if (body == null || body.groupId == null) throw ApiException.validation("请指定 groupId，0 表示未分组");
        // 目标分组不存在 / 不属于该库由服务层抛 404 / 400
        service.setTableGroup(ApiSupport.parseId(id), db, table, body.groupId);
        return ApiResponse.ok(Collections.singletonMap("ok", Boolean.TRUE));
    }
}
