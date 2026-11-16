/**
 * 【文件职责】表达持久化对象不存在的领域异常，由全局异常处理转换为 HTTP 404。
 * <p>维护本文件时应同步检查调用方契约、失败恢复、并发边界和敏感数据处理，避免只修改单侧实现。
 */
package com.el2ck.exception;

/** 持久化对象不存在；由全局异常处理统一转换为 HTTP 404。 */
public class ResourceNotFoundException extends RuntimeException {

    // 构造器:仅携带错误消息
    public ResourceNotFoundException(String message) {
        // 消息为 null 时置为空串,避免出现 "null"
        super(message == null ? "" : message);
    }
}
