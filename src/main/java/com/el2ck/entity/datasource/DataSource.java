/**
 * 【文件职责】本文件定义数据源管理模块的领域结构模型 DataSource，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.datasource;

import lombok.Data;

/**
 * 数据源(同步输入端)配置存储模型。密码等敏感字段以整段加密 JSON(configEnc)保存。
 */
@Data
public class DataSource {
    /** 数据源类型:本地 CSV 文件。 */
    public static final String CSV = "csv";
    /** 数据源类型:本地 JSON 文件。 */
    public static final String JSON = "json";
    /** 数据源类型:本地 Excel(xlsx)文件。 */
    public static final String XLSX = "xlsx";
    /** 数据源类型:SFTP 远程文件。 */
    public static final String SFTP = "sftp";
    /** 数据源类型:MySQL。 */
    public static final String MYSQL = "mysql";
    /** 数据源类型:PostgreSQL。 */
    public static final String PGSQL = "pgsql";
    /** 数据源类型:ClickHouse。 */
    public static final String CLICKHOUSE = "clickhouse";
    /** Same-connection ClickHouse table source. */
    public static final String CK_TABLE = "ck_table";
    /** 数据源类型:MongoDB。 */
    public static final String MONGODB = "mongodb";
    public static final String KAFKA = "kafka";
    public static final String HIVE = "hive";

    /** 数据源主键。 */
    private long id;
    private long groupId;
    /** 用户自定义唯一标识 */
    private String code;
    /** 数据源名称(显示用)。 */
    private String name;
    /** csv / sftp / mysql / pgsql / clickhouse / mongodb */
    private String type;
    /** 类型相关配置(JSON,敏感字段加密) */
    private String configEnc;
    /** 描述/备注。 */
    private String description;
    /** 创建时间。 */
    private String createdAt;
    /** 更新时间。 */
    private String updatedAt;

    /** 校验给定类型是否为受支持的数据源类型(CSV/JSON/XLSX/SFTP/MySQL/PGSQL/ClickHouse/MongoDB)。 */
    public static boolean validType(String t) {
        return CSV.equals(t) || JSON.equals(t) || XLSX.equals(t) || SFTP.equals(t) || MYSQL.equals(t)
                || PGSQL.equals(t) || CLICKHOUSE.equals(t) || MONGODB.equals(t) || CK_TABLE.equals(t)
                || KAFKA.equals(t) || HIVE.equals(t);
    }

    /** 是否为本地文件类型(CSV/JSON/Excel)。 */
    public static boolean isLocalFile(String t) {
        return CSV.equals(t) || JSON.equals(t) || XLSX.equals(t);
    }
}
