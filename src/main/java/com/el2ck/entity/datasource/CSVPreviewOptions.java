/**
 * 【文件职责】本文件定义数据源管理模块的领域数据模型 CSVPreviewOptions，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.datasource;

import lombok.Data;

/** 已保存数据源(csv/json/xlsx)的预览选项。 */
@Data
public class CSVPreviewOptions {
    /** 预览读取的最大行数;为 null 时使用后端默认(如 100)。 */
    private Integer limit;
    /** 是否将首行作为列头。 */
    private Boolean header;
    /** 手动指定的列名(可选);非空时覆盖自动解析出的列名。 */
    private java.util.List<String> names;
    /** 自定义分隔符(CSV/文本,缺省逗号)。 */
    private String delimiter;
}
