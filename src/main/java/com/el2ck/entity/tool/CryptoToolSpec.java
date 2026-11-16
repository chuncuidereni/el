/**
 * 【文件职责】本文件定义密码与哈希工具模块的领域结构模型 CryptoToolSpec，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.tool;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 一个加密算法的规格,对应 Go {@code cryptotool.Spec}。
 *
 * <p>前端拿它渲染下拉、密钥/IV 长度提示与「不推荐用于新数据」的告警;
 * 密钥长度也由它派生(见 {@code CryptoTool#generateKey}),不接受调用方指定。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CryptoToolSpec {
    /** 算法标识(如 {@code aes-256-cbc}),接口的入参就是它。 */
    private String id;
    /** 展示名(如 {@code AES-256-CBC})。 */
    private String name;
    /** 期望密钥字节数。 */
    private int keyLen;
    /** 需要的 IV 字节数;GCM 为 0(自动生成)。 */
    private int ivLen;
    /** 带 nonce + 完整性校验(GCM)。 */
    private boolean aead;
    /** 只用于对接老系统(界面上要标出来)。 */
    private boolean legacy;
    /** 给用户看的提示(ECB 无校验、3DES 可用 16 字节密钥等);没有就不出现。 */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String hint;
}
