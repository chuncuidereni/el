/**
 * 【文件职责】本文件定义数据源管理模块的展示或状态视图模型 DataSourceDetail，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.datasource;

import com.el2ck.entity.common.Column;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/** 数据源详情(预览/详情对话框使用)。字段按类型部分填充。 */
@Data
public class DataSourceDetail {
    /** 数据源 ID。 */
    private Long id;
    /** 用户自定义唯一标识(编码)。 */
    private String code;
    /** 名称。 */
    private String name;
    /** 类型(csv/json/xlsx/sftp/mysql/pgsql/clickhouse/mongodb)。 */
    private String type;
    /** 本地文件路径(仅本地文件类型)。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String path;
    /** 文件大小(字节,本地文件类型)。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long size;
    /** 应用列名映射后的字段列表(本地文件类型)。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private java.util.List<Column> columns;
    /** 未应用列名映射的原始字段列表(本地文件类型)。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private java.util.List<Column> rawColumns;
    /** 读取字段名 → 原始文件表头；独立保存元信息，不改变列结构和续传断点。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private java.util.Map<String, String> columnComments;
    /** 数据总行数(本地文件类型)。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long total;
    /** 是否首行为列头(本地文件类型)。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Boolean hasHeader;
    /** 手动指定的列名(本地文件类型)。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private java.util.List<String> names;
    /** 主机地址(数据库/SFTP 类型)。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String host;
    /** 端口(数据库/SFTP 类型)。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer port;
    /** 数据库名(数据库类型)。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String database;
    /** 目标表名(数据库类型)。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String table;
    /** 集合名(MongoDB 类型)。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String collection;
    /** 远程路径/目录(SFTP 类型)。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String remotePath;
    /** 用户名(SFTP/数据库类型)。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String username;
}
