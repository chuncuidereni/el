/**
 * 【文件职责】本文件定义表管理与数据编辑模块的查询或操作结果模型 DataPage，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.table;

import lombok.Data;

import java.util.List;

/**
 * 表数据分页结果。total 为满足条件的真实总行数。
 */
@Data
public class DataPage {
    /** 返回列的列名(与 types/rows 顺序对齐)。 */
    private List<String> columns;
    /** 返回列的 ClickHouse 类型。 */
    private List<String> types;
    /** 数据行(每行与 columns 顺序对齐)。 */
    private List<List<Object>> rows;
    /** 满足条件的真实总行数。 */
    private Long total;
    /** 本页起始偏移。 */
    private Integer offset;
    /** 每页行数。 */
    private Integer size;
    /** 是否还有下一页。 */
    private Boolean hasMore;
}
