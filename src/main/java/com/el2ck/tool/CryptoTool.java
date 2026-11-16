/**
 * 【文件职责】AES/SM4/3DES 对称加解密工具，统一密钥、IV、填充、GCM nonce/tag 和兼容性校验。
 * <p>维护本文件时应同步检查调用方契约、失败恢复、并发边界和敏感数据处理，避免只修改单侧实现。
 */
package com.el2ck.tool;

import com.el2ck.entity.tool.CryptoToolKeyPair;
import com.el2ck.entity.tool.CryptoToolSpec;
import com.el2ck.exception.ApiException;
import com.el2ck.util.GoFmt;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 对称加解密(AES / SM4 / 3DES),对应 Go {@code internal/cryptotool}。
 *
 * <p>算法只来自两处,都不引新依赖:AES / 3DES 走 JDK 标准库,SM4(国密)走 BouncyCastle
 * (本仓库早已依赖:程序算子的 SM3 用的就是它)。
 *
 * <p>⚠️ 四个必须做对、所以每条都有测试的点:
 * <ol>
 *   <li><b>块大小不都是 16</b> —— DES/3DES 是 8 字节。Java 的 {@code PKCS5Padding} 实际就是
 *       PKCS7(按块大小补齐),所以填补边界跟着 {@code blockSize} 走天然正确;但 IV 长度
 *       必须按算法规格校验,写死 16 会在 3DES 上直接报错,或者更糟:静默错。</li>
 *   <li><b>2-key 3DES</b> —— Java/老系统的 DESede 常给 16 字节密钥(K1K2),而底层只收
 *       24 字节 → 这里按 K1K2K1 展开(与 Go、与 Java DESede 自身行为一致)。</li>
 *   <li><b>GCM 不是「加密即得密文」</b> —— 它要 nonce,输出必须带上。本工具输出
 *       {@code nonce || 密文+tag},与 Go 的同名工具、以及本仓库连接密码加密
 *       ({@code util/Crypto})完全同一口径,两边能互解。</li>
 *   <li><b>ECB / CBC 都不带完整性校验</b> —— 解错密钥会得到一段「看起来正常」的垃圾,
 *       所以工具层要如实把这句提示给用户(见 {@link CryptoToolSpec#getHint()})。</li>
 * </ol>
 */
public final class CryptoTool {

    /** 支持的算法。刻意不长:覆盖对接里真正会遇到的那些。 */
    public static final List<CryptoToolSpec> SPECS = Collections.unmodifiableList(Arrays.asList(
            new CryptoToolSpec("aes-128-cbc", "AES-128-CBC", 16, 16, false, false, null),
            new CryptoToolSpec("aes-192-cbc", "AES-192-CBC", 24, 16, false, false, null),
            new CryptoToolSpec("aes-256-cbc", "AES-256-CBC", 32, 16, false, false, null),
            new CryptoToolSpec("aes-128-ecb", "AES-128-ECB", 16, 0, false, true,
                    "ECB 无 IV、无完整性校验,仅用于对接要求它的老系统"),
            new CryptoToolSpec("aes-256-ecb", "AES-256-ECB", 32, 0, false, true,
                    "ECB 无 IV、无完整性校验,仅用于对接要求它的老系统"),
            new CryptoToolSpec("aes-128-gcm", "AES-128-GCM", 16, 0, true, false,
                    "自带完整性校验,解不出来就是密钥/密文不对"),
            new CryptoToolSpec("aes-256-gcm", "AES-256-GCM", 32, 0, true, false,
                    "自带完整性校验,解不出来就是密钥/密文不对"),
            new CryptoToolSpec("sm4-cbc", "SM4-CBC(国密)", 16, 16, false, false, null),
            new CryptoToolSpec("sm4-ecb", "SM4-ECB(国密)", 16, 0, false, true,
                    "ECB 无 IV、无完整性校验,仅用于对接要求它的老系统"),
            new CryptoToolSpec("3des-cbc", "3DES-CBC", 24, 8, false, true,
                    "密钥可给 24 字节(K1K2K3)或 16 字节(K1K2,按 Java DESede 展开为 K1K2K1)"),
            new CryptoToolSpec("3des-ecb", "3DES-ECB", 24, 0, false, true,
                    "无 IV、无完整性校验,仅用于对接要求它的老系统")));

    /** GCM 的 nonce 长度(标准值 12,与 Go 的 {@code cipher.NewGCM} 一致)。 */
    private static final int GCM_NONCE_LEN = 12;

    /** GCM 认证标签长度(位)。 */
    private static final int GCM_TAG_BITS = 128;

    private static final SecureRandom RANDOM = new SecureRandom();

    /** BouncyCastle 提供者实例:SM4 只在它里面。按实例传入,不做全局 {@code Security.addProvider}。 */
    private static final BouncyCastleProvider BC = new BouncyCastleProvider();

    private CryptoTool() {
    }

    /** 按 id 取规格(找不到返回 null)。 */
    public static CryptoToolSpec specOf(String id) {
        String key = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        for (CryptoToolSpec s : SPECS) {
            if (s.getId().equals(key)) {
                return s;
            }
        }
        return null;
    }

    /** 按 id 取规格,取不到报错。 */
    public static CryptoToolSpec requireSpec(String id) {
        CryptoToolSpec s = specOf(id);
        if (s == null) {
            throw ApiException.validation(String.format("不支持的算法 %s", GoFmt.q(id)));
        }
        return s;
    }

    /**
     * 校验密钥/IV 长度,给出人话错误(而不是等底层抛 {@code InvalidKeyException: Invalid AES key length})。
     *
     * <p>顺序与 Go 一致:先看密钥,密钥不过就不再看 IV。
     */
    public static void checkKeyIv(CryptoToolSpec s, byte[] key, byte[] iv) {
        boolean tripleDes = s.getId().startsWith("3des-");
        if (tripleDes) {
            if (key.length != 24 && key.length != 16) {
                throw ApiException.validation(String.format(
                        "%s 的密钥需要 24 字节(K1K2K3)或 16 字节(K1K2),当前 %d 字节",
                        s.getName(), key.length));
            }
        } else if (key.length != s.getKeyLen()) {
            throw ApiException.validation(String.format("%s 的密钥需要 %d 字节,当前 %d 字节",
                    s.getName(), s.getKeyLen(), key.length));
        }
        if (s.getIvLen() > 0 && iv.length != s.getIvLen()) {
            throw ApiException.validation(String.format("%s 的 IV 需要 %d 字节,当前 %d 字节",
                    s.getName(), s.getIvLen(), iv.length));
        }
    }

    /** 加密一条,返回密文原始字节(GCM 时是 {@code nonce || 密文+tag})。 */
    public static byte[] encrypt(String id, byte[] key, byte[] iv, byte[] plaintext) {
        CryptoToolSpec s = requireSpec(id);
        checkKeyIv(s, key, iv);
        if (s.isAead()) {
            byte[] nonce = new byte[GCM_NONCE_LEN];
            RANDOM.nextBytes(nonce);
            byte[] sealed = doFinal(s, key, nonce, plaintext, Cipher.ENCRYPT_MODE);
            byte[] out = new byte[nonce.length + sealed.length];
            System.arraycopy(nonce, 0, out, 0, nonce.length);
            System.arraycopy(sealed, 0, out, nonce.length, sealed.length);
            return out;
        }
        return doFinal(s, key, iv, plaintext, Cipher.ENCRYPT_MODE);
    }

    /** 解密一条。 */
    public static byte[] decrypt(String id, byte[] key, byte[] iv, byte[] ciphertext) {
        CryptoToolSpec s = requireSpec(id);
        checkKeyIv(s, key, iv);
        if (s.isAead()) {
            if (ciphertext.length < GCM_NONCE_LEN) {
                throw ApiException.validation(String.format(
                        "密文太短:不是 %s 的格式(nonce 至少要 %d 字节)", s.getName(), GCM_NONCE_LEN));
            }
            byte[] nonce = Arrays.copyOfRange(ciphertext, 0, GCM_NONCE_LEN);
            byte[] body = Arrays.copyOfRange(ciphertext, GCM_NONCE_LEN, ciphertext.length);
            try {
                return doFinal(s, key, nonce, body, Cipher.DECRYPT_MODE);
            } catch (ApiException e) {
                // GCM 自带校验:这里失败就意味着密钥或密文不对,不是「解出来一段垃圾」
                throw ApiException.validation(
                        "完整性校验失败:密钥或密文不对(也可能密文被截断/改动过)");
            }
        }
        return doFinal(s, key, iv, ciphertext, Cipher.DECRYPT_MODE);
    }

    /**
     * 按算法规格生成随机密钥(以及需要的 IV)。
     *
     * <p>⚠️ 长度【必须从 {@link #SPECS} 派生】,不能由调用方指定 —— 给 AES-256 生成 16 字节、
     * 或给 3DES 生成 16 字节 IV(它块是 8)是最容易犯又最难查的错。
     *
     * <p>3DES 默认给 24 字节(K1K2K3);要对接只认 16 字节(K1K2)的老系统,把前 16 字节拿去用即可 ——
     * 本类会按 K1K2K1 展开,两条路都通。
     */
    public static CryptoToolKeyPair generateKey(String id) {
        CryptoToolSpec s = requireSpec(id);
        byte[] key = new byte[s.getKeyLen()];
        RANDOM.nextBytes(key);
        CryptoToolKeyPair out = new CryptoToolKeyPair();
        out.setKeyHex(toHex(key));
        out.setKeyB64(java.util.Base64.getEncoder().encodeToString(key));
        out.setKeyLen(key.length);
        out.setIvLen(s.getIvLen());
        out.setForAlgo(s.getId());
        out.setAlgoName(s.getName());
        if (s.getIvLen() > 0) {
            byte[] iv = new byte[s.getIvLen()];
            RANDOM.nextBytes(iv);
            out.setIvHex(toHex(iv));
            out.setIvB64(java.util.Base64.getEncoder().encodeToString(iv));
        }
        if (s.isLegacy()) {
            out.setKeyNotice("该算法已不推荐用于新数据,仅用于对接既有系统");
        }
        if (s.getIvLen() == 0 && !s.isAead()) {
            out.setKeyNotice("该模式不需要 IV(ECB),同一密钥下相同明文永远得到相同密文");
        }
        return out;
    }

    // =====================================================================
    // 底层:一次 Cipher 调用 + 错误翻译
    // =====================================================================

    /**
     * Go 用 {@code pkcs7Pad} 自己补,Java 的 {@code PKCS5Padding} 对任意块大小都是 PKCS7 语义,
     * 所以填补结果逐字节相同 —— 与 openssl 的向量对得上(见 CryptoToolTest)。
     */
    private static byte[] doFinal(CryptoToolSpec s, byte[] key, byte[] iv, byte[] in, int mode) {
        Cipher cipher = cipherOf(s, key, iv, mode);
        try {
            return cipher.doFinal(in);
        } catch (IllegalBlockSizeException e) {
            // 长度不是块大小的整数倍 —— 只可能是密钥/IV/编码不对
            throw ApiException.validation(s.getName() + " 的密文长度不是块大小的整数倍(密钥/IV/编码大概率不对)");
        } catch (BadPaddingException e) {
            // ⚠️ CBC/ECB 没有完整性校验:解错密钥时这里才炸,而且报错本身就是唯一的线索
            throw ApiException.validation(s.getName() + " 的填补字节不合法(密钥/IV/编码大概率不对)");
        } catch (GeneralSecurityException e) {
            throw ApiException.validation(s.getName() + " 加解密失败: " + e.getMessage());
        }
    }

    private static Cipher cipherOf(CryptoToolSpec s, byte[] key, byte[] iv, int mode) {
        try {
            // ⚠️ 不能把 null 当 provider 传进去(会抛 Missing provider):没有指定提供者时
            //    必须走单参重载,由 JCA 自己去找。
            Cipher cipher = s.getId().startsWith("sm4-")
                    ? Cipher.getInstance(transformationOf(s), BC)
                    : Cipher.getInstance(transformationOf(s));
            SecretKeySpec keySpec = new SecretKeySpec(keyOf(s, key), keyAlgoOf(s));
            if (s.isAead()) {
                cipher.init(mode, keySpec, new GCMParameterSpec(GCM_TAG_BITS, iv));
            } else if (usesIv(s)) {
                cipher.init(mode, keySpec, new IvParameterSpec(iv));
            } else {
                cipher.init(mode, keySpec);
            }
            return cipher;
        } catch (GeneralSecurityException e) {
            throw ApiException.validation(s.getName() + " 初始化失败: " + e.getMessage());
        }
    }

    /**
     * 2-key 3DES:16 字节的 K1K2 展开成 24 字节的 K1K2K1。
     *
     * <p>Java 的 DESede 自己也是这么处理的,不展开就对不上老系统。
     */
    private static byte[] keyOf(CryptoToolSpec s, byte[] key) {
        if (s.getId().startsWith("3des-") && key.length == 16) {
            byte[] out = new byte[24];
            System.arraycopy(key, 0, out, 0, 16);
            System.arraycopy(key, 0, out, 16, 8);
            return out;
        }
        return key;
    }

    private static String transformationOf(CryptoToolSpec s) {
        String id = s.getId();
        String mode = id.endsWith("-gcm") ? "GCM" : (id.endsWith("-ecb") ? "ECB" : "CBC");
        String padding = "GCM".equals(mode) ? "NoPadding" : "PKCS5Padding";
        return baseAlgoOf(s) + "/" + mode + "/" + padding;
    }

    private static String baseAlgoOf(CryptoToolSpec s) {
        String id = s.getId();
        if (id.startsWith("aes-")) {
            return "AES";
        }
        if (id.startsWith("sm4-")) {
            return "SM4";
        }
        return "DESede";
    }

    private static String keyAlgoOf(CryptoToolSpec s) {
        return baseAlgoOf(s);
    }

    private static boolean usesIv(CryptoToolSpec s) {
        return !s.isAead() && !s.getId().endsWith("-ecb");
    }

    private static String toHex(byte[] data) {
        StringBuilder out = new StringBuilder(data.length * 2);
        for (byte b : data) {
            out.append(Character.forDigit((b >> 4) & 0xF, 16));
            out.append(Character.forDigit(b & 0xF, 16));
        }
        return out.toString();
    }

    // =====================================================================
    // 密钥材料解析(hex / base64 / text)
    // =====================================================================

    /**
     * 解析密钥/IV 这类密钥材料。
     *
     * <p>hex / base64 允许带空白(复制粘贴常带换行);text 不 trim —— 那是密钥本身,
     * 少一个空格就是另一把密钥,静默替用户改掉更糟。
     */
    public static byte[] decodeMaterial(String s, String format) {
        String fmt = format == null ? "" : format.trim().toLowerCase(Locale.ROOT);
        if (fmt.isEmpty() || "hex".equals(fmt)) {
            return fromHex(s);
        }
        if ("base64".equals(fmt)) {
            try {
                return java.util.Base64.getDecoder().decode(s == null ? "" : s.trim());
            } catch (IllegalArgumentException e) {
                throw ApiException.validation("按 base64 解析失败");
            }
        }
        if ("text".equals(fmt)) {
            return (s == null ? "" : s).getBytes(StandardCharsets.UTF_8);
        }
        throw ApiException.validation(String.format("不支持的密钥格式 %s", GoFmt.q(format)));
    }

    private static byte[] fromHex(String s) {
        String in = s == null ? "" : s.trim();
        if (in.length() % 2 != 0) {
            throw ApiException.validation("按十六进制解析失败:请检查是否有非 0-9a-f 的字符");
        }
        byte[] out = new byte[in.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(in.charAt(i * 2), 16);
            int lo = Character.digit(in.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                throw ApiException.validation("按十六进制解析失败:请检查是否有非 0-9a-f 的字符");
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    /** 规格清单的一份可写副本(前端渲染用)。 */
    public static List<CryptoToolSpec> specs() {
        return new ArrayList<>(SPECS);
    }
}
