/**
 * 【文件职责】本文件定义系统监控与设置模块的HTTP 控制器 SettingsController。接收 Web 请求并完成路由与参数适配，统一封装响应；核心校验、权限和持久化逻辑委托给服务层。
 */

package com.el2ck.controller.system;

import com.el2ck.controller.common.ApiResponse;

import com.el2ck.entity.system.AppSettings;
import com.el2ck.service.system.SettingsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 全局默认高级配置 HTTP 接口(对应 Go internal/api/settings_api.go)。
 *
 * 提供平台级默认的连接高级参数(dial/query 超时、连接保持时长、默认只读、自定义参数);
 * 保存时由 {@link SettingsService#update} 规范化(非正数超时复位默认、params 强制合法 JSON)。
 *
 * 供前端「连接管理」页(/connections)右上角「全局设置」弹窗调用;Basic Auth 保护(配置了 user 时)。
 */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    // 依赖的设置服务:负责读取/保存全局默认高级配置
    private final SettingsService svc;

    // 构造器注入设置服务
    /**
     * 创建系统设置 HTTP 控制器并装配后续调用所需的依赖。
     *
     * 依赖由 Spring 容器统一管理；本构造器只保存引用或初始化轻量适配对象，不执行远程业务操作。
     *
     * @param svc 该控制器对应的领域业务服务，承载实际校验和数据处理
     */
    public SettingsController(SettingsService svc) {
        // 保存服务引用供接口调用
        this.svc = svc;
    }

    /** GET /api/settings 读取全局默认高级配置(缺失键返回内置默认)。 */
    @GetMapping
    public ApiResponse get() {
        // 读取配置(缺失项回填内置默认)并装入成功信封
        return ApiResponse.ok(svc.get());
    }

    /** PUT /api/settings 保存全局默认高级配置。 */
    @PutMapping
    public ApiResponse update(@RequestBody(required = false) AppSettings in) {
        // 规范化后写入新配置
        svc.update(in);
        // 保存后回读最新配置返回,便于前端即时刷新
        return ApiResponse.ok(svc.get());
    }
}
