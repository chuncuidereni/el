/**
 * 【文件职责】本文件定义表管理与数据编辑模块的请求/写入参数模型 MaterializeRequest，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.table;

import lombok.Data;

/**
 * 圈选落表请求:把当前过滤条件圈出的结果落成一张「数据包」。对应 Go service.MaterializeRequest。
 */
@Data
public class MaterializeRequest {
    /** 落到哪个库;空 = 与源表同库。 */
    private String targetDb;
    /** 数据包表名。 */
    private String targetTable;
    /** 圈选条件(与数据页同一套渲染,所以「看到的行」=「存进去的行」)。 */
    private FilterNode filter;
    /** 去重保留记录的排序，不同于新表的物理排序键 orderBy。 */
    private java.util.List<SortSpec> orders;
    /** 存哪些列;空 = 全部。建议含号码列,否则后续没法按号码使用。 */
    private java.util.List<String> columns;
    /** 排序键列;空 = 号码列(取不到就用 tuple(),即不设排序键)。 */
    private String orderBy;
    /** 同名表已存在时是否先删除。 */
    private Boolean dropIfExists;
    /** 只生成 SQL 不执行(界面「预览」用:落库是写操作,先看清楚要跑什么)。 */
    private Boolean dryRun;
}
