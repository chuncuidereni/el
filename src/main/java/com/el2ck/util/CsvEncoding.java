package com.el2ck.util;

import java.io.*;
import com.el2ck.exception.ApiException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.*;
import java.util.Locale;

/** 有界探测并严格解码；绝不在已输出数据后切换编码或用替代字符吞掉错误字节。 */
public final class CsvEncoding {
    private static final Charset GBK = Charset.forName("GBK");
    private static final int PROBE_BYTES = 256 * 1024;
    private CsvEncoding() { }

    public static void normalizeCSV(java.nio.file.Path source, java.nio.file.Path target) throws java.io.IOException {
        Charset charset = StandardCharsets.UTF_8;
        try {
            transcode(source, null, charset);
        } catch (CharacterCodingException invalidUtf8) {
            charset = GBK;
        }
        try {
            transcode(source, target, charset);
        } catch (CharacterCodingException invalidEncoding) {
            throw ApiException.validation("CSV 编码无法识别(非 UTF-8 且非 GBK)");
        }
    }

    private static void transcode(java.nio.file.Path source, java.nio.file.Path target, Charset charset)
            throws java.io.IOException {
        CharsetDecoder decoder = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try (java.io.Reader reader = new java.io.InputStreamReader(java.nio.file.Files.newInputStream(source), decoder);
             java.io.Writer writer = target == null ? null : java.nio.file.Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
            char[] buffer = new char[8192];
            int n;
            while ((n = reader.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("编码转换已取消");
                if (writer != null) writer.write(buffer, 0, n);
            }
        }
    }

    /**
     * 规范化 CSV 字节：已是合法 UTF-8 则原样返回；否则用 GBK 解码后重写为 UTF-8。
     *
     * @param data 原始文件字节
     * @return 转换后的 UTF-8 字节
     * @throws ApiException 400:既非 UTF-8 也非可解码的 GBK(哨兵语义,对齐 Go ErrUnknownEncoding)
     */
    public static byte[] normalizeCSV(byte[] data) {
        // 空数据或已是合法 UTF-8 时无需转换，原样返回。
        // 注意：UTF-8 BOM(EF BB BF)本身也是合法 UTF-8 序列，会被原样保留，交由 DuckDB 读取端处理。
        if (data == null || data.length == 0 || isValidUtf8(data)) {
            return data;
        }
        try {
            // ⚠️ 用 REPORT 策略严格解码 GBK:非法/不可映射字节即抛,而不是像 new String(byte[],GBK)
            // 那样用 REPLACE 静默产出 U+FFFD 乱码。这样「既非 UTF-8 又非 GBK」能被识别出来,
            // 上传接口据此返回 400(输入问题)而非 500(服务端问题)—— 对齐 Go 的 ErrUnknownEncoding 哨兵。
            CharsetDecoder dec = GBK.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            CharBuffer decoded = dec.decode(ByteBuffer.wrap(data));
            // 解码成功:按 UTF-8 重新编码,完成中文编码归一化。
            return decoded.toString().getBytes(StandardCharsets.UTF_8);
        } catch (CharacterCodingException e) {
            // 既非 UTF-8 也非可解码 GBK:按输入问题(400)返回,不是服务端错误。
            throw ApiException.validation("CSV 编码无法识别(非 UTF-8 且非 GBK)");
        }
    }

    /** 严格校验字节是否为合法 UTF-8(遇到非法或不可映射序列即返回 false)。 */
    private static boolean isValidUtf8(byte[] data) {
        try {
            // 构造严格 REPORT 模式的 UTF-8 解码器：遇非法/不可映射序列即抛异常
            CharsetDecoder dec = Charset.forName("UTF-8").newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            // 解码整个字节缓冲，全程无异常即判定为合法 UTF-8
            dec.decode(ByteBuffer.wrap(data));
            return true;
        } catch (CharacterCodingException e) {
            // 存在非法序列，判定为非 UTF-8 编码
            return false;
        }
    }

    public static String normalize(String name) {
        if (name == null || name.trim().isEmpty() || "auto".equalsIgnoreCase(name.trim())) return "auto";
        String key = name.trim().toUpperCase(Locale.ROOT).replace('_', '-');
        if ("UTF8".equals(key)) key = "UTF-8";
        if (!java.util.Arrays.asList("UTF-8", "GB18030", "GBK", "UTF-16LE", "UTF-16BE").contains(key))
            throw new IllegalArgumentException("CSV 编码仅支持自动、UTF-8、GB18030、GBK、UTF-16LE、UTF-16BE");
        return key;
    }

    public static final class Decoded {
        public final Reader reader;
        public final String encoding;
        private Decoded(Reader reader, String encoding) { this.reader = reader; this.encoding = encoding; }
    }

    /** 接管输入流；失败时关闭。探测只读固定前缀，不下载第二份文件。 */
    public static Decoded open(InputStream source, String requested) throws IOException {
        BufferedInputStream input = new BufferedInputStream(source, 8192);
        try {
            String encoding = normalize(requested);
            input.mark(PROBE_BYTES + 1);
            byte[] bytes = new byte[PROBE_BYTES];
            int length = 0;
            boolean eof = false;
            while (length < bytes.length) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("CSV 编码探测已取消");
                int n = input.read(bytes, length, bytes.length - length);
                if (n < 0) { eof = true; break; }
                if (n > 0) length += n;
            }
            input.reset();
            String bom = null;
            int skip = 0;
            if (starts(bytes, length, 0, 0, 0xfe, 0xff) || starts(bytes, length, 0xff, 0xfe, 0, 0))
                throw new IOException("CSV 使用 UTF-32，请先转换为 UTF-8");
            if (starts(bytes, length, 0xef, 0xbb, 0xbf)) { bom = "UTF-8"; skip = 3; }
            else if (starts(bytes, length, 0xff, 0xfe)) { bom = "UTF-16LE"; skip = 2; }
            else if (starts(bytes, length, 0xfe, 0xff)) { bom = "UTF-16BE"; skip = 2; }
            if (starts(bytes, length, 0x50, 0x4b, 3, 4) || starts(bytes, length, 0xd0, 0xcf, 0x11, 0xe0))
                throw new IOException("文件内容为 Excel/压缩容器，不能按 CSV 文本读取，请使用相应文件格式");
            if (bom != null) {
                if (!"auto".equals(encoding) && !bom.equals(encoding))
                    throw new IOException("所选 CSV 编码 " + encoding + " 与文件 BOM " + bom + " 不一致");
                encoding = bom;
            } else if ("auto".equals(encoding)) {
                for (int i = 0; i < length; i++) if (bytes[i] == 0)
                    throw new IOException("CSV 含零字节，可能是无 BOM 的 UTF-16 文件，请手动选择 UTF-16LE 或 UTF-16BE");
                if (valid(bytes, length, "UTF-8", eof)) encoding = "UTF-8";
                else if (valid(bytes, length, "GB18030", eof)) encoding = "GB18030";
                else throw new IOException("无法识别 CSV 编码，请明确选择源文件编码；文件也可能已损坏或混合编码");
            }
            for (int i = 0; i < skip; i++) input.read();
            CharsetDecoder decoder = Charset.forName(encoding).newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
            return new Decoded(new InputStreamReader(input, decoder), encoding);
        } catch (IOException | RuntimeException e) {
            try { input.close(); } catch (IOException ignored) { }
            throw e;
        }
    }

    private static boolean valid(byte[] bytes, int length, String encoding, boolean eof) {
        CharsetDecoder decoder = Charset.forName(encoding).newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        // 未到 EOF 的探测边界可能正好切在多字节字符中间，不把这个正常前缀误判为错误编码。
        return !decoder.decode(ByteBuffer.wrap(bytes, 0, length), CharBuffer.allocate(length + 1), eof).isError();
    }

    private static boolean starts(byte[] bytes, int length, int... prefix) {
        if (length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if ((bytes[i] & 255) != prefix[i]) return false;
        return true;
    }
}
