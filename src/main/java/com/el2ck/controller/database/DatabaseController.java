/**
 * 【文件职责】本文件定义数据库目录浏览模块的HTTP 控制器 DatabaseController。接收 Web 请求并完成路由与参数适配，统一封装响应；核心校验、权限和持久化逻辑委托给服务层。
 */

package com.el2ck.controller.database;

import com.el2ck.controller.common.ApiResponse;
import com.el2ck.controller.common.ApiSupport;

import com.el2ck.service.database.DatabaseService;
import com.el2ck.service.table.TableService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;

/**
 * 数据库管理 HTTP 接口(对应 Go internal/api/database_api.go + table_api.Stats)。
 *
 * 在指定连接下列出/新建/删除数据库、读取库内各表统计,供前端「数据仓库工作台」(/workspace)
 * 库树区使用;所有到目标 ClickHouse 的操作失败统一 502(服务层抛 badGateway)。Basic Auth 保护(配置了 user 时)。
 */
@RestController
@RequestMapping("/api/connections/{id}/databases")
public class DatabaseController {

    // 依赖的数据库服务:负责库列表、建库与删库
    private final DatabaseService svc;

    // 依赖的表服务:库内表统计的路由前缀属于 databases,但取数与缓存按表来(对应 Go tableAPI.Stats)
    private final TableService tableSvc;

    // 构造器注入数据库服务与表服务
    /**
     * 创建数据库浏览 HTTP 控制器并装配后续调用所需的依赖。
     *
     * 依赖由 Spring 容器统一管理；本构造器只保存引用或初始化轻量适配对象，不执行远程业务操作。
     *
     * @param svc 该控制器对应的领域业务服务，承载实际校验和数据处理
     * @param tableSvc 表业务服务，用于查询表信息和执行表操作
     */
    public DatabaseController(DatabaseService svc, TableService tableSvc) {
        // 保存数据库服务引用供库级接口调用
        this.svc = svc;
        // 保存表服务引用供表统计接口调用
        this.tableSvc = tableSvc;
    }

    /** 数据库新建请求体(对应 Go 内联 struct)。 */
    public static class CreateReq {
        // 库名
        public String name;
        // 备注说明
        public String remark;
    }

    /** GET /api/connections/{id}/databases 列出数据库。 */
    @GetMapping
    public ApiResponse list(@PathVariable("id") String id) {
        // 解析连接 ID 后列出其下全部数据库
        return ApiResponse.ok(svc.list(ApiSupport.parseId(id)));
    }

    /** POST /api/connections/{id}/databases 新建数据库。 */
    @PostMapping
    public ApiResponse create(@PathVariable("id") String id, @RequestBody(required = false) CreateReq body) {
        // 解析连接数字 ID
        long cid = ApiSupport.parseId(id);
        // 请求体可能为空,逐字段做空保护取值
        String name = body == null ? null : body.name;
        String remark = body == null ? null : body.remark;
        // 在目标连接上执行建库
        svc.create(cid, name, remark);
        // 返回新建库名供前端确认
        return ApiResponse.ok(Collections.singletonMap("name", name));
    }

    /** GET /api/connections/{id}/databases/{db}/table-stats 库内各表的列数/行数/磁盘大小(服务端带 TTL 缓存)。 */
    @GetMapping("/{db}/table-stats")
    public ApiResponse tableStats(@PathVariable("id") String id, @PathVariable("db") String db) {
        // 委派给表服务取统计;Controller 不写 SQL
        return ApiResponse.ok(tableSvc.tableStats(ApiSupport.parseId(id), db));
    }

    /** DELETE /api/connections/{id}/databases/{db} 删除数据库。 */
    @DeleteMapping("/{db}")
    public ApiResponse drop(@PathVariable("id") String id, @PathVariable("db") String db) {
        // 解析连接 ID 后删除指定库
        svc.drop(ApiSupport.parseId(id), db);
        // 返回被删除库名供前端确认
        return ApiResponse.ok(Collections.singletonMap("name", db));
    }
}
