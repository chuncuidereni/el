/**
 * 【文件职责】本文件定义表管理与数据编辑模块的领域数据模型 TableGroup，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.table;

import com.el2ck.entity.group.DatabaseGroup;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 表分组(对应 MySQL 的 tbl_groups 表)，把某个连接某个库里的表归入自定义分组，便于侧栏分类展示。
 *
 * <p>与数据库分组({@link DatabaseGroup})的区别:数据库分组按「连接」划分、成员是库名;
 * 表分组按「连接 + 库」划分、成员是表名。故同一个库可以在不同连接下有各自独立的分组。
 *
 * <p>分组成员不在本表:成员关系存在 tbl_group_tables,按需回填到 {@code tables};
 * 故新增/改名这类单条查询返回的对象 {@code tables} 为空。
 */
@Data
public class TableGroup {
    /** 分组主键,也是分组列表的排序键(按 id 升序)。 */
    private long id;
    /** 所属连接 ID。分组是「连接 + 库」内的概念,保存成员时以它为准。 */
    private long connectionId;
    /** 所属库名。分组只在同一个库内生效,不能跨库引用。 */
    private String db;
    /** 分组名称。服务层保证去空白后非空且不超过 50 字(按码点计)。 */
    private String name;
    /** 分组备注,可为空串。 */
    private String remark;
    /** 组内表数量。列表查询时按成员数实时得出,单条查询时为 0。 */
    private int count;
    /**
     * 组内表名列表,按表名排序;仅列表查询回填。
     * 空列表不参与序列化(与 Go 的 {@code json:"tables,omitempty"} 对齐),前端据此判断有无成员。
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<String> tables = new ArrayList<>();
    /** 创建时间，由 MySQL 元数据层写入的时间文本。 */
    private String createdAt;
    /** 更新时间,改名称/备注时刷新。 */
    private String updatedAt;
}
