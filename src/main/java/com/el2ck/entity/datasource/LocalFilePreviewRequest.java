/**
 * 【文件职责】本文件定义数据源管理模块的请求/写入参数模型 LocalFilePreviewRequest，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.datasource;

import lombok.Data;

/** 数据源本地文件(未保存前)预览请求。 */
@Data
public class LocalFilePreviewRequest {
    /** 文件类型 csv/json/xlsx。 */
    private String type;
    /** 本地文件绝对路径。 */
    private String path;
    /** 预览最大行数。 */
    private Integer limit;
    /** 是否将首行作为列头。 */
    private Boolean header;
    /** 手动指定的列名(可选)。 */
    private java.util.List<String> names;
    /** 自定义分隔符(CSV/文本,缺省逗号)。 */
    private String delimiter;
}
