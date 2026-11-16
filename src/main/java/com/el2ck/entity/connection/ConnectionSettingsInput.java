package com.el2ck.entity.connection;

import com.el2ck.entity.system.AppSettings;
import lombok.Data;

/**
 * 同时保存连接与全局高级参数的请求；两部分都必须提供。
 * connection 中密码留空沿用已存密文，advancedScope 必须为 global。
 */
@Data
public class ConnectionSettingsInput {
    /** 当前连接的完整编辑内容，不包含返回给浏览器的密码密文。 */
    private ConnectionInput connection;
    /** 本次要写入的全局高级参数，提交成功后该连接继承这些值。 */
    private AppSettings globalSettings;
}
