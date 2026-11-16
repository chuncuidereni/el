/**
 * 【文件职责】本文件定义HTTP 公共基础设施模块的领域结构模型 Column，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.common;

import lombok.Data;

/** DuckDB 解析出的列描述(name,type)。 */
@Data
public class Column {
    /** 列名。 */
    private String name;
    /** 列类型文本(如 BIGINT/VARCHAR/DateTime64 等)。 */
    private String type;
}
