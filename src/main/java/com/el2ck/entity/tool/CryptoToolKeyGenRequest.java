/**
 * 【文件职责】本文件定义密码与哈希工具模块的请求/写入参数模型 CryptoToolKeyGenRequest，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.tool;

import lombok.Data;

/**
 * 加密解密工具:生成密钥的入参,对应 Go {@code api.keyGenIn}。
 *
 * <p>只有一个字段是有意的:密钥/IV 的长度由算法规格决定,调用方给不了(给了也不认)。
 */
@Data
public class CryptoToolKeyGenRequest {
    /** 算法标识。 */
    private String algo;
}
