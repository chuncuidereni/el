/**
 * 【文件职责】本文件定义查询工作流模块的领域数据模型 QueryWorkflow，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.queryworkflow;

import lombok.Data;

/**
 * 查询工作流定义，对应 Go {@code model.QueryWorkflow}(MySQL 表 query_workflows)。
 *
 * <p>它是「只 SELECT 不落库」的多表查询配置:向导生成的 SQL 每次执行都是现算的,
 * 不产生任何 ClickHouse 侧产物,故删除配置只会一并删掉运行历史,不需要清理 CK 对象。
 */
@Data
public class QueryWorkflow {
    /** 主键,也是列表的排序键(倒序)。 */
    private long id;
    /** 名称。服务层保证去空白后非空。 */
    private String name;
    /** 描述,可为空串。 */
    private String description;
    /** 是否启用；MySQL 中以 0/1 存储。 */
    private boolean enabled;
    /** 向导完整配置(序列化进 config_json 列)。 */
    private QueryWorkflowConfig config = new QueryWorkflowConfig();
    /** 创建时间。 */
    private String createdAt;
    /** 更新时间。 */
    private String updatedAt;
}
