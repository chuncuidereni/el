/**
 * 【文件职责】本文件定义数据源管理模块的API 数据传输模型 DataSourceDTO，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.datasource;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.Map;

/** 数据源对外 DTO(config 中 password 键被移除,改为 hasPassword 布尔)。 */
@Data
public class DataSourceDTO {
    /** 数据源 ID。 */
    private Long id;
    private Long groupId;
    /** 服务端持久化的所属用户，客户端不可指定或修改。 */
    private Long ownerUserId;
    private String ownerUsername;
    /** 用户自定义唯一标识(编码)。 */
    private String code;
    /** 名称。 */
    private String name;
    /** 类型(csv/json/xlsx/sftp/mysql/pgsql/clickhouse/mongodb)。 */
    private String type;
    /** 描述/备注。 */
    private String description;
    /** 是否已配置密码(config 中的 password 键不下发)。 */
    private Boolean hasPassword;
    /** 明文配置映射(敏感项如 password 已移除)。 */
    private Map<String, Object> config;
    /** 创建时间。 */
    private String createdAt;
    /** 更新时间。 */
    private String updatedAt;
    /** 后端提示/告警(如配置不完整);无则省略。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String warning;
}
