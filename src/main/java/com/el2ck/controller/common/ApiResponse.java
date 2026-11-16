/**
 * 【文件职责】本文件定义HTTP 公共基础设施模块的HTTP 响应模型 ApiResponse。描述接口成功与失败信封，确保控制器返回结构稳定。
 */

package com.el2ck.controller.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;

/**
 * 统一响应信封,对应 Go internal/api/response.go。
 * 成功: {code:0, message:"ok", data:...}; 失败: HTTP status + {code:status, message:"..."}, 无 data 键。
 */
@Getter
public class ApiResponse {

    // 状态码:0 表示成功;非 0 时与 HTTP 状态码一致(错误场景)
    private final int code;
    // 结果消息:成功固定 "ok",失败时携带错误描述
    private final String message;
    // data 为 null(失败信封)时该键不出现在 JSON 中
    @JsonInclude(JsonInclude.Include.NON_NULL)
    // 成功时的业务数据;失败时为 null
    private final Object data;

    // 私有构造器:统一经 ok/fail 工厂创建,创建后字段不可变
    /**
     * 创建不可变响应信封，仅供 {@link #ok(Object)}、{@link #ok()} 和 {@link #fail(int, String)} 工厂调用。
     *
     * @param code 业务状态码；成功为 0
     * @param message 返回给调用方的状态描述
     * @param data 成功时的业务数据；失败时传 null
     */
    private ApiResponse(int code, String message, Object data) {
        // 记录状态码
        this.code = code;
        // 记录结果消息
        this.message = message;
        // 记录业务数据
        this.data = data;
    }

    // 成功工厂:携带业务数据,状态码 0、消息 "ok"
    /**
     * 创建携带业务数据的成功响应。
     *
     * @param data 要写入 data 字段的数据；传 null 时该字段因 {@code NON_NULL} 策略而省略
     * @return code 为 0、message 为 {@code ok} 的响应
     */
    public static ApiResponse ok(Object data) {
        // 带数据构造成功信封
        return new ApiResponse(0, "ok", data);
    }

    // 成功工厂(无数据):仅返回 {code:0,message:"ok"},不含 data 键
    /**
     * 创建无业务数据的成功响应。
     *
     * @return code 为 0、message 为 {@code ok} 且不包含 data 字段的响应
     */
    public static ApiResponse ok() {
        // 空数据构造成功信封
        return new ApiResponse(0, "ok", null);
    }

    // 失败工厂:按状态码与消息构造错误信封(data 恒为 null)
    /**
     * 创建失败响应，data 始终为 null，避免错误响应泄露业务数据。
     *
     * @param code 非成功业务码，通常与 HTTP 状态码一致
     * @param message 可展示给调用方的错误描述
     * @return 失败响应信封
     */
    public static ApiResponse fail(int code, String message) {
        // 空数据构造失败信封
        return new ApiResponse(code, message, null);
    }
}
