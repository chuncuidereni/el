/**
 * 【文件职责】本文件定义系统监控与设置模块的领域数据模型 AppSettings，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.system;

import lombok.Data;

/**
 * 全局默认高级配置(对应 Go internal/store/app_settings.go)。
 *
 * 作为「连接级高级覆盖」的回退层:dial/query 超时、连接最长存活、默认只读、
 * 自定义连接参数。按 key 存于 MySQL app_settings 表；缺失的键在读取时回填内置默认。
 */
@Data
public class AppSettings {

    /** 拨号超时默认(毫秒)。 */
    public static final long DEFAULT_DIAL_TIMEOUT_MS = 5000L;
    /** 查询超时默认(毫秒)。 */
    public static final long DEFAULT_QUERY_TIMEOUT_MS = 60000L;
    /** 连接最长存活默认(分钟)。 */
    public static final long DEFAULT_CONN_MAX_LIFETIME_MIN = 30L;

    /** 拨号超时(毫秒);为 null 表示未覆盖、继承全局默认。 */
    private Long dialTimeoutMs;
    /** 查询超时(毫秒);为 null 表示未覆盖、继承全局默认。 */
    private Long queryTimeoutMs;
    /** 连接最长存活(分钟);为 null 表示未覆盖、继承全局默认。 */
    private Long connMaxLifetimeMin;
    /** 连接是否默认只读;为 null 表示未覆盖、继承全局默认。 */
    private Boolean readOnly;
    /** 自定义连接参数(JSON 字符串,可为 null)。 */
    private String paramsJson;
}
