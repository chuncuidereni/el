/**
 * 【文件职责】本文件定义查询执行模块的领域数据模型 SqlQuery，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.query;

import com.el2ck.entity.queryworkflow.QueryWorkflowConfig;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * 已保存的 SQL 查询(对应 MySQL 的 sql_queries 表)，「数据探索」页左侧列表的一条。
 *
 * <p>查询本身只保存不执行:真正跑 SQL 走 `POST /api/connections/{id}/query`。
 */
@Data
public class SqlQuery {
    /** 主键,也是列表排序键(按 id 倒序,最近保存的在最上面)。 */
    private long id;
    /** 查询名称。服务层保证去空白后非空。 */
    private String name;
    /** 备注/说明,可为空串。 */
    private String description;
    /** 绑定的库(如 ods_data / dwd_data);新建时为空则落默认库。 */
    private String database;
    /** SQL 正文。 */
    private String sqlText;
    /**
     * 可视化构建器配置(QueryWorkflowConfig 的 JSON 文本)。
     * 空串表示这条查询不是用可视化构建器创建的 —— 空值不序列化(对齐 Go 的 {@code json:",omitempty"}),
     * 前端据此决定是否显示「用可视化编辑」入口。
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String visualConfig;
    /** 所属分组 ID;0 = 默认分组(未分组)。 */
    private long groupId;
    /** 创建时间，由 MySQL 元数据层写入的时间文本。 */
    private String createdAt;
    /** 更新时间,保存/移组时刷新。 */
    private String updatedAt;
}
