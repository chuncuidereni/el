/**
 * 【文件职责】本文件定义查询执行模块的请求/写入参数模型 SqlQueryInput，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.query;

import lombok.Data;

/**
 * 已保存 SQL 查询的新建/更新入参(对应 Go 的 model.SQLQueryInput)。
 *
 * <p>字段与 {@link SqlQuery} 基本一致但不含 id 与时间戳 —— 那两个由服务端决定。
 */
@Data
public class SqlQueryInput {
    /** 查询名称:必填,空白由服务层判 400。 */
    private String name;
    /** 备注/说明。 */
    private String description;
    /** 绑定的库;新建时为空则落默认库 ods_data。 */
    private String database;
    /** SQL 正文。 */
    private String sqlText;
    /**
     * 可视化构建器配置(JSON 文本)。
     * ⚠️ 更新时未携带该字段会把它清空 —— 与 Go 的行为一致(Go 的 Update 同样整体覆盖该列)。
     */
    private String visualConfig;
    /** 所属分组 ID;0 = 默认分组。非 0 时服务层校验分组存在。 */
    private Long groupId;
}
