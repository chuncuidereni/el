/**
 * 【文件职责】本文件定义密码与哈希工具模块的HTTP 控制器 ToolController。接收 Web 请求并完成路由与参数适配，统一封装响应；核心校验、权限和持久化逻辑委托给服务层。
 */

package com.el2ck.controller.tool;

import com.el2ck.controller.common.ApiResponse;

import com.el2ck.entity.tool.CryptoToolKeyGenRequest;
import com.el2ck.entity.tool.CryptoToolRequest;
import com.el2ck.entity.tool.HashToolRequest;
import com.el2ck.exception.ApiException;
import com.el2ck.service.tool.ToolService;
import com.el2ck.tool.HashTool;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;

/**
 * 工具页的 HTTP 接口,对应 Go {@code internal/api/hashtool_api.go} 与 {@code crypto_api.go} 的 5 个端点。
 *
 * <pre>
 * GET  /api/tools/hash/config     算法清单
 * POST /api/tools/hash/digest     批量算摘要
 * GET  /api/tools/crypto/config   算法清单(含密钥/IV 长度与提示)
 * POST /api/tools/crypto/keygen   按算法规格生成随机密钥与 IV
 * POST /api/tools/crypto/run      批量加解密
 * </pre>
 *
 * <p>本层只做「取参数 → 调服务 → 装信封」;算法与批量循环分别在 {@code tool.HashTool}、
 * {@code tool.CryptoTool} 与 {@link ToolService}。没有 Repository:这两个工具不落任何库
 * (纯计算),状态全在请求里。
 *
 * <p>请求体上限(32MB)由 {@code config.ToolBodyLimitFilter} 在解析之前拦下 ——
 * 与 Go 的 {@code http.MaxBytesReader} 同一位置、同一口径。
 */
@RestController
@RequestMapping("/api/tools")
public class ToolController {

    private final ToolService svc;
    /**
     * 创建工具接口 HTTP 控制器并装配后续调用所需的依赖。
     *
     * 依赖由 Spring 容器统一管理；本构造器只保存引用或初始化轻量适配对象，不执行远程业务操作。
     *
     * @param svc 该控制器对应的领域业务服务，承载实际校验和数据处理
     */
    public ToolController(ToolService svc) {
        this.svc = svc;
    }

    /** GET /api/tools/hash/config 算法清单。 */
    @GetMapping("/hash/config")
    public ApiResponse hashConfig() {
        return ApiResponse.ok(Collections.singletonMap("algos", HashTool.ALGOS));
    }

    /** POST /api/tools/hash/digest 批量算摘要。 */
    @PostMapping("/hash/digest")
    public ApiResponse digest(@RequestBody(required = false) HashToolRequest body) {
        if (body == null) {
            throw ApiException.validation("请求体非法: 请求体不能为空");
        }
        return ApiResponse.ok(svc.digest(body));
    }

    /** GET /api/tools/crypto/config 算法清单。 */
    @GetMapping("/crypto/config")
    public ApiResponse cryptoConfig() {
        return ApiResponse.ok(Collections.singletonMap("algos", svc.specs()));
    }

    /** POST /api/tools/crypto/keygen 生成随机密钥与 IV。 */
    @PostMapping("/crypto/keygen")
    public ApiResponse keyGen(@RequestBody(required = false) CryptoToolKeyGenRequest body) {
        if (body == null) {
            throw ApiException.validation("请求体非法: 请求体不能为空");
        }
        return ApiResponse.ok(svc.keyGen(body.getAlgo()));
    }

    /** POST /api/tools/crypto/run 批量加解密。 */
    @PostMapping("/crypto/run")
    public ApiResponse cryptoRun(@RequestBody(required = false) CryptoToolRequest body) {
        if (body == null) {
            throw ApiException.validation("请求体非法: 请求体不能为空");
        }
        return ApiResponse.ok(svc.run(body));
    }
}
