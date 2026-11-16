/**
 * 【文件职责】本文件定义数据库连接管理模块的请求/写入参数模型 ConnectionInput，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.connection;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/** 创建/更新连接的请求体。 */
@Data
public class ConnectionInput {
    /** 连接名称。 */
    private String name;
    /** 主机地址。 */
    private String host;
    /** 端口。 */
    private Integer port;
    /** 数据库名。 */
    private String database;
    /** 用户名。 */
    private String username;
    /** 密码明文(仅入参;读取时不回传)。 */
    private String password;
    /** 连接协议:{@code native}(默认)或 {@code http};为 null 时按 native 处理。 */
    private String protocol;
    /** 是否启用 TLS 安全连接。 */
    private Boolean secure;
    /** 描述/备注。 */
    private String description;
    /** "connection" 或 "global"(global 表示清除该连接高级覆盖,继承全局) */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String advancedScope;
    /** 拨号超时(毫秒);为 null 表示不覆盖。 */
    private Long dialTimeoutMs;
    /** 查询超时(毫秒);为 null 表示不覆盖。 */
    private Long queryTimeoutMs;
    /** 连接最长存活(分钟);为 null 表示不覆盖。 */
    private Long connMaxLifetimeMin;
    /** 只读开关;为 null 表示不覆盖。 */
    private Boolean readOnly;
    /** 自定义连接参数(JSON)。 */
    @JsonProperty("paramsJson")
    private String paramsJson;
}
