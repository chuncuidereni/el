/**
 * 【文件职责】本文件定义表管理与数据编辑模块的查询或操作结果模型 GroupByResult，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.table;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 分布统计结果。对应 Go service.GroupByResult。
 * <p>{@code buckets} 以空列表初始化(前端直接迭代),{@code sql} 回传实际执行的语句,界面可直接展示。
 */
@Data
public class GroupByResult {
    /** 按哪个字段分组。 */
    private String column = "";
    /** 各取值的条数,按条数从多到少(时间/数值分桶时按其桶序)。 */
    private List<GroupBucket> buckets = new ArrayList<>();
    /**
     * 满足过滤条件的<b>条数</b> —— 占比的分母。
     * 注意它与各桶之和不等(只取了前 limit 个);分母查询失败时为 0(界面算不出百分比,但分布本身仍在)。
     */
    private long total;
    /** 实际执行的 SQL(界面可以给用户看「这条分布是怎么算的」)。 */
    private String sql = "";
}
