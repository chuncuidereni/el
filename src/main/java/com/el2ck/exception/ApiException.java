/**
 * 【文件职责】统一的 API 业务异常及 HTTP 状态快捷工厂；异常只携带安全消息，由全局异常处理映射响应。
 * <p>维护本文件时应同步检查调用方契约、失败恢复、并发边界和敏感数据处理，避免只修改单侧实现。
 */
package com.el2ck.exception;

/**
 * API 业务异常:携带 HTTP 状态码与中文错误消息,由全局异常处理转换为对应 HTTP 响应。
 * 错误状态映射与 Go 一致:NotFound→404 / Validation→400 / ReadOnly→403 / 其余→500 / 下游失败→502。
 */
public class ApiException extends RuntimeException {

    // 该异常对应的 HTTP 状态码
    private final int status;

    // 构造器:仅含状态码与消息(消息为 null 时归一为空串)
    public ApiException(int status, String message) {
        // 消息为 null 时置为空串,避免后续拼接产生 "null"
        super(message == null ? "" : message);
        // 记录 HTTP 状态码
        this.status = status;
    }

    // 构造器:含状态码、消息与底层原因(用于包装下游异常)
    public ApiException(int status, String message, Throwable cause) {
        // 消息为 null 时置为空串,并记录原始异常链
        super(message == null ? "" : message, cause);
        // 记录 HTTP 状态码
        this.status = status;
    }

    // 供全局异常处理读取 HTTP 状态码
    public int getStatus() {
        return status;
    }

    // ---- 常用快捷构造 ----

    /** 400 参数校验失败。 */
    public static ApiException validation(String msg) {
        // 构造并返回 400 状态码的业务异常
        return new ApiException(400, msg);
    }

    /** 403 只读模式禁止写操作。 */
    public static ApiException readOnly(String msg) {
        // 构造并返回 403 状态码的业务异常
        return new ApiException(403, msg);
    }

    /** 404 记录不存在。 */
    public static ApiException notFound(String msg) {
        // 构造并返回 404 状态码的业务异常
        return new ApiException(404, msg);
    }

    /** 500 服务器内部错误。 */
    public static ApiException server(String msg) {
        // 构造并返回 500 状态码的业务异常
        return new ApiException(500, msg);
    }

    /** 502 下游(ClickHouse/MySQL/Postgres/Mongo/SFTP/DuckDB)连接或执行失败。 */
    public static ApiException badGateway(String msg) {
        // 构造并返回 502 状态码的业务异常(表示上游网关依赖的后端出错)
        return new ApiException(502, msg);
    }
}
