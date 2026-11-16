package com.el2ck.config;

/**
 * 【文件职责】输出启动环境诊断信息，帮助定位数据库、目录和运行时配置问题。
 */

import java.util.ArrayList;
import java.util.List;

/** 只报告配置，不更改密钥、监听地址或平台登录策略。 */
public final class StartupDiagnostics {
    private StartupDiagnostics() { }

    /**
     * 启动时的配置风险提示。返回值只用于打印,**不改变任何配置、不阻断启动**。
     *
     * <p>目前只在两种情况下告警:密钥仍是空白或内置开发值(生产会泄漏加密数据),
     * 以及监听在非回环地址上对外开放(此时才需要提醒 HTTPS —— 仅本机访问不涉及传输安全)。
     */
    public static List<String> warnings(AppConfig config, String address, boolean tls) {
        List<String> warnings = new ArrayList<>();
        String secret = config.getSecretKey();
        if (secret == null || secret.trim().isEmpty() || AppConfig.DEFAULT_SECRET_KEY.equals(secret)) {
            warnings.add("仍使用空白或内置开发密钥。生产部署请配置 app.secret-key；已有加密配置需先备份并迁移，直接更换密钥将无法解密旧数据。");
        }
        if (!loopback(address)) {
            warnings.add("服务监听非回环地址，平台账号登录已启用；请核对防火墙及反向代理访问范围。"
                    + (tls ? "" : "应用未启用 HTTPS，对外访问应通过可信 HTTPS 入口。"));
        }
        return warnings;
    }

    /** 判断监听地址是否仅限本机:接受 localhost、IPv6 回环的几种写法,以及 127.0.0.0/8 全部地址。 */
    static boolean loopback(String address) {
        if (address == null) return false;
        String host = address.trim();
        if ("localhost".equalsIgnoreCase(host) || "::1".equals(host) || "[::1]".equals(host)
                || "0:0:0:0:0:0:0:1".equals(host)) return true;
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4 || !"127".equals(parts[0])) return false;
        for (String part : parts) {
            if (!part.matches("[0-9]{1,3}") || Integer.parseInt(part) > 255) return false;
        }
        return true;
    }
}
