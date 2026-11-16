package com.el2ck.ckpool;

/**
 * 【文件职责】描述 ClickHouse 连接参数，并区分 native 9000/9001 与 HTTP 8123/8132 的协议语义。
 */

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ClickHouse 连接参数(对应 Go ckpool.Config)。
 * 由 ConnectionService 依据连接配置 + 全局默认组装:连接覆盖 > 全局 > 内置默认。
 */
@Data
public class CkConfig {
    /** 协议取值:原生 TCP(native 驱动,通常 9000)。 */
    public static final String PROTOCOL_NATIVE = "native";
    /** 协议取值:HTTP 接口(官方驱动,通常 8123)。 */
    public static final String PROTOCOL_HTTP = "http";

    /** ClickHouse 主机地址(明文为 host;native + secure=true 时为本地隧道 127.0.0.1)。 */
    private String host;
    /** 端口(native 通常 9000/9001,HTTP 通常 8123/8132);协议决定驱动,端口表示实际可达入口。 */
    private int port;
    /**
     * 连接协议:{@link #PROTOCOL_NATIVE}(默认)或 {@link #PROTOCOL_HTTP}。
     *
     * <p>由 {@link CkClient#open} 据此决定驱动类与 JDBC URL 形态;两种协议的
     * settings 下发方式也不同(见该处说明)。默认 native,保证旧配置行为不变。
     */
    private String protocol = PROTOCOL_NATIVE;
    /** 目标数据库名(缺省 default,经 enc 净化后拼入 JDBC URL)。 */
    private String database;
    /** 登录用户名。 */
    private String username;
    /** 登录密码(可为空串)。 */
    private String password;
    /** 是否走 TLS:native 经 NativeTlsTunnel 转发;http 直接用 https。 */
    private boolean secure;

    /** 连接超时(毫秒),<=0 表示用内置默认 5000。 */
    private long dialTimeoutMs;
    /** 查询超时(毫秒),由上层用于超时控制。 */
    private long queryTimeoutMs;
    /** 连接最大保持(毫秒)。 */
    // 单位是毫秒,但调用方(Engine/SyncEngine 组装配置时)拿到的是“分钟”配置,已在那侧乘
    // 60_000 换算;因此这里不会再做单位转换,只需要保证非 0 即可(CkClient 内部对 <=0 兜底)。
    private long connMaxLifetimeMs;
    /** 只读模式(仅用于服务层写拦截,不传给驱动)。 */
    // 注意语义:它不参与建连,只作为“本次操作是否允许写”的判定依据被上层读取
    // (DatabaseService/QueryService/UserService/SyncEngine 各自在入口处拦截)。
    private boolean readOnly;
    /** 自定义 ClickHouse 参数(覆盖内置 max_result_rows 等)。 */
    // 合并顺序在 CkClient.open 里:内置兜底先放、本 Map 后放,因此这里的同名键会覆盖内置值;
    // 由 ConnectionService.parseParamsJSON 从连接/全局参数 JSON 解析而来。
    private Map<String, Object> settings = new LinkedHashMap<>();

    /** host:port。 */
    public String addr() {
        // 拼出 "host:port",用于日志/异常提示。
        return host + ":" + port;
    }

    /**
     * 归一化协议取值:识别 {@code http}(忽略大小写与空白),其余一律按 {@link #PROTOCOL_NATIVE} 处理。
     *
     * <p>刻意不报错:协议是新增的可选字段,历史数据、外部手工写入的 JSON、旧前端都可能没有它或给出
     * 非预期值 —— 这些情况都应退回「原有行为」而不是让建连失败。
     */
    public static String normalizeProtocol(String raw) {
        return raw != null && PROTOCOL_HTTP.equalsIgnoreCase(raw.trim()) ? PROTOCOL_HTTP : PROTOCOL_NATIVE;
    }

    /** 返回协议对应的标准端口；9001/8132 等转发入口需要由用户显式填写。 */
    public static int defaultPort(String protocol) {
        return PROTOCOL_HTTP.equals(normalizeProtocol(protocol)) ? 8123 : 9000;
    }

    /**
     * 校验「协议 + 端口」组合。端口决定实际入口，协议决定使用的驱动；
     * 9001/8132 分别表示转发到 native/HTTP 标准端口，协议仍须与目标服务一致。
     */
    public static void requireCompatibleProtocolPort(String protocol, int port) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("端口必须在 1-65535 之间");
        }
        boolean http = PROTOCOL_HTTP.equals(normalizeProtocol(protocol));
        if (http && (port == 9000 || port == 9001)) {
            throw new IllegalArgumentException("协议与端口不匹配：端口 " + port
                    + " 是 ClickHouse native 入口，协议应选择 native；HTTP 通常使用 8123 或经 8132 转发");
        }
        if (!http && (port == 8123 || port == 8132)) {
            throw new IllegalArgumentException("协议与端口不匹配：端口 " + port
                    + " 是 ClickHouse HTTP 入口，协议应选择 http；native 通常使用 9000 或经 9001 转发");
        }
    }

    /** 返回一套内置默认连接参数(本机 9000 / default 库 / 非 TLS)。 */
    public static CkConfig defaults() {
        CkConfig c = new CkConfig();
        c.host = "127.0.0.1";
        c.port = 9000;
        c.database = "default";
        c.username = "default";
        c.password = "";
        c.secure = false;
        c.dialTimeoutMs = 5000L;
        c.queryTimeoutMs = 60000L;
        c.connMaxLifetimeMs = 30L * 60_000L;
        c.readOnly = false;
        return c;
    }

    /**
     * 复制一份配置。
     *
     * <p>用于「只改一个字段、其余照旧」的场景(如导出时把库名换成用户指定的库)。
     * 必须复制而不是就地改:配置对象在 ConnectionService 里是按连接缓存复用的,
     * 就地改会把这一次的覆盖泄漏给之后所有请求。
     */
    public CkConfig copy() {
        CkConfig c = new CkConfig();
        c.host = host;
        c.port = port;
        c.protocol = protocol;
        c.database = database;
        c.username = username;
        c.password = password;
        c.secure = secure;
        c.dialTimeoutMs = dialTimeoutMs;
        c.queryTimeoutMs = queryTimeoutMs;
        c.connMaxLifetimeMs = connMaxLifetimeMs;
        c.readOnly = readOnly;
        // settings 也复制一份:两个对象共享同一个 Map,改一处两边都变。
        c.settings = new LinkedHashMap<>(settings);
        return c;
    }
}
