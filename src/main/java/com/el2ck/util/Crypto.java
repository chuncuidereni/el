package com.el2ck.util;

/**
 * 【文件职责】提供应用配置密钥下的加解密能力，用于保护持久化连接凭据。
 */

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 对称加解密(AES-256-GCM)，与 Go 的 internal/crypto 逐字节兼容。
 * 兼容性关键点(决定能否读取旧版 SQLite 中已存的密文)：
 *  1. 密钥 = SHA-256(配置 SecretKey) 取 32 字节 → AES-256；
 *  2. 每次加密使用随机 12 字节 nonce(AES-GCM 标准 nonce 长度)；
 *  3. 输出格式 = Base64(nonce + 密文)，其中 "密文" 是 GCM Seal 的结果(含认证标签)；
 *  4. 空明文直接返回空串(表示"未设置密文")，空密文解密也返回空串。
 * Java 默认 GCM 认证标签为 128bit(16 字节)，与 Go cipher.NewGCM 相同。
 */
public final class Crypto {

    // 安全随机源：为每次加密生成不同的随机 nonce
    private static final SecureRandom RANDOM = new SecureRandom();
    /** GCM nonce 固定 12 字节(与 Go cipher.NewGCM 的 NonceSize 一致)。 */
    private static final int NONCE_SIZE = 12;

    /** 工具类私有构造器：禁止实例化。 */
    private Crypto() {
    }

    /** 由配置 SecretKey 经 SHA-256 派生出 32 字节 AES-256 密钥(与 Go 逐字节一致)。 */
    private static SecretKeySpec keyOf(String secret) throws Exception {
        // 对配置密钥串计算 SHA-256 摘要，摘要直接作为 AES 密钥
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] key = md.digest(secret.getBytes(StandardCharsets.UTF_8));
        // 把密钥字节包装为 AES 算法的 SecretKeySpec 供 Cipher 使用
        return new SecretKeySpec(key, "AES");
    }

    /**
     * 加密明文并返回 Base64 编码的密文字符串。
     *
     * @param secret    加密密钥(经 SHA-256 派生)
     * @param plaintext 明文；为空串时直接返回空串(不加密，表示未设置密文)
     * @return Base64(nonce + GCM 密文) 形式的密文字符串
     */
    public static String encrypt(String secret, String plaintext) {
        // 明文为空时不做加密，直接返回空串表示"未设置密文"
        if (plaintext == null || plaintext.isEmpty()) {
            return "";
        }
        try {
            SecretKeySpec key = keyOf(secret);
            // 生成随机 12 字节 nonce(AES-GCM 标准长度)
            byte[] nonce = new byte[NONCE_SIZE];
            RANDOM.nextBytes(nonce);
            // 采用 AES/GCM/NoPadding，认证标签长度取 128bit
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            // 对 UTF-8 明文字节执行 GCM 加密，得到含认证标签的密文
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            // 拼装输出缓冲：nonce 前置、密文在后，与 Go 的输出布局一致
            byte[] out = new byte[nonce.length + ct.length];
            System.arraycopy(nonce, 0, out, 0, nonce.length);
            System.arraycopy(ct, 0, out, nonce.length, ct.length);
            // 对 nonce+密文整体做 Base64 编码后返回
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            // 加密失败(算法不可用等)属服务端配置问题，包装为未检查异常上抛由上层转为 500
            throw new IllegalStateException("加密失败: " + e.getMessage(), e);
        }
    }

    /**
     * 解密 {@link #encrypt(String, String)} 产生的密文；空密文返回空串。
     *
     * @param secret     与加密时一致的密钥(经 SHA-256 派生)
     * @param ciphertext Base64 编码的密文(nonce 前置)
     * @return 解密还原的明文字符串
     * @throws IllegalArgumentException 密文格式错误/过短/认证失败(密钥与加密时不符)
     */
    public static String decrypt(String secret, String ciphertext) {
        // 密文为空时直接返回空串
        if (ciphertext == null || ciphertext.isEmpty()) {
            return "";
        }
        try {
            SecretKeySpec key = keyOf(secret);
            // 先对密文串做 Base64 解码，还原出 nonce+密文的原始字节
            byte[] raw = Base64.getDecoder().decode(ciphertext);
            // 总字节数不足 nonce 长度即视为密文过短，判为非法输入
            if (raw.length < NONCE_SIZE) {
                throw new IllegalArgumentException("密文过短");
            }
            // 拆分：前 12 字节为 nonce，其余为 GCM 密文(含认证标签)
            byte[] nonce = new byte[NONCE_SIZE];
            byte[] ct = new byte[raw.length - NONCE_SIZE];
            System.arraycopy(raw, 0, nonce, 0, NONCE_SIZE);
            System.arraycopy(raw, NONCE_SIZE, ct, 0, ct.length);
            // 以 AES/GCM/NoPadding 解密，密钥不符或标签校验失败会抛异常
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            byte[] pt = cipher.doFinal(ct);
            // 将解密出的明文字节按 UTF-8 还原为字符串
            return new String(pt, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // Base64 解码失败或「密文过短」：消息本身已说明原因，原样透传(不再套"密钥不符"的壳)
            throw new IllegalArgumentException(e.getMessage(), e);
        } catch (Exception e) {
            // 其余异常(主要是 AEADBadTagException：密钥不符或密文被篡改)统一归为解密失败
            throw new IllegalArgumentException("解密失败(密钥与加密时不符): " + e.getMessage(), e);
        }
    }
}
