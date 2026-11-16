/**
 * 【文件职责】本文件定义数据源管理模块的请求/写入参数模型 SftpRemotePreviewRequest，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.datasource;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** SFTP 远端单文件预览，可使用已保存数据源 ID 或直连参数。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class SftpRemotePreviewRequest extends SftpBrowseRequest {
    /** 预览行数；null 或 <=0 取 10，并由服务层夹取到 1000 上限，防止一次拉回过多数据。 */
    private Integer limit;
    /** 首行是否表头；CSV 默认 true，与目录采集一致，前端可明确关闭。 */
    private Boolean header;
    private String csvEncoding;
    private Boolean ignoreExtraCsvColumns;
}
