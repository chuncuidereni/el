package com.el2ck.config;

/**
 * 【文件职责】限制工具类接口的请求体大小，避免超大上传或请求占用过多内存。
 */

import com.el2ck.tool.HashTool;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ReadListener;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 工具接口的请求体上限,对应 Go 的 {@code http.MaxBytesReader}。
 *
 * <p>⚠️ 为什么必须在【解析之前】卡:写成「先绑定再检查 len(values)」的话,
 * 整个 body 已经进了内存才被拒绝 —— 传个几百 MB 的 JSON 照样能把内存吃满。
 * 32MB 的依据见 {@link HashTool#MAX_BODY_BYTES}(100 万条 ≈ 15MB,留一倍余量)。
 *
 * <p>只作用于 {@code /api/tools/} 下的接口:那里是唯一「用户可以把剪贴板整段贴进来」的入口,
 * 其余接口的入参形状都是固定的。
 *
 * <p>两种请求都要兜住:带 {@code Content-Length} 的(axios/fetch 发 JSON 串的常态)
 * 直接看长度就拒;不带长度的(chunked)靠包装输入流,读超了在读取过程中就断,
 * 不会等到全部读进内存。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ToolBodyLimitFilter implements Filter {

    /** 只卡工具接口。 */
    private static final String TOOLS_PREFIX = "/api/tools/";

    /**
     * 限流入口:非 {@code /api/tools/} 直接放行;带 {@code Content-Length} 的按长度即拒,
     * 不带宽度的靠包装输入流({@link LimitedBodyRequest})在读取过程中截断 —— 两种路径都归到同一个 413。
     */
    @Override
    public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) resp;
        if (!request.getRequestURI().startsWith(TOOLS_PREFIX)) {
            chain.doFilter(req, resp);
            return;
        }
        if (request.getContentLengthLong() > HashTool.MAX_BODY_BYTES) {
            tooLarge(response);
            return;
        }
        try {
            chain.doFilter(new LimitedBodyRequest(request), resp);
        } catch (Exception e) {
            // 不带宽度的请求在读取过程中撞到上限:这里把它翻译成与上面同一个 413
            if (hasTooLarge(e) && !response.isCommitted()) {
                tooLarge(response);
                return;
            }
            throw e;
        }
    }

    /**
     * 413 的文案与 Go 逐字一致;上限按 MB 展示,便于用户判断该分成几批。
     *
     * <p>信封手工拼接,与 {@link BasicAuthFilter#writeJson} 同一做法:过滤器在
     * DispatcherServlet 之前,拿不到 controller 里的统一信封,也不该为它反向依赖那一层。
     */
    private static void tooLarge(HttpServletResponse response) throws IOException {
        String message = String.format("请求体过大(上限 %dMB),请分批提交", HashTool.MAX_BODY_BYTES >> 20);
        response.setStatus(413);
        response.setContentType("application/json;charset=UTF-8");
        byte[] body = ("{\"code\":413,\"message\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8);
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }

    private static boolean hasTooLarge(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof BodyTooLargeException) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    /** 上限标记:只在本类内部流转,被 Jackson/Spring 包在别的异常里也认得出来。 */
    static final class BodyTooLargeException extends IOException {
        BodyTooLargeException() {
            super("request body exceeds " + HashTool.MAX_BODY_BYTES + " bytes");
        }
    }

    /** 把输入流换成「数着读」的那个;其余行为原样透传。 */
    private static final class LimitedBodyRequest extends HttpServletRequestWrapper {
        LimitedBodyRequest(HttpServletRequest request) {
            super(request);
        }

/** 返回带累计字节上限检查的请求输入流。 */
        @Override
        public ServletInputStream getInputStream() throws IOException {
            return new LimitedServletInputStream(super.getInputStream());
        }
    }

    /** 边读边计数的输入流:累计字节超过上限即抛 {@link BodyTooLargeException},其余方法原样透传。 */
    private static final class LimitedServletInputStream extends ServletInputStream {
        private final ServletInputStream delegate;
        private long read;

        LimitedServletInputStream(ServletInputStream delegate) {
            this.delegate = delegate;
        }

/** 读取一个字节并累计长度，超过工具接口上限时抛出 {@link BodyTooLargeException}。 */
        @Override
        public int read() throws IOException {
            int b = delegate.read();
            if (b >= 0) {
                read++;
                check();
            }
            return b;
        }

/** 批量读取字节并累计长度，读取量超过上限时抛出 {@link BodyTooLargeException}。 */
        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            // 只读「还允许的字节数」——不先读进来再判断,那样上限就白设了
            long left = (long) HashTool.MAX_BODY_BYTES - read;
            int n = delegate.read(b, off, (int) Math.min(len, Math.max(left, 0) + 1));
            if (n > 0) {
                read += n;
                check();
            }
            return n;
        }

        private void check() throws IOException {
            if (read > HashTool.MAX_BODY_BYTES) {
                throw new BodyTooLargeException();
            }
        }

/** 透传底层输入流是否已经读完。 */
        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

/** 透传底层输入流当前是否可读。 */
        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

/** 把异步读取监听器原样注册到底层输入流。 */
        @Override
        public void setReadListener(ReadListener readListener) {
            delegate.setReadListener(readListener);
        }
    }
}
