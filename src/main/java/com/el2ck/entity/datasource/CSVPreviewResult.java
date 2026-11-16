/**
 * 【文件职责】本文件定义数据源管理模块的查询或操作结果模型 CSVPreviewResult，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.datasource;

import com.el2ck.entity.common.Column;

import com.el2ck.duck.DuckQueryResult;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * 数据源 CSV/JSON 文件预览的响应体(服务层组装)。
 *
 * 与前端契约保持一致:
 *  columns = DESCRIBE 出的文件字段(name/type,已应用 names 映射);
 *  rows    = 实际查询前 N 行的结果对象(columns/types/rows,单元格统一字符串);
 *  total   = 文件总行数;preview-file 额外返回 has_header(自动行头检测)。
 */
@Data
public class CSVPreviewResult {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String csvEncoding;
    /** 文件字段列表(name/type,已应用 names 映射);解析失败时省略。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<Column> columns;
    /** 前 N 行数据的查询结果对象;省略时表示无数据或失败。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private DuckQueryResult rows;
    /** 文件总行数;省略时表示未知。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long total;
    /** 自动行头检测结果(preview-file 接口额外返回)。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("has_header")
    private Boolean hasHeader;
}
