/**
 * 【文件职责】本文件定义HTTP 公共基础设施模块的HTTP 响应模型 CsvResponse。描述接口成功与失败信封，确保控制器返回结构稳定。
 */

package com.el2ck.controller.common;

import com.el2ck.util.Csv;

import javax.servlet.http.HttpServletResponse;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

/**
 * 以流式方式把 CSV 写进 HTTP 响应。对应 Go {@code internal/api/response.go} 的 {@code streamCsv}。
 *
 * <p>为什么必须流式:导出结果可以到 200 万行,整份 CSV 先攒进内存再序列化,等于让服务端
 * 为每次导出准备一份与结果同大的缓冲;而且前端拿到的响应体就是文件本身
 * ({@code postBlob} + {@code downloadBlob}),不是 JSON 信封 —— 导出的响应体必须是裸 CSV。
 *
 * <p>⚠️ 一旦写出第一个字节,状态码与响应头就定死了:所以调用方必须先把能失败的校验跑完
 * (各导出服务里的 Prepare 阶段),再进这里。写出阶段的错误只能中断连接,没法再改成 JSON 错误。
 */
public final class CsvResponse {

    /** 工具类私有构造器:禁止实例化。 */
    private CsvResponse() {
    }

    /** 写出体:往 Writer 里逐行写 CSV,返回写出的数据行数(不含表头)。 */
    public interface Body {
        /** 把 CSV 写入 w。 */
        long write(Writer w) throws IOException;
    }

    /**
     * 写出 CSV 响应。
     *
     * @param resp     响应(调用时状态码与响应头尚未提交)
     * @param filename 非空时带 Content-Disposition 触发浏览器下载;空则不设(两个导出端点都用空)
     * @param body     实际写 CSV 的逻辑
     * @throws IOException 写出失败(连接已断开等),由容器处理,此时响应已无法改成 JSON
     */
    public static void write(HttpServletResponse resp, String filename, Body body) throws IOException {
        // 响应头:CSV + UTF-8;nosniff 防止浏览器把内容当别的类型解析。
        resp.setContentType("text/csv; charset=utf-8");
        if (filename != null && !filename.isEmpty()) {
            resp.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        }
        resp.setHeader("X-Content-Type-Options", "nosniff");
        // 导出结果与时间/权限相关,不该被任何中间层缓存。
        resp.setHeader("Cache-Control", "no-store");
        resp.setStatus(HttpServletResponse.SC_OK);
        // BOM 直接写字节(与 Go 一致):不经字符编码,不受 Writer 的编码设置影响。
        resp.getOutputStream().write(Csv.bomBytes());
        // 64KB 缓冲,与 Go 的 bufio.NewWriterSize(…, 64*1024) 一致 —— 逐行 flush 会让大导出变成无数次小包。
        try (Writer w = new BufferedWriter(
                new OutputStreamWriter(resp.getOutputStream(), StandardCharsets.UTF_8), 64 * 1024)) {
            body.write(w);
        }
    }
}
