/**
 * 【文件职责】本文件定义密码与哈希工具模块的领域数据模型 CryptoToolKeyPair，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.tool;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * 加密解密工具:生成的一组随机密钥与 IV,对应 Go {@code cryptotool.KeyPair}。
 *
 * <p>⚠️ 长度【必须从算法规格派生】,不能由调用方指定:给 AES-256 生成 16 字节、
 * 或给 3DES 生成 16 字节 IV(它块是 8)是最容易犯又最难查的错。
 */
@Data
public class CryptoToolKeyPair {
    /** 密钥(小写十六进制)。 */
    private String keyHex;
    /** 同一份密钥的 base64(界面两种都给,粘贴到哪里都不用再转)。 */
    private String keyB64;
    /** IV(十六进制);不需要 IV 的算法不出现。 */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String ivHex;
    /** 同一份 IV 的 base64。 */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String ivB64;
    /** 密钥字节数。 */
    private int keyLen;
    /** IV 字节数(0 = 不需要)。 */
    private int ivLen;
    /** 为哪个算法生成的。 */
    private String forAlgo;
    /** 该算法的展示名。 */
    private String algoName;
    /** 「仅用于对接老系统」「ECB 不需要 IV」这类提示;没有就不出现。 */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String keyNotice;
}
