/**
 * 【文件职责】本文件定义数据源管理模块的查询或操作结果模型 SftpBrowseResult，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.datasource;

import lombok.Data;

import java.util.List;

/**
 * SFTP 单层目录列举结果(DataSourceService.browseSftp 的返回体，对应 Go BrowseSFTPDir)。
 * 只列一层、不解析文件内容：前端文件选择器靠它逐级下钻；要看目录结构请用 SftpDirPreview。
 */
@Data
public class SftpBrowseResult {
    /** 实际列举的远程目录：入参未指定时为登录后的当前工作目录。原样回显，便于前端据此拼下一级路径。 */
    private String path;
    /** 该层子项(. 与 .. 已剔除)，服务端按「目录优先、名称不区分大小写」排好序，前端不必再排。 */
    private List<SftpEntry> entries;
}
