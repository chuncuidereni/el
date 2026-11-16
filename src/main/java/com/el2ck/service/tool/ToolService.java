/**
 * 【文件职责】本文件定义密码与哈希工具模块的业务服务 ToolService。承载该模块的核心业务规则，协调仓储、连接器或外部服务，并统一转换业务异常和操作状态。
 */

package com.el2ck.service.tool;

import com.el2ck.entity.tool.CryptoToolItem;
import com.el2ck.entity.tool.CryptoToolKeyPair;
import com.el2ck.entity.tool.CryptoToolRequest;
import com.el2ck.entity.tool.CryptoToolResult;
import com.el2ck.entity.tool.CryptoToolSpec;
import com.el2ck.entity.tool.HashToolItem;
import com.el2ck.entity.tool.HashToolRequest;
import com.el2ck.entity.tool.HashToolResult;
import com.el2ck.exception.ApiException;
import com.el2ck.tool.CryptoTool;
import com.el2ck.tool.HashTool;
import com.el2ck.util.GoFmt;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * 工具页(哈希 / 加解密)的批量编排,对应 Go {@code internal/api/hashtool_api.go}
 * 与 {@code crypto_api.go} 里 HTTP 层做的那部分。
 *
 * <p>为什么把循环放在这里而不是控制器:控制器只该做「取参数 → 调服务 → 装信封」。
 * 算法本身在 {@link HashTool} / {@link CryptoTool},这里只管:
 * 入参校验、逐行调用、以及【逐行隔离错误】。
 *
 * <h3>两个工具的错误模型是【刻意不同】的</h3>
 * <pre>
 * 哈希   ── 一行算不出来(算法就不支持)整批都没意义 → 直接 400;
 * 加解密 ── 一批密文里有两行密钥不对是常态,不能让它们把另外 998 行也毁掉
 *           → 整批 200,失败的行带 error,成功的行带 result,靠 okCount/failCount 汇总。
 * </pre>
 */
@Service
public class ToolService {

    /**
     * 批量计算摘要。
     *
     * <p>入参校验顺序与 Go 一致:先看有没有值、再看条数上限、再解析算法 ——
     * 算法不认就在这里失败,不会算到一半才炸。
     */
    public HashToolResult digest(HashToolRequest in) {
        List<String> values = valuesOf(in == null ? null : in.getValues());
        String algo = HashTool.normalize(in == null ? null : in.getAlgo());
        if (algo.isEmpty()) {
            algo = "md5"; // 默认算法(与前端下拉的默认值一致)
        }
        // 默认【大写】:平台口径,见 HashTool 的类注释
        boolean upper = in == null || in.getUpper() == null || in.getUpper();

        // 算法解析一次、循环里只做哈希 —— 每条都重解析一遍是纯浪费
        Function<String, String> hasher = HashTool.newHasher(algo, upper);
        List<HashToolItem> items = new ArrayList<>(values.size());
        for (String v : values) {
            items.add(new HashToolItem(v, hasher.apply(v)));
        }

        HashToolResult out = new HashToolResult();
        out.setItems(items);
        out.setAlgo(algo);
        out.setUpper(upper);
        out.setCount(items.size());
        return out;
    }

    /** 按算法规格生成随机密钥与 IV。长度由规格决定,不接受调用方指定。 */
    public CryptoToolKeyPair keyGen(String algo) {
        return CryptoTool.generateKey(algo == null ? "" : algo.trim());
    }

    /** 算法清单(前端拿它渲染下拉与提示)。 */
    public List<CryptoToolSpec> specs() {
        return CryptoTool.specs();
    }

    /**
     * 批量加解密。逐行隔离错误:一行失败不影响别的行。
     */
    public CryptoToolResult run(CryptoToolRequest in) {
        List<String> values = valuesOf(in == null ? null : in.getValues());

        boolean encrypt = directionOf(in == null ? null : in.getDirection());

        String keyFormat = in == null ? null : in.getKeyFormat();
        byte[] key = decodeWrapped(in == null ? null : in.getKey(), keyFormat, "密钥:");
        byte[] iv = new byte[0];
        String rawIv = in == null ? null : in.getIv();
        if (rawIv != null && !rawIv.trim().isEmpty()) {
            iv = decodeWrapped(rawIv, keyFormat, "IV:");
        }

        // 算法与密钥/IV 长度先整体校验一次,别等逐行处理时才发现每一行都报同一个错
        String algoId = in == null || in.getAlgo() == null ? "" : in.getAlgo().trim();
        CryptoToolSpec spec = CryptoTool.requireSpec(algoId);
        CryptoTool.checkKeyIv(spec, key, iv);

        String outFmt = formatOf(in == null ? null : in.getOutFormat(), "base64");
        String inFmt = formatOf(in == null ? null : in.getInFormat(), "base64");

        List<CryptoToolItem> items = new ArrayList<>(values.size());
        int failed = 0;
        for (String v : values) {
            CryptoToolItem it = new CryptoToolItem();
            it.setValue(v);
            try {
                if (encrypt) {
                    byte[] ct = CryptoTool.encrypt(spec.getId(), key, iv, bytes(v));
                    it.setResult("hex".equals(outFmt)
                            ? toHex(ct) : Base64.getEncoder().encodeToString(ct));
                } else {
                    byte[] raw = decodeWrapped(v, inFmt, "密文");
                    it.setResult(new String(CryptoTool.decrypt(spec.getId(), key, iv, raw),
                            java.nio.charset.StandardCharsets.UTF_8));
                }
            } catch (ApiException e) {
                // 一行失败只记在这一行上(Go 的 cryptoItem 语义)
                it.setError(e.getMessage());
                it.setResult("");
                failed++;
            }
            items.add(it);
        }

        CryptoToolResult out = new CryptoToolResult();
        out.setItems(items);
        out.setAlgo(spec.getId());
        out.setDirection(encrypt ? "encrypt" : "decrypt");
        out.setOutFormat(outFmt);
        out.setInFormat(inFmt);
        out.setCount(items.size());
        out.setOkCount(items.size() - failed);
        out.setFailCount(failed);
        return out;
    }

    // =====================================================================
    // 入参校验
    // =====================================================================

    /** 两个工具共用的「有没有值 / 条数上限」两道闸。 */
    private static List<String> valuesOf(List<String> values) {
        List<String> out = values == null ? new ArrayList<>() : values;
        if (out.isEmpty()) {
            throw ApiException.validation("请先输入要计算的数据(每行一个)");
        }
        if (out.size() > HashTool.MAX_VALUES) {
            throw ApiException.validation(
                    String.format("单次最多 %d 条,当前 %d 条", HashTool.MAX_VALUES, out.size()));
        }
        return out;
    }

    /** 方向:encrypt(默认)/ decrypt,其余报错。 */
    private static boolean directionOf(String direction) {
        String d = direction == null ? "" : direction.trim().toLowerCase(Locale.ROOT);
        if (d.isEmpty() || "encrypt".equals(d)) {
            return true;
        }
        if ("decrypt".equals(d)) {
            return false;
        }
        throw ApiException.validation(String.format("不支持的方向 %s(可选:encrypt / decrypt)",
                GoFmt.q(direction)));
    }

    /**
     * 规范化可选格式参数：去空白、转小写，空值回退默认格式。
     *
     * @param format 原始格式
     * @param dflt 默认格式
     * @return 规范化后的格式
     */
    private static String formatOf(String format, String dflt) {
        String f = format == null ? "" : format.trim().toLowerCase(Locale.ROOT);
        return f.isEmpty() ? dflt : f;
    }

    /** 解析密钥材料,失败时带上「密钥:」「IV:」这类前缀,才知道是哪一项错了。 */
    private static byte[] decodeWrapped(String raw, String format, String prefix) {
        try {
            return CryptoTool.decodeMaterial(raw, format);
        } catch (ApiException e) {
            throw ApiException.validation(prefix + e.getMessage());
        }
    }

    /**
     * 按 UTF-8 将文本编码为字节，保证摘要和加密工具在不同平台上得到一致结果。
     *
     * @param s 待编码文本
     * @return UTF-8 字节数组
     */
    private static byte[] bytes(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** 密文的十六进制输出:【小写】—— 与 Go 的 hex.EncodeToString、openssl 一致。 */
    private static String toHex(byte[] data) {
        StringBuilder out = new StringBuilder(data.length * 2);
        for (byte b : data) {
            out.append(Character.forDigit((b >> 4) & 0xF, 16));
            out.append(Character.forDigit(b & 0xF, 16));
        }
        return out.toString();
    }
}
