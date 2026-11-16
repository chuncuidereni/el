/**
 * 【文件职责】本文件定义分组管理模块的领域数据模型 DataSourceGroup，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.group;

import lombok.Data;

/**
 * 数据源分组(对应 MySQL 的 ds_groups 表)，用于把数据源列表按业务分组归类。
 * 由 GroupRepository 读写、GroupController 暴露接口、GroupService 负责校验；前端见 DSGroup。
 */
@Data
public class DataSourceGroup {
    /** 分组主键；也是分组列表的排序键(按 id 升序)。 */
    private long id;
    /** 分组名称。服务层已保证去空白后非空且不超过 50 个字(按码点计)。 */
    private String name;
    /** 分组备注。可为空串，写库前统一 trim。 */
    private String remark;
    /** 组内数据源数量。不是 ds_groups 的列，而是查询时用相关子查询实时算出的别名列，故永不为 null。 */
    private int count;
    /** 创建时间，由 MySQL 元数据层写入的时间文本。 */
    private String createdAt;
    /** 更新时间，同上为文本；重命名/改备注时由 SQL 的 datetime('now') 刷新。 */
    private String updatedAt;
}
