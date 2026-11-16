/**
 * 【文件职责】兼容 Go 实现的 FF1 保型加密，负责密钥/tweak 解析、数字字母表校验和可互操作加解密。
 * <p>维护本文件时应同步检查调用方契约、失败恢复、并发边界和敏感数据处理，避免只修改单侧实现。
 */
package com.el2ck.workflow;

import com.el2ck.exception.ApiException;
import com.el2ck.util.GoFmt;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HashMap;
import java.util.Map;

/**
 * FF1 保型加密(Format-Preserving Encryption),移植自 Go {@code internal/sync/ff1.go}。
 *
 * <p>依据:NIST SP 800-38G 的 FF1(算法 7 加密 / 算法 8 解密)。
 * 底层 PRP 用 AES,输出与输入【长度相同、字符集相同】——
 * 例如 11 位手机号加密后仍是 11 位数字,可直接落库而不改列结构。
 *
 * <p>⚠️ 本类必须与 Go 侧【逐字节可互操作】:同一密钥 / tweak / 字母表下,
 * Go 加密的密文 Java 要能解出来,反之亦然。因此这里不做任何"优化"或"简化" ——
 * 包括大整数运算也照搬({@code ceil(v·log2(radix))} 用浮点在宽字母表上会丢精度)。
 * 校验方式见 {@code FF1Test} 里抄自 Go 的固定测试向量。
 */
public final class FF1 {

    /** 工具类私有构造器:禁止实例化。 */
    private FF1() {
    }

    /** 数字字母表(手机号最常用)。 */
    public static final String DEFAULT_DIGITS_ALPHABET = "0123456789";

    /** FF1 算法参数(对应 Go {@code sync.FF1Params})。 */
    public static final class Params {
        /** AES 密钥(16 / 24 / 32 字节)。 */
        private final byte[] key;
        /** 辅助参数(可为空)。 */
        private final byte[] tweak;
        /** 字母表,如 "0123456789";radix = 字符数(不是字节数)。 */
        private final String alphabet;

        /** 构造器:三个参数在 {@link #encrypt} 之前会各自校验。 */
        public Params(byte[] key, byte[] tweak, String alphabet) {
            this.key = key;
            this.tweak = tweak;
            this.alphabet = alphabet;
        }
    }

    /**
     * 解析密钥:接受十六进制(32/48/64 位)或原始字节串。
     *
     * <p>长度判定按【字节数】而不是字符数 —— 非 ASCII 密钥的字符数与字节数不同,
     * 用字符数会让"看起来 16 位"的密钥实际超过 AES 接受的 16/24/32 字节。
     */
    public static byte[] parseKey(String v) {
        String s = v == null ? "" : v.trim();
        if (s.isEmpty()) {
            throw ApiException.validation("密钥不能为空");
        }
        int bytes = s.getBytes(StandardCharsets.UTF_8).length;
        // 32 / 48 / 64 位十六进制 → 16 / 24 / 32 字节
        if (bytes == 32 || bytes == 48 || bytes == 64) {
            byte[] hex = tryHex(s);
            if (hex != null) {
                return hex;
            }
        }
        if (bytes == 16 || bytes == 24 || bytes == 32) {
            return s.getBytes(StandardCharsets.UTF_8);
        }
        throw ApiException.validation(String.format(
                "密钥需为 16/24/32 字节,或 32/48/64 位十六进制,实际 %d 字符", bytes));
    }

    /**
     * 解析 tweak:偶数长度(且不短于 8)按十六进制解,否则按原始字节。
     *
     * <p>长度门槛 8 是刻意的:短 tweak(如 "ab")按十六进制解会得到 1 字节,
     * 与用户"就是这两个字符"的预期不符,故短串一律按原始字节处理。
     */
    public static byte[] parseTweak(String v) {
        String s = v == null ? "" : v.trim();
        if (s.isEmpty()) {
            return null; // tweak 可为空
        }
        if (s.length() % 2 == 0 && s.length() >= 8) {
            byte[] hex = tryHex(s);
            if (hex != null) {
                return hex;
            }
        }
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** 十六进制解码;非法输入返回 null(与 Go 的 {@code hex.DecodeString} 报错等价,只是不抛)。 */
    private static byte[] tryHex(String s) {
        int n = s.length();
        if (n % 2 != 0) {
            return null;
        }
        byte[] out = new byte[n / 2];
        for (int i = 0; i < n; i += 2) {
            int hi = Character.digit(s.charAt(i), 16);
            int lo = Character.digit(s.charAt(i + 1), 16);
            if (hi < 0 || lo < 0) {
                return null;
            }
            out[i / 2] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    /** 保型加密。输入输出的字符都必须属于 alphabet。 */
    public static String encrypt(Params p, String plain) {
        return run(p, plain, true);
    }

    /** 保型解密。 */
    public static String decrypt(Params p, String cipherText) {
        return run(p, cipherText, false);
    }

    /**
     * 加密 / 解密的公共实现。
     *
     * <p>⚠️ radix 必须是【字符数】而不是字节数。Go 侧曾用字节数,遇到多字节字母表
     * (全角数字、中文等)时 radix 会大于实际字符数,取下标时直接 panic(实测 HTTP 500)。
     * 这里全程按码点处理,同样是这个原因。
     */
    private static String run(Params p, String in, boolean enc) {
        int[] alphabet = toCodePoints(p.alphabet);
        int radix = alphabet.length;
        if (radix < 2 || radix > 65536) {
            throw ApiException.validation(String.format("字母表长度(radix)需在 2..65536,实际 %d", radix));
        }
        Cipher block = aesBlock(p.key);
        int[] x = indicesOf(alphabet, in);
        int n = x.length;
        if (n < 2) {
            throw ApiException.validation(String.format("FF1 要求长度至少 2,实际 %d", n));
        }
        int t = p.tweak == null ? 0 : p.tweak.length;

        int u = n / 2;
        int v = n - u;

        // b = ceil( ceil(v * log2(radix)) / 8 )
        // 用大整数精确计算,避免浮点误差:
        //   ceil(v*log2(radix)) = bitLen(radix^v - 1)
        BigInteger radixBI = BigInteger.valueOf(radix);
        int bits = radixBI.pow(v).subtract(BigInteger.ONE).bitLength();
        int b = (bits + 7) / 8;
        int d = 4 * ((b + 3) / 4) + 4;

        // P = [1]1 || [2]1 || [1]1 || [radix]3 || [10]1 || [u mod 256]1 || [n]4 || [t]4
        byte[] prefix = new byte[]{
                1, 2, 1,
                (byte) (radix >> 16), (byte) (radix >> 8), (byte) radix,
                10,
                (byte) (u % 256),
                (byte) (n >> 24), (byte) (n >> 16), (byte) (n >> 8), (byte) n,
                (byte) (t >> 24), (byte) (t >> 16), (byte) (t >> 8), (byte) t};

        int[] a = new int[u];
        System.arraycopy(x, 0, a, 0, u);
        int[] bb = new int[v];
        System.arraycopy(x, u, bb, 0, v);

        if (enc) {
            // FF1.Encrypt(算法 7):i 从 0 到 9
            for (int i = 0; i < 10; i++) {
                // Q = T || [0](-t-b-1 mod 16) || [i]1 || [NUM_radix(B)]b
                BigInteger y = new BigInteger(1, ff1S(block, prf(block, concat(prefix, buildQ(p.tweak, t, b, i, bb, radix))), d));
                int m = (i % 2 == 1) ? v : u;
                // c = (NUM_radix(A) + y) mod radix^m
                BigInteger c = numRadix(a, radix).add(y).mod(radixBI.pow(m));
                a = bb;
                bb = strRadix(c, m, radix);
            }
            return toStr(alphabet, concat(a, bb));
        }

        // FF1.Decrypt(算法 8):i 从 9 到 0,且 Q 用 A、c 用 B —— 与加密互为镜像
        for (int i = 9; i >= 0; i--) {
            BigInteger y = new BigInteger(1, ff1S(block, prf(block, concat(prefix, buildQ(p.tweak, t, b, i, a, radix))), d));
            int m = (i % 2 == 1) ? v : u;
            // c = (NUM_radix(B) - y) mod radix^m
            BigInteger c = numRadix(bb, radix).subtract(y).mod(radixBI.pow(m));
            int[] previousA = a;
            a = strRadix(c, m, radix);
            bb = previousA;
        }
        return toStr(alphabet, concat(a, bb));
    }

    /** 构造 Q = T || [0](-t-b-1 mod 16) || [i]1 || [NUM_radix(digits)]b。 */
    private static byte[] buildQ(byte[] tweak, int t, int b, int i, int[] digits, int radix) {
        int padLen = ((-(t + b + 1)) % 16 + 16) % 16;
        byte[] raw = bigIntBytes(numRadix(digits, radix));
        if (raw.length > b) {
            // 理论上不会发生(radix^len 一定装得进 b 字节),真发生了说明 b 算错了;
            // 截断会静默算出错误密文,故直接抛错。
            throw ApiException.validation(String.format("FF1 内部错误:数值 %d 字节超过 b=%d", raw.length, b));
        }
        byte[] q = new byte[t + padLen + 1 + b];
        if (t > 0) {
            System.arraycopy(tweak, 0, q, 0, t);
        }
        q[t + padLen] = (byte) i;
        // 左补零到 b 字节,数值放在末尾(大端)
        System.arraycopy(raw, 0, q, q.length - raw.length, raw.length);
        return q;
    }

    /**
     * 大整数的无符号大端字节表示,等价于 Go 的 {@code big.Int.Bytes()}。
     *
     * <p>⚠️ 不能用 {@code BigInteger.toByteArray()} 直接当结果:它会为最高位是 1 的正数
     * 补一个 0 字节(符号位),也会把 0 表示成单字节 {@code [0]},而 Go 的 Bytes() 对 0 返回空串。
     */
    private static byte[] bigIntBytes(BigInteger n) {
        if (n.signum() == 0) {
            return new byte[0];
        }
        byte[] raw = n.toByteArray();
        if (raw.length > 1 && raw[0] == 0) {
            byte[] trimmed = new byte[raw.length - 1];
            System.arraycopy(raw, 1, trimmed, 0, trimmed.length);
            return trimmed;
        }
        return raw;
    }

    /**
     * FF1 的伪随机函数:CBC-MAC。
     * 输入必须是 16 的整数倍(由 P 与 Q 的构造保证)。
     */
    private static byte[] prf(Cipher block, byte[] data) {
        byte[] out = new byte[16];
        System.arraycopy(data, 0, out, 0, 16);
        encryptBlock(block, out);
        for (int i = 16; i < data.length; i += 16) {
            for (int j = 0; j < 16; j++) {
                out[j] ^= data[i + j];
            }
            encryptBlock(block, out);
        }
        return out;
    }

    /** S = 前 d 字节 of R || CIPH_K(R ⊕ [1]16) || CIPH_K(R ⊕ [2]16) || …。 */
    private static byte[] ff1S(Cipher block, byte[] r, int d) {
        // d + 16 足够:每轮追加 16 字节,循环在 len >= d 时停止,故最多写到 [d/16] 向上取整 * 16 字节
        byte[] out = new byte[d + 16];
        System.arraycopy(r, 0, out, 0, r.length);
        int len = r.length;
        byte[] buf = new byte[16];
        for (int j = 1; len < d; j++) {
            System.arraycopy(r, 0, buf, 0, 16);
            buf[15] ^= (byte) j; // ⊕ [j]16(大端,低字节在最后)
            encryptBlock(block, buf);
            System.arraycopy(buf, 0, out, len, 16);
            len += 16;
        }
        byte[] result = new byte[d];
        System.arraycopy(out, 0, result, 0, d);
        return result;
    }

    /** NUM_radix(X):按 radix 把数字切片解释为大整数。 */
    private static BigInteger numRadix(int[] x, int radix) {
        BigInteger r = BigInteger.valueOf(radix);
        BigInteger n = BigInteger.ZERO;
        for (int digit : x) {
            n = n.multiply(r).add(BigInteger.valueOf(digit));
        }
        return n;
    }

    /** STR_radix^m(x):把大整数转成固定 m 位的数字切片。 */
    private static int[] strRadix(BigInteger n, int m, int radix) {
        int[] out = new int[m];
        BigInteger r = BigInteger.valueOf(radix);
        BigInteger tmp = n;
        for (int i = m - 1; i >= 0; i--) {
            BigInteger[] qr = tmp.divideAndRemainder(r);
            tmp = qr[0];
            out[i] = qr[1].intValue();
        }
        return out;
    }

    /**
     * 把输入按字母表转成数字切片。
     *
     * <p>⚠️ 下标必须是【码点序号 0..radix-1】,不能用 charAt 的 char 值 ——
     * 补充平面字符(如 emoji)在 Java 里占两个 char,按 char 处理会把一个字拆成两个非法字。
     */
    private static int[] indicesOf(int[] alphabet, String x) {
        Map<Integer, Integer> idx = new HashMap<Integer, Integer>();
        for (int i = 0; i < alphabet.length; i++) {
            idx.put(alphabet[i], i);
        }
        int[] chars = toCodePoints(x);
        int[] out = new int[chars.length];
        for (int i = 0; i < chars.length; i++) {
            Integer at = idx.get(chars[i]);
            if (at == null) {
                throw ApiException.validation(String.format(
                        "字符 %s 不在字母表中", GoFmt.q(new String(Character.toChars(chars[i])))));
            }
            out[i] = at;
        }
        return out;
    }

    /** 把数字切片按字母表还原成字符串。 */
    private static String toStr(int[] alphabet, int[] x) {
        StringBuilder out = new StringBuilder(x.length);
        for (int digit : x) {
            out.appendCodePoint(alphabet[digit]);
        }
        return out.toString();
    }

    /** 按【码点】切分(不是按 char,否则补充平面字符会被拆成两半)。 */
    private static int[] toCodePoints(String s) {
        int count = s.codePointCount(0, s.length());
        int[] out = new int[count];
        int at = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            out[at++] = cp;
            i += Character.charCount(cp);
        }
        return out;
    }

    /** 拼接数字切片。 */
    private static int[] concat(int[] a, int[] b) {
        int[] out = new int[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    /** 拼接字节数组。 */
    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    /** 建一个 AES/ECB/NoPadding 的 Cipher,只用来加密单个 16 字节块(FF1 的 PRP)。 */
    private static Cipher aesBlock(byte[] key) {
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
            return cipher;
        } catch (GeneralSecurityException e) {
            // 密钥长度非法等:Java 侧是 GeneralSecurityException,Go 侧是 aes.NewCipher 的错误
            throw ApiException.validation(String.format("AES 初始化失败: %s", e.getMessage()));
        }
    }

    /** 就地加密一个 16 字节块。 */
    private static void encryptBlock(Cipher cipher, byte[] block) {
        try {
            byte[] out = cipher.doFinal(block, 0, 16);
            System.arraycopy(out, 0, block, 0, 16);
        } catch (GeneralSecurityException e) {
            throw ApiException.validation(String.format("AES 加密失败: %s", e.getMessage()));
        }
    }
}
