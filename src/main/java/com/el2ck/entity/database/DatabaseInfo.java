/**
 * 【文件职责】本文件定义数据库目录浏览模块的展示或状态视图模型 DatabaseInfo，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.database;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/** ClickHouse 数据库信息(list databases 结果)。remark = 库注释(COMMENT)。 */
@Data
public class DatabaseInfo {
    /** 数据库名。 */
    private String name;
    /** 库注释(COMMENT);为空时省略。 */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String remark;
}
