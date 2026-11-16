/**
 * 【文件职责】本文件定义数据库连接管理模块的API 数据传输模型 ConnectionDTO，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.connection;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;


/** 连接对外 DTO(密码永不下发)。 */
@Data
public class ConnectionDTO {
    /** 连接 ID。 */
    private Long id;
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
    /** 是否已配置密码(密码本身不下发)。 */
    @JsonProperty("hasPassword")
    private Boolean hasPassword;
    /** 连接协议:{@code native} 或 {@code http}。 */
    private String protocol;
    /** 是否启用 TLS 安全连接。 */
    private Boolean secure;
    /** 描述/备注。 */
    private String description;
    /** 是否为默认连接。 */
    @JsonProperty("isDefault")
    private Boolean isDefault;
    /** 创建时间。 */
    private String createdAt;
    /** 更新时间。 */
    private String updatedAt;
    /** 拨号超时(毫秒);为 null 时省略,表示继承全局默认。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long dialTimeoutMs;
    /** 查询超时(毫秒);为 null 时省略,表示继承全局默认。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long queryTimeoutMs;
    /** 连接最长存活(分钟);为 null 时省略,表示继承全局默认。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long connMaxLifetimeMin;
    /** 只读开关;为 null 时省略,表示继承全局默认。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Boolean readOnly;
    /** 自定义连接参数(JSON);为 null 时省略,表示继承全局默认。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String paramsJson;
}
