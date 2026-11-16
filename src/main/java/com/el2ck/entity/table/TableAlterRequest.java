/**
 * 【文件职责】本文件定义表管理与数据编辑模块的请求/写入参数模型 TableAlterRequest，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.table;

import lombok.Data;

import java.util.List;

/** 表结构变更请求(可同时改表名、表注释及若干列)。 */
@Data
public class TableAlterRequest {
    /** 重命名后的新表名(为空表示不改表名)。 */
    private String newTableName;
    /** 新表注释；null 表示保持原值，空字符串表示清空注释。 */
    private String tableComment;
    /** 列变更操作列表。 */
    private List<ColumnAlter> columns;
}
