package com.el2ck.ckpool;

/**
 * 【文件职责】为需要 TLS 的 ClickHouse native 连接建立本地隧道，管理转发线程和底层套接字生命周期。
 */

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * 本地 TLS 桥:把 ClickHouse 原生(明文 TCP)协议封装进 TLS,供本机 JDBC 客户端透明连接。
 *
 * 线程/语义:open() 在回环地址随机端口建 ServerSocket(backlog=1),并启动守护线程
 * accept 恰好一个本地 JDBC 客户端;接入后用默认 SSLContext 与远程 9000 建 TLS 连接并
 * 完成握手,再起上行/下行两个守护线程按 16KB 缓冲双向"原样搬运"字节——纯字节中继、
 * 不解析 NATIVE 帧,故无粘包/拆包问题。任一端读到 EOF(对端断开)即结束对应转发线程并
 * 关闭两端 socket 收尾。close() 以 CAS 保证只执行一次;全程守护线程不阻止 JVM 退出。
 */
final class NativeTlsTunnel implements Closeable {
    /** 本地监听 socket,监听回环地址上的一个随机端口,等待 JDBC 客户端接入。 */
    private final ServerSocket listener;
    /** 要转发的远程 ClickHouse 主机(原始 host)。 */
    private final String remoteHost;
    /** 要转发的远程 ClickHouse 端口(默认 9000)。 */
    private final int remotePort;
    /** 底层连接/握手超时(毫秒)。 */
    private final int timeoutMs;
    /** 隧道是否已关闭的标记,保证 close 只生效一次。 */
    private final AtomicBoolean closed = new AtomicBoolean(false);
    /** 接入的本地客户端 socket(客户端 → 隧道)。 */
    private volatile Socket local;
    /** 与远程 ClickHouse 建立的 TLS socket(隧道 → 服务端)。 */
    private volatile Socket remote;

    private NativeTlsTunnel(ServerSocket listener, String remoteHost, int remotePort, int timeoutMs) {
        // 保存监听 socket。
        this.listener = listener;
        // 记录远程主机名。
        this.remoteHost = remoteHost;
        // 记录远程端口。
        this.remotePort = remotePort;
        // 记录超时毫秒数。
        this.timeoutMs = timeoutMs;
    }

    static NativeTlsTunnel open(String host, int port, long timeoutMs) {
        // 空主机名直接视为参数错误。
        if (host == null || host.trim().isEmpty()) {
            throw new IllegalStateException("TLS connection host is empty");
        }
        // 把毫秒超时收敛到 1..Integer.MAX_VALUE 秒内,避免溢出。
        // 收敛的真正目的是不让 0 或负数传下去:setSoTimeout(0) 意味着“永不超时”,而
        // connect 的超时参数为 0 同样会变成无限等待,都会让失败场景变成挂死。
        int timeout = (int) Math.min(Integer.MAX_VALUE, Math.max(1L, timeoutMs));
        try {
            // 在回环地址上随机端口建监听,backlog=1(同一时刻只服务一个 JDBC 客户端)。
            ServerSocket server = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress());
            // 构造隧道对象(此时尚未有连接进来)。
            NativeTlsTunnel tunnel = new NativeTlsTunnel(server, host.trim(), port, timeout);
            // 启动后台线程 accept 本地客户端并转发到远程。
            Thread t = new Thread(tunnel::acceptOne, "ck-tls-tunnel-" + server.getLocalPort());
            // 守护线程:不阻止进程退出。
            t.setDaemon(true);
            // 启动后台 accept 线程。
            t.start();
            // 返回隧道,JDBC 客户端连其本地端口即可。
            return tunnel;
        } catch (IOException e) {
            throw new IllegalStateException("create TLS tunnel failed: " + e.getMessage(), e);
        }
    }

    int localPort() {
        // 返回本地监听端口,供 JDBC URL 使用(走 127.0.0.1)。
        return listener.getLocalPort();
    }

    private void acceptOne() {
        // 等待并接受一个本地 JDBC 客户端(退出时自动关闭)。
        // backlog=1 与本方法只 accept 一次:一条隧道服务一条 JDBC 连接(见 CkClient.open 的
        // 一对一关系),不做多路复用。边界:若同一个本地端口被两个客户端同时连,第二个会在
        // 内核队列里等待而不会被处理——驱动侧必须保证一条 CkClient 一条连接。
        try (Socket accepted = listener.accept()) {
            // 隧道已关闭则不再处理新接入。
            if (closed.get()) return;
            // 记录本地客户端 socket。
            register(accepted, true);
            // 给本地接入的读超时设上连接超时值。
            // 这一段(建 TCP + TLS 握手)是有超时保护的:客户端连上却不发数据时不会把线程
            // 永久钉死;握手完成后立刻改回 0(不超时),否则空闲的长连接会被读超时掐断。
            accepted.setSoTimeout(timeoutMs);
            // 新建一条到远程 ClickHouse 的明文 TCP 连接。
            Socket raw = new Socket();
            register(raw, false);
            // 带超时连接远程 host:port。
            raw.connect(new InetSocketAddress(remoteHost, remotePort), timeoutMs);
            // 取系统默认 SSLSocketFactory。
            // 保留系统信任链验证，握手时另验证证书对应原始远端主机名。
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            // 基于已连好的 raw socket 再包一层 TLS(自动关闭底层)。
            // 末位 true = autoClose:关闭 ssl 会连带关闭底层 raw,TCP 连接不会残留。
            SSLSocket ssl = (SSLSocket) factory.createSocket(raw, remoteHost, remotePort, true);
            register(ssl, false);
            handshake(ssl, timeoutMs);
            // 握手成功后放开本地接入的超时,进入长连接转发。
            accepted.setSoTimeout(0);
            // 双向转发字节流(本地↔远程)。
            forward(accepted, ssl);
        } catch (Exception ignored) {
            // 任一环节失败(客户端断开/TLS 失败)时,JDBC 驱动会把本地 socket 关闭
            // 当作其连接错误上报,这里静默即可。
            // 单连接隧道结束时由 finally 关闭两端及监听 socket。
        } finally {
            close();
        }
    }

    static void handshake(SSLSocket ssl, int timeoutMs) throws IOException {
        javax.net.ssl.SSLParameters parameters = ssl.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        ssl.setSSLParameters(parameters);
        ssl.setSoTimeout(timeoutMs);
        ssl.startHandshake();
        ssl.setSoTimeout(0); // 握手后允许连接池空闲，查询超时由上层管理。
    }

    private synchronized void register(Socket socket, boolean client) throws IOException {
        if (closed.get()) {
            socket.close();
            throw new IOException("TLS tunnel closed");
        }
        if (client) local = socket; else remote = socket;
    }

    private void forward(Socket a, Socket b) throws InterruptedException {
        // 上行线程:本地→远程(ClickHouse 客户端发原生握手/查询包)。
        Thread ab = new Thread(() -> copy(a, b), "ck-tls-up");
        // 下行线程:远程→本地(ClickHouse 服务端返回数据/心跳应答)。
        Thread ba = new Thread(() -> copy(b, a), "ck-tls-down");
        // 上行线程设守护。
        ab.setDaemon(true);
        // 下行线程设守护。
        ba.setDaemon(true);
        // 启动上行复制线程。
        ab.start();
        // 启动下行复制线程。
        ba.start();
        // 等待上行复制结束(即本地客户端断开)。
        // 无超时的 join:客户端可能长时间保持空闲连接(应用侧连接池复用),此时两个复制线程
        // 都阻塞在 read 上,本线程也随之阻塞——它只是 accept 线程,不额外占资源。
        ab.join();
        // 客户端断开后主动关闭本地 socket,触发下行复制结束。
        // 必须主动关两端:另一方向可能正阻塞在 read 上,仅靠一端 EOF 无法把它唤醒。
        closeSocket(a);
        // 关闭远程 socket。
        closeSocket(b);
        // 再给下行线程最多 1 秒收尾。
        ba.join(1000L);
    }

    private static void copy(Socket from, Socket to) {
        // 单向搬运 from 的字节流到 to。
        try {
            // 取源 socket 的输入流。
            InputStream in = from.getInputStream();
            // 取目标 socket 的输出流。
            OutputStream out = to.getOutputStream();
            // 16KB 中转缓冲区。
            byte[] buf = new byte[16 * 1024];
            // n 记录本次读到的字节数。
            int n;
            // 循环读到 EOF(-1)为止。
            // 读到 -1 说明本方向的来源端已关闭;此时 finally 会把两端都关掉,于是另一方向的
            // 复制线程也会因 read 报错/EOF 退出——两个方向靠共享的这段 finally 互相收尾,
            // 无需额外的状态协调,也不会出现只关一半的半开连接。
            while ((n = in.read(buf)) >= 0) {
                // 只搬运实际读到的部分。
                if (n > 0) {
                    out.write(buf, 0, n);
                    // 立即冲刷,保证低延迟(避免 NATIVE 心跳/应答被缓冲延迟)。
                    // 说明:套接字输出流本身无用户态缓冲,flush 实际是空操作;写在这里是为了
                    // 固化“每读即写即发”的语义,避免日后换成带缓冲的流时改变时延特性。
                    out.flush();
                }
            }
        } catch (IOException ignored) {
            // 对端关闭了流,属正常断开。
        } finally {
            // 复制结束统一关闭两端 socket。
            closeSocket(from);
            closeSocket(to);
        }
    }

    private static void closeSocket(Socket socket) {
        if (socket != null) {
            // 忽略关闭异常:关闭 socket 一般不需要处理。
            try { socket.close(); } catch (IOException ignored) { }
        }
    }

    @Override
    public synchronized void close() {
        // CAS 保证只执行一次关闭流程。
        // 多条路径都可能触发关闭(连接池 remove、CkClient 关闭引发的 EOF、失败清理),CAS 让
        // 重复调用变成空操作;同时它也是与 acceptOne 之间的开关——close 之后再接入的客户端
        // 会被 acceptOne 开头的 closed 判断直接丢弃。
        if (!closed.compareAndSet(false, true)) return;
        // 关闭监听 socket,停止接受新连接(忽略异常)。
        try { listener.close(); } catch (IOException ignored) { }
        // 释放本地接入 socket。
        closeSocket(local);
        // 释放远程 TLS socket。
        closeSocket(remote);
    }
}
