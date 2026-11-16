package com.el2ck.ckpool;

/**
 * 【文件职责】按连接身份缓存 ClickHouse 客户端，统一处理连接复用、失效清理和关闭生命周期。
 */

import java.util.concurrent.ConcurrentHashMap;

/**
 * 按连接 ID 缓存 ClickHouse 客户端,避免每请求重建连接。
 * 对应 Go ckpool.Manager,但 Java 版用 synchronized 简化并发(打开/移除全串行)。
 *
 * 保活/重建规则:取用(get)时先做"存活期"检查(超过 connMaxLifetimeMs 直接重建),
 * 再做 Ping 新鲜度检查(TTL 5s 内不再发 Ping);Ping 失败或连接超龄都会从缓存摘除并关闭,
 * 随后现场新开一条放入缓存返回。缓存 key 为逻辑连接 ID,连接更新/删除后须调用 remove。
 */
public class CkPool implements AutoCloseable {

    /** 缓存命中后多久内不重做 Ping,降低每请求 +1 RTT。 */
    private static final long PING_TTL_MS = 5000;

    /** 缓存键 → 缓存的客户端。键由调用方构造:管理连接 c:{id},业务身份 u:{user}:c:{id}:b:{binding}:v{version}。 */
    private final ConcurrentHashMap<String, CkClient> clients = new ConcurrentHashMap<>();

    /**
     * 获取(必要时打开并缓存)连接客户端。
     * 5 秒内 Ping 过则直接返回;Ping 失败则移除旧连接并重建。
     */
    public synchronized CkClient get(long id, CkConfig cfg) {
        return get("c:" + id, cfg);
    }

    /** 按复合键获取客户端。同一物理连接的不同用户身份绝不共用连接。 */
    public synchronized CkClient get(String id, CkConfig cfg) {
        // 先从缓存取出该连接的客户端,未命中时为 null。
        CkClient c = clients.get(id);
        // 已作废的连接(上一次执行在驱动层失败、字节流已错位)直接摘除重建,不走 Ping。
        // ⚠️ 必须放在下面 fresh(c) 那条捷径【之前】:5 秒内 Ping 过就直接返回缓存对象,
        //    正是「一次失败连累后续所有请求」的那条通道。
        // remove 与 close 的顺序与下方 Ping 失败那条保持一致(先摘除再关,避免并发请求
        // 在两步之间取到这条已关闭的连接)。
        if (c != null && c.invalidated()) {
            clients.remove(id);
            c.close();
            c = null;
        }
        // 命中缓存时先处理连接超龄(比 PING_TTL 更优先)。
        // 顺序是有意的:存活期是硬约束(如服务端/负载均衡侧的连接时长上限),即便连接刚 Ping
        // 通过、看起来“新鲜”,到达 lifetime 也必须换掉,否则会用到一条注定被对端掐断的连接。
        if (c != null) {
            // 连接最长存活到期后主动重建,使全局/连接级 lifetime 配置生效。
            if (c.expired()) {
                // 先把失效连接从缓存摘除,避免其它请求再取到。
                clients.remove(id);
                // 关闭旧连接,释放底层 socket / TLS 隧道。
                c.close();
                // 置空以便走下方重建分支。
                c = null;
            }
        }
        // 仍持有有效连接:按 Ping 新鲜度决定直接复用或刷新。
        if (c != null) {
            // TTL 内(5s)Ping 过 → 直接用,不再发 Ping 包。
            if (fresh(c)) {
                return c;
            }
            // 已过期 → 尝试 Ping 刷新。
            if (c.ping()) {
                return c;
            }
            // Ping 失败:先从 map 摘除再关闭,避免后续请求拿到失效连接。
            // 顺序不能反:先 close 后 remove 的话,并发请求会在两步之间从 map 里取到那条已
            // 关闭的连接并用它执行查询,表现为难以复现的“连接已关闭”偶发错误。
            clients.remove(id);
            // 关闭失效连接。
            c.close();
        }
        // 打开新连接并入缓存。
        // synchronized 作用于整个方法,因此同一时刻只有一条建连在跑(没有“单飞”之外的优化);
        // 边界:建连失败会直接把异常抛给调用方且不写缓存,下一次请求会重新尝试——即失败不被
        // 记忆,连接串配错时会持续重试而不是快速失败。
        CkClient nc = CkClient.open(cfg);
        // 放入缓存,后续请求可复用。
        clients.put(id, nc);
        // 返回新连接。
        return nc;
    }

    /** 打开一条临时连接做连通性测试,随后立即关闭(不缓存)。 */
    // 走的是与正式连接完全相同的 open 流程(含会话 SET 与 Ping),因此测试结果能代表真实
    // 可用性;不入缓存是有意的——测试用的凭据/参数可能是临时的,不该污染正在服役的客户端。
    public void test(CkConfig cfg) {
        // try-with-resources 同时覆盖 open 成功后出现的任何异常，确保健康探测不泄漏 JDBC/TLS 资源。
        try (CkClient c = CkClient.open(cfg)) {
            // CkClient.open 已执行完整的 SET 与 SELECT 1 探测；此处只需确保临时连接被关闭。
        }
    }

    /** 关闭并移除指定连接的缓存(连接更新/删除后调用)。 */
    // 两步都要做:只 close 不 remove 的话,map 里会留下一条已关闭的连接,下一次 get 命中后
    // 虽然 Ping 会失败并触发重建,但要多付一次无谓的 Ping 往返;只 remove 不 close 则泄漏连接。
    public synchronized void remove(long id) {
        remove("c:" + id);
    }

    /** 按复合键关闭并移除客户端。 */
    public synchronized void remove(String id) {
        // 从缓存移除并取回该客户端(不存在则返回 null)。
        CkClient c = clients.remove(id);
        // 若确实存在缓存,则关闭其底层连接。
        if (c != null) {
            c.close();
        }
    }

    /**
     * 按前缀批量关闭并移除。用户禁用、凭据轮换或撤权后调用,确保不残留旧身份连接。
     */
    public synchronized void removePrefix(String prefix) {
        java.util.Iterator<java.util.Map.Entry<String, CkClient>> it = clients.entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<String, CkClient> e = it.next();
            if (e.getKey().startsWith(prefix)) {
                it.remove();
                try { e.getValue().close(); } catch (Exception ignored) { }
            }
        }
    }

    /**
     * 移除键中包含指定片段的客户端。用于连接配置变更时清理所有指向该连接的用户身份连接
     * (用户键形如 {@code u:42:c:7:b:3:v1},连接片段不在开头)。
     */
    public synchronized void removeContaining(String fragment) {
        java.util.Iterator<java.util.Map.Entry<String, CkClient>> it = clients.entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<String, CkClient> e = it.next();
            if (e.getKey().contains(fragment)) {
                it.remove();
                try { e.getValue().close(); } catch (Exception ignored) { }
            }
        }
    }

    /** 关闭全部连接。 */
    public synchronized void closeAll() {
        // 遍历所有缓存的客户端逐个关闭。
        for (CkClient c : clients.values()) {
            // 单个关闭包住 try-catch,避免某个失败中断整体关闭。
            try {
                c.close();
            } catch (Exception ignored) {
                // 忽略单个关闭异常,继续关闭其余。
            }
        }
        // 关闭后清空缓存映射。
        clients.clear();
    }

    @Override
    public void close() {
        // 实现 AutoCloseable:统一走 closeAll 关闭并清空全部连接。
        closeAll();
    }

    /** 是否在 TTL 内 Ping 过。 */
    // 池中客户端的 lastPingMs 在 CkClient.open 末尾就会 Ping 一次,所以 0(从未 Ping)基本
    // 只在手工构造/异常路径出现,这里按“不新鲜”处理即让调用方补一次 Ping,是安全的保守选择。
    private static boolean fresh(CkClient c) {
        // 读取该客户端上次 Ping 成功的时刻。
        long last = c.lastPingMs();
        // 从未 Ping 过(时刻为 0)视为不新鲜。
        if (last == 0) {
            return false;
        }
        // 距上次 Ping 不足 PING_TTL_MS(5s)即为新鲜。
        return System.currentTimeMillis() - last < PING_TTL_MS;
    }
}
