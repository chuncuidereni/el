/**
 * 【文件职责】本文件定义表管理与数据编辑模块的领域结构模型 GroupBucket，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.table;

import lombok.Data;

/** 分布统计里的一个取值及其条数。对应 Go service.GroupBucket。 */
@Data
public class GroupBucket {
    /** 取值(已 toString;数值/时间分桶时是桶标签)。 */
    private String value;
    /** 命中该取值的行数。 */
    private long count;
}
