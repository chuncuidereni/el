/**
 * 【文件职责】本文件定义表管理与数据编辑模块的请求/写入参数模型 GroupByRequest，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.table;

import lombok.Data;

/**
 * 维度分布统计入参(画像查询 / 圈选后的分布确认)。对应 Go service.GroupByRequest。
 */
@Data
public class GroupByRequest {
    /**
     * 过滤条件树 —— 与数据页同一个结构、同一套渲染:
     * 「看到的行」与「统计的条数」口径必须一致,否则圈选条数与画像分布对不上。
     */
    private FilterNode filter;
    /** 去重保留记录的排序，与圈选列表一致。 */
    private java.util.List<SortSpec> orders;
    /** 按哪个维度分组(输出字段名)。 */
    private String column;
    /** 最多返回多少个取值(默认 50,上限 500);按条数从多到少取。 */
    private Integer limit;
    /**
     * 分组方式:
     * <ul>
     *   <li>空串 / raw:原值分组(分类列用,按条数从多到少)</li>
     *   <li>number:数值分桶(等宽)—— 年龄段、消费区间…… 步长由 bucketSize 定</li>
     *   <li>day / month / year:时间分桶 —— 注册月份、活跃年月</li>
     * </ul>
     * 为什么需要它:画像里数值/时间维度不能按原值分组 —— age 会出 100 行(18..99)、
     * reg_date 出 365 行,根本没法看。
     */
    private String bucket;
    /** 数值分桶步长(如 10 → 10 一档);&lt;=0 时默认 10。 */
    private Double bucketSize;
}
