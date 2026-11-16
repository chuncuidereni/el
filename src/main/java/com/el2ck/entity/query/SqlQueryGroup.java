/**
 * 【文件职责】本文件定义查询执行模块的领域数据模型 SqlQueryGroup，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.query;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * SQL 查询分组(对应 MySQL 的 sql_query_groups 表)，「数据探索」页左侧的查询分类。
 */
@Data
public class SqlQueryGroup {
    /** 主键,也是分组列表的排序键(按 id 升序)。 */
    private long id;
    /** 分组名称。服务层保证去空白后非空。 */
    private String name;
    /** 分组备注,可为空串。 */
    private String remark;
    /**
     * 组内查询数量。0 不序列化(对齐 Go 的 {@code json:",omitempty"}——值为 0 时整个 key 不出现,
     * 前端用「有没有 count」区分「没统计」和「统计出来是 0」)。
     */
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private int count;
    /** 创建时间，由 MySQL 元数据层写入的时间文本。 */
    private String createdAt;
    /** 更新时间,改名/改备注时刷新。 */
    private String updatedAt;
}
