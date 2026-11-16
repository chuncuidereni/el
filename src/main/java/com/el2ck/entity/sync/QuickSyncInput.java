/**
 * 【文件职责】本文件定义数据同步模块的请求/写入参数模型 QuickSyncInput，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.sync;

import lombok.Data;

import java.util.List;

/** 数据源一键入库请求；映射由预览结果自动生成，用户只需指定目标位置。 */
@Data
public class QuickSyncInput {
    /** 目标 ClickHouse 连接 ID。 */
    private Long connectionId;
    /** 目标数据库名。 */
    private String targetDb;
    /** 目标表名。 */
    private String targetTable;
    /** 同步策略: full / incremental。 */
    private String mode;
    /** 增量源字段；增量模式必填。 */
    private String incrColumn;
    /** 写入前是否清空目标表。 */
    private Boolean truncate;
    /** 目标表不存在时是否按映射自动创建。 */
    private Boolean autoCreateTable;
    /** 源列 -> 目标列的字段映射(由预览结果自动生成)。 */
    private List<SyncFieldMapping> mapping;
}
