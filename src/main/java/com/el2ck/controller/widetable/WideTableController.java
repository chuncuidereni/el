/**
 * 【文件职责】本文件定义宽表任务模块的HTTP 控制器 WideTableController。接收 Web 请求并完成路由与参数适配，统一封装响应；核心校验、权限和持久化逻辑委托给服务层。
 */

package com.el2ck.controller.widetable;

import com.el2ck.controller.common.ApiResponse;

import com.el2ck.service.widetable.WideTableService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Go 首页使用的 JOIN 型宽表任务列表入口。 */
@RestController
public class WideTableController {
    // 依赖的宽表服务:只提供 JOIN 型宽表任务的读取(本控制器无写接口)
    private final WideTableService service;

    // 构造器注入宽表服务
    /**
     * 创建宽表任务 HTTP 控制器并装配后续调用所需的依赖。
     *
     * 依赖由 Spring 容器统一管理；本构造器只保存引用或初始化轻量适配对象，不执行远程业务操作。
     *
     * @param service 该控制器对应的领域业务服务，承载实际校验和数据处理
     */
    public WideTableController(WideTableService service) {
        // 保存服务引用供接口调用
        this.service = service;
    }

    /** GET /api/wide-table-tasks 首页「宽表任务」卡片的一次性全量清单(不分页、不带筛选参数)。 */
    @GetMapping("/api/wide-table-tasks")
    public ApiResponse list() {
        // 首页卡片用的一次性全量清单,不分页也不带筛选参数
        return ApiResponse.ok(service.list());
    }
}
