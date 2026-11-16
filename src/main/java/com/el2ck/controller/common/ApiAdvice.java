/**
 * 【文件职责】本文件定义HTTP 公共基础设施模块的全局 HTTP 异常与响应适配器 ApiAdvice。把异常和空响应转换为项目统一的 HTTP 响应语义，便于各控制器保持一致行为。
 */

package com.el2ck.controller.common;

import com.el2ck.exception.ApiException;
import com.el2ck.config.AppConfig;
import com.el2ck.exception.ResourceNotFoundException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.NoHandlerFoundException;

import javax.servlet.http.HttpServletRequest;
import java.io.File;
import java.nio.file.Files;

/**
 * 全局异常 → 统一失败信封(对应 Go internal/api/response.go 的 errOf / fail / badGateway)。
 *
 * 成功响应由各 Controller 直接返回 {@link ApiResponse};此处只负责把业务/系统异常按
 * 状态码翻译为 {code,message} JSON(不带 data 键)。
 * 同时兜底"无匹配路由":/api/* → JSON 404「接口不存在」;其余 → 前端 index.html(SPA 深链)。
 */
@ControllerAdvice
public class ApiAdvice {

    // 应用全局配置;路由兜底时要据此拼接前端 index.html 的目录
    private final AppConfig cfg;

    // 构造器注入 AppConfig
    /**
     * 创建统一 API 异常处理组件并装配后续调用所需的依赖。
     *
     * 依赖由 Spring 容器统一管理；本构造器只保存引用或初始化轻量适配对象，不执行远程业务操作。
     *
     * @param cfg 应用配置对象，提供错误响应和运行时开关
     */
    public ApiAdvice(AppConfig cfg) {
        // 保存配置引用供异常兜底处理器使用
        this.cfg = cfg;
    }

    /** 业务异常:原样携带其状态码与中文消息。 */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResponse> handleApi(ApiException e) {
        // 直接采用业务异常自带的状态码与消息封装失败信封
        return fail(e.getStatus(), e.getMessage());
    }

    /** 仓库层「记录不存在」(MetadataRepository 直接抛,如删除连接/数据源) → 404,对应 Go store.ErrNotFound。 */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponse> handleNotFound(ResourceNotFoundException e) {
        // 统一以 404 状态码返回记录不存在的错误消息
        return fail(404, e.getMessage());
    }

    /** 请求体无法反序列化(JSON 语法/字段类型错) → 400,与 Go "参数错误: ..." 一致。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse> handleUnreadable(HttpMessageNotReadableException e) {
        // 取具体根因异常,其消息通常比外层更贴近字段/语法错误
        Throwable cause = e.getMostSpecificCause();
        // 优先采用根因消息;根因无消息则退回外层消息
        String detail = cause != null && cause.getMessage() != null
                ? cause.getMessage() : e.getMessage();
        // 包装为 400「参数错误: <详情>」失败信封
        return fail(400, "参数错误: " + detail);
    }

    /** 缺少请求参数 / 上传内容不是 multipart 等 → 400。 */
    @ExceptionHandler({MissingServletRequestParameterException.class, MultipartException.class})
    public ResponseEntity<ApiResponse> handleMissingParam() {
        // 无需具体消息,统一提示参数错误
        return fail(400, "参数错误");
    }

    /** 请求方法不支持(GET 打到 POST 等)→ 405。 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse> handleMethod(HttpRequestMethodNotSupportedException e) {
        // 附带实际使用的方法名,提示请求方法不被支持
        return fail(405, "请求方法不支持: " + e.getMethod());
    }

    /** 类型转换失败(路径参数/查询参数)→ 400。 */
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse> handleTypeMismatch() {
        // 参数类型错误统一按 400 处理
        return fail(400, "参数错误");
    }

    /**
     * 无匹配路由(需 spring.mvc.throw-exception-if-no-handler-found=true)。
     *  - /api/* → JSON 404「接口不存在」(与 Go serveWeb NoRoute 一致);
     *  - 其余 → 返回前端 index.html(HTML5 history 路由深链刷新);
     *  - 无前端构建产物时也返回 404。
     */
    @ExceptionHandler(NoHandlerFoundException.class)
    public Object handleNoRoute(NoHandlerFoundException e, HttpServletRequest request) {
        // 取当前请求 URI,用于区分 API 路径与前端深链路径
        String uri = request.getRequestURI();
        // 命中 /api 前缀却无对应路由 → JSON 404「接口不存在」
        if (uri == null || uri.startsWith("/api/") || "/api".equals(uri)) {
            return fail(404, "接口不存在");
        }
        // 非 /api 路径视为前端深链刷新,定位前端入口文件
        File index = new File(cfg.getWebDir(), "index.html");
        // 前端构建产物存在时才回写页面;否则按 404 处理
        if (index.exists() && index.isFile()) {
            try {
                // 一次性读入 index.html 的全部字节
                byte[] body = Files.readAllBytes(index.toPath());
                // 构造响应头
                HttpHeaders headers = new HttpHeaders();
                // 强制 text/html + UTF-8 编码输出
                headers.setContentType(MediaType.valueOf("text/html;charset=UTF-8"));
                // 以 200 返回前端入口页面
                return new ResponseEntity<byte[]>(body, headers, HttpStatus.OK);
            } catch (Exception ex) {
                // 读文件失败退化为 404。
                return fail(404, "接口不存在");
            }
        }
        // 既非 API 路由又无前端产物 → 404
        return fail(404, "接口不存在");
    }

    /** 其他未预期异常 → 500,携带具体原因(与 Go errOf default 一致)。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse> handleOther(Exception e) {
        // 按 500 返回提取到的最内层异常文案
        return fail(500, msg(e));
    }

    // =====================================================================
    // 工具
    // =====================================================================

    // 按状态码构造统一 JSON 失败信封(仅 code/message,不含 data 键)
    /**
     * 构造带 HTTP 状态码和 JSON 类型的统一失败响应。
     *
     * @param status HTTP 状态码
     * @param message 面向客户端的错误文案
     * @return 失败响应实体
     */
    private static ResponseEntity<ApiResponse> fail(int status, String message) {
        // 以指定状态码 + application/json 类型输出失败信封
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.fail(status, message));
    }

    /** 提取最内层有消息的异常文案;空则退化为类名。 */
    private static String msg(Throwable e) {
        // 从当前异常起准备向下追踪根因
        Throwable t = e;
        // 只要下层仍有 cause 且当前层无消息,就继续向根因走
        while (t.getCause() != null && (t.getMessage() == null || t.getMessage().isEmpty())) {
            t = t.getCause();
        }
        // 取最内层有消息的异常文案
        String m = t.getMessage();
        // 文案仍为空时退化为类名提示,避免向前端返回空消息
        if (m == null || m.isEmpty()) {
            return "服务器内部错误: " + t.getClass().getSimpleName();
        }
        // 返回可读的根因文案
        return m;
    }
}
