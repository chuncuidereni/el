/**
 * 【文件职责】本文件定义宽表任务模块的运行状态或日志模型 WideTableTask，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.widetable;

import lombok.Data;

import java.util.Map;

/**
 * Go 版 wide_table_tasks 的列表视图。Java 侧只读不写：任务的建/改/跑都由 Go 程序负责，
 * 本服务仅把它读出来供首页卡片展示。注意与 UNION 型宽表(arb 那套)是两回事，不要混用。
 */
@Data
public class WideTableTask {
    /** 任务主键，同时也是列表的排序键(倒序，最新建的在前)。 */
    private long id;
    /** 任务名称。 */
    private String name;
    /** 任务描述；库里是非空列且默认空串，故不会为 null。 */
    private String description;
    /** 是否启用。库里存 INTEGER 0/1，读取时按非 0 即 true 归一为布尔。 */
    private boolean enabled;
    /** 多表关联配置，来自 config_json 文本列(与数据探索向导同一套结构)。整段 JSON 解析失败的行会被读取方跳过。 */
    private Map<String, Object> config;
    /** 目标 ClickHouse 库；库里有默认值 dwd_data，一般不为空。 */
    private String targetDb;
    /** 目标 ClickHouse 表，建表时必填，故非空。 */
    private String targetTable;
    /** MergeTree 排序键，必须是输出字段之一；为空表示取第一个输出字段，由执行侧决定。 */
    private String orderKey;
    /** 最近一次运行开始时间。没跑过时为 null，读取时统一归一为空串，避免前端处理 null。 */
    private String lastRunAt;
    /** 最近一次运行的终态(如 success)；没跑过为空串。 */
    private String lastStatus;
    /** 最近一次运行写入的行数；没跑过按 0 计。 */
    private long lastRows;
    /** 最近一次运行的失败原因；成功或没跑过为空串。 */
    private String lastError;
    /** 记录创建时间，由 MySQL 元数据层写入的文本。 */
    private String createdAt;
    /** 记录更新时间；同样只在写侧更新，读取方不感知。 */
    private String updatedAt;
}
