/**
 * 【文件职责】MD5/SHA-256/SM3 十六进制摘要工具，固定 UTF-8、无盐、无隐式 trim 的跨端计算口径。
 * <p>维护本文件时应同步检查调用方契约、失败恢复、并发边界和敏感数据处理，避免只修改单侧实现。
 */
package com.el2ck.tool;

import com.el2ck.exception.ApiException;
import com.el2ck.util.GoFmt;
import org.bouncycastle.crypto.digests.SM3Digest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * 把原始数据算成 MD5 / SHA256 / SM3 十六进制摘要,对应 Go {@code internal/hashtool}。
 *
 * <p>编码约定:无盐、不加换行、按 UTF-8 字节直接算(见 {@link #digest})。
 *
 * <p>⚠️ 大小写为什么默认【大写】:ClickHouse 的 {@code hex()} 返回大写(平台的 hex_md5 算子
 * 就是 {@code hex(MD5(x))}),平台自己的 SM3 程序算子({@code ProgOps} 的 sm3Hex)也是大写。
 * 本工具产物大多是拿去和库里的列比对的 —— 大小写不一致会让 join / 去重【静默】对不上
 * (看着都像「没数据」)。所以默认口径是大写,由调用方传 {@code upper=false} 才给小写。
 */
public final class HashTool {

    /** 支持的算法。顺序即界面上的呈现顺序。 */
    public static final List<String> ALGOS =
            Collections.unmodifiableList(Arrays.asList("md5", "sha256", "sm3"));

    /**
     * 单次上限:哈希是纯 CPU 计算,给得宽一些;但也不能无限,否则一次请求就能把内存占住。
     */
    public static final int MAX_VALUES = 1_000_000;

    /**
     * 单次请求体上限(HTTP 层先卡,别等解析完才看条数)。
     *
     * <p>100 万条(MaxValues)× 约 15 字节 ≈ 15MB,这里给一倍余量。
     */
    public static final int MAX_BODY_BYTES = 32 << 20;

    private HashTool() {
    }

    /**
     * 计算一个值的十六进制摘要。
     *
     * <p>value 按【字节原样】计算 —— 不 trim、不加换行。去掉行尾空白是调用方(前端)的事,
     * 这里保持「给什么算什么」,接口语义才不会有歧义(同一个值在两边算出来必须一样)。
     */
    public static String digest(String algo, String value, boolean upper) {
        return newHasher(algo, upper).apply(value);
    }

    /**
     * 解析一次算法,返回可逐值复用的哈希函数。
     *
     * <p>为什么需要它:批量接口一次几十万条,每条目都重做一遍算法解析是纯浪费。
     * 调用方先解析一次,循环里只调返回的函数(见 {@link com.el2ck.service.tool.ToolService})。
     *
     * <p>⚠️ 它与 {@link #digest} 必须逐值完全一致 —— 否则批量接口会和单值接口算出不同结果,
     * 而这是最难发现的那类缺陷。
     */
    public static Function<String, String> newHasher(String algo, boolean upper) {
        String key = normalize(algo);
        if ("md5".equals(key)) {
            return hasher("MD5", upper);
        }
        if ("sha256".equals(key)) {
            return hasher("SHA-256", upper);
        }
        if ("sm3".equals(key)) {
            return value -> hexUpperOrLower(sm3(value), upper);
        }
        // 不支持的算法要报错(不做猜测 —— 不自动识别算法种类)
        throw ApiException.validation(String.format("不支持的算法 %s(可选:%s)",
                GoFmt.q(algo), String.join(" / ", ALGOS)));
    }

    /** 大小写与两端空白要容忍(界面/接口传值串味很常见)。 */
    public static String normalize(String algo) {
        return algo == null ? "" : algo.trim().toLowerCase(Locale.ROOT);
    }

    private static Function<String, String> hasher(String jdkName, boolean upper) {
        return value -> hexUpperOrLower(MessageDigestHolder.digest(jdkName, value), upper);
    }

    private static byte[] sm3(String value) {
        SM3Digest digest = new SM3Digest();
        byte[] data = value.getBytes(StandardCharsets.UTF_8);
        digest.update(data, 0, data.length);
        byte[] out = new byte[digest.getDigestSize()];
        digest.doFinal(out, 0);
        return out;
    }

    private static String hexUpperOrLower(byte[] data, boolean upper) {
        String hex = toHex(data);
        return upper ? hex.toUpperCase(Locale.ROOT) : hex;
    }

    /** 字节数组 → 小写十六进制。 */
    private static String toHex(byte[] data) {
        StringBuilder out = new StringBuilder(data.length * 2);
        for (byte b : data) {
            out.append(Character.forDigit((b >> 4) & 0xF, 16));
            out.append(Character.forDigit(b & 0xF, 16));
        }
        return out.toString();
    }

    /**
     * MessageDigest 不是线程安全的,而这里要按值复用「已解析的算法」。
     *
     * <p>用 ThreadLocal 而不是每次新建:一次批量几十万条,每条目 getInstance 一次
     * 也要走一遍算法查找与 JCA 提供者遍历。
     */
    private static final class MessageDigestHolder {
        private static final ThreadLocal<MessageDigest> MD5 = ThreadLocal.withInitial(() -> instance("MD5"));
        private static final ThreadLocal<MessageDigest> SHA256 = ThreadLocal.withInitial(() -> instance("SHA-256"));

        private static MessageDigest instance(String name) {
            try {
                return MessageDigest.getInstance(name);
            } catch (NoSuchAlgorithmException e) {
                // JDK 必须提供这两个算法;真缺了也不该让用户看见一段堆栈
                throw ApiException.validation("当前运行环境缺少 " + name + " 实现");
            }
        }

        private static byte[] digest(String name, String value) {
            ThreadLocal<MessageDigest> holder = "MD5".equals(name) ? MD5 : SHA256;
            MessageDigest md = holder.get();
            md.reset();
            md.update(value.getBytes(StandardCharsets.UTF_8));
            return md.digest();
        }
    }
}
