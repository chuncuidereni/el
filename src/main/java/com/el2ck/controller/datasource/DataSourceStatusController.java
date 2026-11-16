/**
 * 【文件职责】本文件定义数据源管理模块的HTTP 控制器 DataSourceStatusController。接收 Web 请求并完成路由与参数适配，统一封装响应；核心校验、权限和持久化逻辑委托给服务层。
 */

package com.el2ck.controller.datasource;

import com.el2ck.controller.common.ApiResponse;

import com.el2ck.service.datasource.DataSourceStatusService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 数据源状态快照 HTTP 接口(对应 Go internal/api/datasource_api.go 的 ListStatus)。
 *
 * 返回历史状态快照；请求不建立远端连接，旧 force 参数不再触发探测。
 *
 * Basic Auth 保护(配置了 user 时)。
 */
@RestController
public class DataSourceStatusController {
    // 只读状态快照服务
    private final DataSourceStatusService service;

    // 构造器注入状态服务
    /**
     * 创建数据源状态 HTTP 控制器并装配后续调用所需的依赖。
     *
     * 依赖由 Spring 容器统一管理；本构造器只保存引用或初始化轻量适配对象，不执行远程业务操作。
     *
     * @param service 该控制器对应的领域业务服务，承载实际校验和数据处理
     */
    public DataSourceStatusController(DataSourceStatusService service) {
        // 保存服务引用供接口调用
        this.service = service;
    }

    /** GET /api/datasources/status：当前用户的历史状态。 */
    @GetMapping("/api/datasources/status")
    public ApiResponse list() {
        // 旧客户端即使携带 force=1，也只读快照，不再触发全量探测。
        return ApiResponse.ok(service.list());
    }
}
