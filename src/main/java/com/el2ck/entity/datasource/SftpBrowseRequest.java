/**
 * 【文件职责】本文件定义数据源管理模块的请求/写入参数模型 SftpBrowseRequest，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.datasource;

import lombok.Data;

/** Go BrowseSFTPDir 的请求体；id 优先于直连参数。 */
@Data
public class SftpBrowseRequest {
    /** 已保存数据源的 ID。>0 时凭据全部从库里解密获得，请求体里的直连参数一律忽略；
     *  为 null 或 <=0 才走下面的直连字段(新增向导场景，连接尚未落库)。 */
    private Long id;
    /** 直连模式下的 SFTP 主机；id 模式不读此字段。 */
    private String host;
    /** 直连模式端口；缺省或 <=0 回落 22，超过 65535 由服务层判 400。 */
    private Integer port;
    /** 直连模式用户名。 */
    private String username;
    /** 直连模式密码；允许为空(免密/密钥场景)，浏览与预览不做强校验。 */
    private String password;
    /** 起始远程路径；空表示用登录后的当前工作目录。 */
    private String path;
}
