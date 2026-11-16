/**
 * 【文件职责】本文件定义表管理与数据编辑模块的查询或操作结果模型 DataEditResult，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.table;

import lombok.Data;

/** 数据编辑结果统计。 */
@Data
public class DataEditResult {
    /** 实际新增行数。 */
    private Integer inserted;
    /** 实际更新行数。 */
    private Integer updated;
    /** 实际删除行数。 */
    private Integer deleted;
}
