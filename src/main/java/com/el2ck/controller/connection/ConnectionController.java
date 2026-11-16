/**
 * 【文件职责】本文件定义数据库连接管理模块的HTTP 控制器 ConnectionController。接收 Web 请求并完成路由与参数适配，统一封装响应；核心校验、权限和持久化逻辑委托给服务层。
 */

package com.el2ck.controller.connection;

import com.el2ck.controller.common.ApiAdvice;
import com.el2ck.controller.common.ApiResponse;
import com.el2ck.controller.common.ApiSupport;

import com.el2ck.exception.ApiException;
import com.el2ck.entity.connection.ConnectionInput;
import com.el2ck.service.connection.ConnectionService;
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
 * 连接管理 HTTP 接口(对应 Go internal/api/connection_api.go)。
 *
 * 全部成功响应包装 {code:0,message:"ok",data};业务失败由 {@link ApiAdvice} 统一输出
 * 错误信封(400/404/500 等);连接测试类失败 → 502(badGateway),由服务层抛
 * {@link ApiException#badGateway}。
 *
 * 供前端「数据仓库 · 连接管理」页(/connections)调用:维护目标 ClickHouse 连接与默认连接,
 * 点「打开」进入该连接做库/表管理;Basic Auth 保护(配置了 user 时)。
 */
@RestController
@RequestMapping("/api/connections")
public class ConnectionController {
    @org.springframework.beans.factory.annotation.Autowired
    private com.el2ck.service.auth.ResourceAccessService access;

    // 依赖的连接服务:负责连接的增删改查、状态检查与连通性测试
    private final ConnectionService svc;
    @org.springframework.beans.factory.annotation.Autowired
    private com.el2ck.service.connection.ConnectionSettingsService connectionSettings;

    // 构造器注入连接服务
    /**
     * 创建ClickHouse 连接 HTTP 控制器并装配后续调用所需的依赖。
     *
     * 依赖由 Spring 容器统一管理；本构造器只保存引用或初始化轻量适配对象，不执行远程业务操作。
     *
     * @param svc 该控制器对应的领域业务服务，承载实际校验和数据处理
     */
    public ConnectionController(ConnectionService svc) {
        // 保存服务引用供各接口调用
        this.svc = svc;
    }

    /** GET /api/connections 连接列表。 */
    @GetMapping
    public ApiResponse list() {
        // 查询全部连接列表并装入统一成功信封返回
        if ("admin".equals(com.el2ck.config.AuthContext.get().getRole())) return ApiResponse.ok(svc.list());
        java.util.List<java.util.Map<String,Object>> result = new java.util.ArrayList<>();
        for (com.el2ck.entity.connection.ConnectionDTO c : svc.list()) {
            java.util.Map<String,Object> item = new java.util.LinkedHashMap<>();
            item.put("id", c.getId()); item.put("name", c.getName()); item.put("readOnly", Boolean.TRUE.equals(c.getReadOnly()));
            item.put("isDefault",Boolean.TRUE.equals(c.getIsDefault()));
            result.add(item);
        }
        return ApiResponse.ok(result);
    }

    /** GET /api/connections/status 全部连接健康状态(端口通 + SELECT 1)。 */
    @GetMapping("/status")
    public ApiResponse status() {
        // 逐个连接做连通性检查,结果包装为成功信封返回
        return ApiResponse.ok(svc.checkStatuses());
    }

    /** POST /api/connections 新增连接。 */
    @PostMapping
    public ApiResponse create(@RequestBody(required = false) ConnectionInput in) {
        // 交由服务创建连接并返回生成后的连接对象
        return ApiResponse.ok(svc.create(in));
    }

    /** POST /api/connections/test 测试尚未保存的连接配置(不入库)。 */
    @PostMapping("/test")
    public ApiResponse test(@RequestBody(required = false) ConnectionInput in) {
        // 仅校验传入配置的连通性,不写入数据库
        svc.testConfig(in);
        // 测试通过则以 {ok:true} 作为成功数据返回
        return ApiResponse.ok(okMap());
    }

    /** PUT /api/connections/{id} 更新连接。 */
    @PutMapping("/{id}")
    public ApiResponse update(@PathVariable("id") String id, @RequestBody(required = false) ConnectionInput in) {
        // 解析路径数字 ID 后交由服务更新,返回更新后的连接
        return ApiResponse.ok(svc.update(ApiSupport.parseId(id), in));
    }

    /** PUT /api/connections/{id}/settings 在一个事务内保存连接与全局默认高级配置。 */
    @PutMapping("/{id}/settings")
    public ApiResponse updateWithSettings(@PathVariable("id") String id,
            @RequestBody(required = false) com.el2ck.entity.connection.ConnectionSettingsInput in) {
        return ApiResponse.ok(connectionSettings.update(ApiSupport.parseId(id), in));
    }

    /** PUT /api/connections/{id}/default 设为默认连接。 */
    @PutMapping("/{id}/default")
    public ApiResponse setDefault(@PathVariable("id") String id) {
        // 解析 ID 后将该连接设为平台默认连接
        return ApiResponse.ok(svc.setDefault(ApiSupport.parseId(id)));
    }

    /** DELETE /api/connections/{id} 删除连接。 */
    @DeleteMapping("/{id}")
    public ApiResponse delete(@PathVariable("id") String id) {
        // 解析路径中的数字 ID
        long cid = ApiSupport.parseId(id);
        // 执行删除:仅清理连接记录与其下的用户备注(表间无外键,在仓储层手动级联),
        // 数据源/同步任务不随连接删除;连接不存在 → 404
        svc.delete(cid);
        // 返回被删除连接的 id 供前端确认
        return ApiResponse.ok(Collections.singletonMap("id", cid));
    }

    /** POST /api/connections/{id}/test 测试已保存的连接。 */
    @PostMapping("/{id}/test")
    public ApiResponse testById(@PathVariable("id") String id) {
        // 按已保存的连接 ID 执行连通性测试
        svc.testByID(ApiSupport.parseId(id));
        // 测试通过则以 {ok:true} 作为成功数据返回
        return ApiResponse.ok(okMap());
    }

    /** POST /api/connections/{id}/test-config 测试编辑中的配置(不保存)。 */
    @PostMapping("/{id}/test-config")
    public ApiResponse testConfig(@PathVariable("id") String id, @RequestBody(required = false) ConnectionInput in) {
        // 以"编辑中"的连接配置覆盖测试(不落库)
        svc.testConfig(ApiSupport.parseId(id), in);
        // 测试通过则以 {ok:true} 作为成功数据返回
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
