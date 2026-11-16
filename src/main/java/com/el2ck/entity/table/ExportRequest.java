/**
 * 【文件职责】本文件定义表管理与数据编辑模块的请求/写入参数模型 ExportRequest，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.table;

import lombok.Data;

import java.util.List;

/** 表导出 CSV 请求。 */
@Data
public class ExportRequest {
    /** 需要导出的列(为空表示导出全部列)。 */
    private List<String> columns;
    /** 导出 CSV 的列头文本(与 columns 对齐,可自定义)。 */
    private List<String> headerNames;
    /** 过滤条件(可空,为空导出全表)。 */
    private FilterNode filter;
    /** 是否在 CSV 首行输出列头。 */
    private Boolean withHeader;
    /** \t / tab → TAB,其余默认为逗号 */
    private String delimiter;
    /** 导出行数上限(为空表示不限制)。 */
    private Integer limit;
    /** 排序(可空);与数据页用同一份渲染,保证「看到的顺序」=「导出的顺序」。 */
    private List<SortSpec> orders;
}
