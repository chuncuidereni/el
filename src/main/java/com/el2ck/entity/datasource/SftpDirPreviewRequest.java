/**
 * 【文件职责】本文件定义数据源管理模块的请求/写入参数模型 SftpDirPreviewRequest，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.datasource;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 目录级 SFTP 预览请求：递归找出目录下所有匹配文件并归并出「文件结构 + 分组」。
 * 只复用父类的 id 与直连凭据(host/port/username/password)，起始目录改用 remotePath，
 * 继承来的 path 在此场景不参与解析——目录预览与单层浏览不要混用同一个字段。
 * 明文直连时 host/username/password 三项必须齐全，否则服务层判 400。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class SftpDirPreviewRequest extends SftpBrowseRequest {
    /** 要分析的远程目录；空时回退到数据源配置里的 remotePath，仍为空则用登录后的当前工作目录。 */
    private String remotePath;
    /** 文件名通配符(glob)；空时默认 *.csv。不含 glob 元字符时退化为前缀匹配。 */
    private String filePattern;
    /** 首行是否表头；null 视为 true——只有显式传 false 才按无表头解析。 */
    private Boolean hasHeader;
    /** 与任务读取保持一致；未传时默认忽略额外尾列。 */
    private Boolean ignoreExtraCsvColumns;
    private String csvEncoding;
}
