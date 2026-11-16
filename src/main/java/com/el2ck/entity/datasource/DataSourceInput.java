/**
 * 【文件职责】本文件定义数据源管理模块的请求/写入参数模型 DataSourceInput，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.datasource;

import lombok.Data;

import java.util.Map;

/** 创建/更新数据源的请求体(config 为类型相关的明文配置,password 空表示保留原密码)。 */
@Data
public class DataSourceInput {
    /** 所属分组；0 表示未分组，编辑时不传则保留原分组。 */
    private Long groupId;
    /** 用户自定义唯一标识(编码,创建时必填)。 */
    private String code;
    /** 名称。 */
    private String name;
    /** 类型(csv/json/xlsx/sftp/mysql/pgsql/clickhouse/mongodb)。 */
    private String type;
    /** 描述/备注。 */
    private String description;
    /** 类型相关的明文配置映射(含 path/host/port/password 等)。 */
    private Map<String, Object> config;
}
