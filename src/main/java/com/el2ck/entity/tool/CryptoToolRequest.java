/**
 * 【文件职责】本文件定义密码与哈希工具模块的请求/写入参数模型 CryptoToolRequest，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.tool;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 加密解密工具:一次批量执行的入参,对应 Go {@code api.cryptoIn}。
 */
@Data
public class CryptoToolRequest {
    /** 算法标识(见 {@code CryptoTool.SPECS})。 */
    private String algo;
    /** 方向:encrypt / decrypt。空 = encrypt(与 Go 一致)。 */
    private String direction;
    /** 密钥。按 {@link #keyFormat} 解析。 */
    private String key;
    /** IV。ECB 不需要;GCM 自动生成,传了也不认。 */
    private String iv;
    /** 密钥与 IV 共用的编码:hex / base64 / text。空 = hex。 */
    private String keyFormat;
    /** 解密时:密文是 base64 还是 hex。空 = base64。 */
    private String inFormat;
    /** 加密时:输出 base64 还是 hex。空 = base64。 */
    private String outFormat;
    /** 要处理的值,每行一条。 */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<String> values = new ArrayList<>();
}
