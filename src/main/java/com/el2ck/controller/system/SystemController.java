/**
 * 【文件职责】本文件定义系统监控与设置模块的HTTP 控制器 SystemController。接收 Web 请求并完成路由与参数适配，统一封装响应；核心校验、权限和持久化逻辑委托给服务层。
 */

package com.el2ck.controller.system;

import com.el2ck.controller.common.ApiResponse;
import com.el2ck.controller.common.ApiSupport;
import com.el2ck.controller.connection.ConnectionController;

import com.el2ck.exception.ApiException;
import com.el2ck.service.system.MonitorService;
import com.el2ck.service.system.SystemStatsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统监控接口(对应 Go internal/api/system_api.go 的 2 个端点)。
 *
 * <pre>
 * GET /api/system/stats                  平台服务进程自身的资源占用快照(前端每 2 秒拉一次)
 * GET /api/connections/{id}/server-stats 目标库服务的运行指标(内存 / CPU / 磁盘 / 连接)
 * </pre>
 *
 * <p>⚠️ 第二个端点虽然挂在 {@code /connections/} 前缀下,但域归属是「系统监控」而不是
 * 「连接管理」—— Go 侧它就在 {@code systemAPI} 上(路由分组只是前缀)。所以它在这里,
 * 不在 {@link ConnectionController};上一版盘点曾把这一条漏在统计之外。
 */
@RestController
@RequestMapping("/api")
public class SystemController {

    /** 平台进程自身的采样服务。 */
    private final SystemStatsService stats;

    /** 目标库服务的指标采集。 */
    private final MonitorService monitor;
    /**
     * 创建系统统计与监控 HTTP 控制器并装配后续调用所需的依赖。
     *
     * 依赖由 Spring 容器统一管理；本构造器只保存引用或初始化轻量适配对象，不执行远程业务操作。
     *
     * @param stats 系统统计服务，用于汇总运行时指标
     * @param monitor 监控查询服务，用于读取实例与任务运行状态
     */
    public SystemController(SystemStatsService stats, MonitorService monitor) {
        this.stats = stats;
        this.monitor = monitor;
    }

    /** GET /api/system/stats 资源占用快照(前端每 2 秒拉一次)。 */
    @GetMapping("/system/stats")
    public ApiResponse systemStats() {
        // 首次调用 cpuPercent 为 0(没有上一次采样点做差),属预期行为
        return ApiResponse.ok(stats.collect());
    }

    /**
     * GET /api/connections/{id}/server-stats 目标库服务的运行指标。
     *
     * <p>报错一律 502(与 Go 的 {@code badGateway} 一致):这一页的失败几乎都是
     * 「连不上目标库 / 那台机器上的视图读不了」,不是调用方把参数写错了。
     */
    @GetMapping("/connections/{id}/server-stats")
    public ApiResponse serverStats(@PathVariable("id") String id) {
        return ApiResponse.ok(monitor.serverStats(parseId(id)));
    }

    /** 路径 ID:非数字与 &lt;= 0 都按 400(对应 Go parseID,不落到下游去换来一个 502)。 */
    private static long parseId(String id) {
        long value = ApiSupport.parseId(id);
        if (value <= 0) {
            throw ApiException.validation("ID 非法");
        }
        return value;
    }
}
