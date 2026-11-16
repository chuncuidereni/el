/**
 * 【文件职责】本文件定义数据库连接管理模块的业务服务 ConnectionService。承载该模块的核心业务规则，协调仓储、连接器或外部服务，并统一转换业务异常和操作状态。
 */

package com.el2ck.service.connection;

import com.el2ck.config.AppConfig;
import com.el2ck.exception.ApiException;
import com.el2ck.util.Sql;
import com.el2ck.ckpool.CkClient;
import com.el2ck.ckpool.CkConfig;
import com.el2ck.ckpool.CkPool;
import com.el2ck.util.Crypto;
import com.el2ck.util.AfterCommit;
import org.springframework.transaction.annotation.Transactional;
import com.el2ck.entity.system.AppSettings;
import com.el2ck.entity.connection.Connection;
import com.el2ck.entity.connection.ConnectionDTO;
import com.el2ck.entity.connection.ConnectionInput;
import com.el2ck.entity.connection.ConnStatus;
import com.el2ck.repository.metadata.MetadataRepository;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * ClickHouse 连接管理服务:连接 CRUD 与客户端/生效配置的供给(对应 Go internal/service/connection_service.go)。
 *
 * 核心职责:
 *  1. 连接 CRUD:新增 / 更新 / 删除 / 列表 / 详情;密码经 AES-GCM 加密后落库,永不回传,
 *     对外仅以 hasPassword 标记「是否已设置密码」;
 *  2. 生效配置组装 cfg():按「连接级高级覆盖 > 全局默认 > 内置默认」三层合并出 {@link CkConfig},
 *     并解密存储的密码、合并自定义参数;结果带 5 分钟 TTL 内存缓存(Update/Delete 时主动失效);
 *  3. 连通性检测:CheckStatus / CheckStatuses 用 SELECT 1(短超时)对连接列表逐条探测;
 *  4. Client():供数据库 / 查询 / 同步等其它服务按连接 ID 取得并复用缓存的 ClickHouse 客户端。
 * 校验失败统一报 400、记录不存在报 404,错误文案与 Go 版保持一致。
 */
public class ConnectionService {

    /** 配置缓存有效期(毫秒):连接参数变更时由 Update/Delete 主动失效(对应 Go cfgCacheTTL=5min)。 */
    private static final long CFG_CACHE_TTL_MS = 5L * 60L * 1000L;

    /** 独立 ObjectMapper:仅用于连接自定义参数 JSON 的合法性校验与解析(不采用 Spring 注入的实例)。 */
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** 元数据仓库:读写连接记录与应用全局设置。 */
    private final MetadataRepository repository;
    /** 连接池:按连接 ID 缓存并复用 ClickHouse 客户端。 */
    private final CkPool pool;
    /** 全局 AES-GCM 加密密钥(用于连接密码的加解密)。 */
    private final String secret;

    /** 已组装连接参数的缓存:键为连接 ID;值为组装好的配置 + 过期时刻(读写分别加锁)。 */
    private final Map<Long, Entry> cfgCache = new ConcurrentHashMap<>();

    /** 配置缓存并发控制锁(读多写少)。 */
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    /** 写锁下递增；阻止配置刷新之前开始的慢读取在刷新之后重新填回旧缓存。 */
    private long cfgRevision;

    /** 缓存条目:组装好的配置 + 过期时刻(超时即需重建)。 */
    private static final class Entry {
        // 组装好的生效配置
        final CkConfig cfg;
        // 过期时刻(系统当前毫秒 + TTL)
        final long expires;

        Entry(CkConfig cfg, long expires) {
            // 记录配置
            this.cfg = cfg;
            // 记录过期时刻
            this.expires = expires;
        }
    }
    /**
     * 创建ClickHouse 连接业务服务并装配后续调用所需的依赖。
     *
     * 依赖由 Spring 容器统一管理；本构造器只保存引用或初始化轻量适配对象，不执行远程业务操作。
     *
     * @param repository 元数据仓储，用于读取或写入平台业务记录
     * @param pool ClickHouse 客户端池，用于管理共享连接资源
     * @param secret 应用主密钥，用于配置解密和完整性摘要计算
     */
    public ConnectionService(MetadataRepository repository, CkPool pool, String secret) {
        // 保存元数据仓库引用
        this.repository = repository;
        // 保存连接池引用
        this.pool = pool;
        // 保存加密密钥引用
        this.secret = secret;
    }

    // =====================================================================
    // 内置默认连接种子(对应 Go util.go SeedDefaultConnection)
    // =====================================================================

    /** YAML owns the default connection; retain its persisted ID for task references. */
    public static void seedDefaultConnection(MetadataRepository store, String secret, AppConfig.DefaultCk def) {
        if (def == null || def.getHost() == null || def.getHost().trim().isEmpty()
                || def.getPort() == null || def.getPort() < 1 || def.getPort() > 65535
                || def.getDatabase() == null || def.getDatabase().trim().isEmpty()
                || def.getUsername() == null || def.getUsername().trim().isEmpty()
                || def.getPassword() == null) {
            throw new IllegalArgumentException("请在 application.yml 完整配置 app.default-ck（host、port、database、username、password）");
        }
        List<Connection> list = store.listConnections();
        String seedName = defStr(def.getName(), "目标数据库");
        // Resolve the configured seed first: the UI may have selected another default.
        // Renaming that other connection would collide with the existing seed name.
        Connection c = list.stream().filter(item -> java.util.Objects.equals(item.getName(), seedName)).findFirst().orElse(null);
        if (c == null) c = list.stream().filter(Connection::isDefault).findFirst().orElse(null);
        boolean create = c == null;
        if (create) { c = new Connection(); c.setParamsJson("{}"); }
        c.setName(defStr(def.getName(), "目标数据库"));
        c.setHost(def.getHost().trim());
        // 协议:未配置或非法值按 native(归一化,保持既有行为)
        c.setProtocol(CkConfig.normalizeProtocol(def.getProtocol()));
        c.setPort(def.getPort());
        c.setDatabase(def.getDatabase().trim());
        c.setUsername(def.getUsername().trim());
        c.setPasswordEnc(Crypto.encrypt(secret, def.getPassword()));
        c.setSecure(def.isSecure());
        c.setDescription(defStr(def.getDescription(), "默认连接"));
        c.setDefault(true);
        if (create) store.createConnection(c); else store.updateConnection(c);
    }

    /**
     * 返回非空字符串；输入为 null 或空串时使用默认值。
     *
     * <p>仅把空串视为缺失，纯空白文本会原样保留。
     *
     * @param s 原始值
     * @param def 缺失时默认值
     * @return 原始值或默认值
     */
    private static String defStr(String s, String def) {
        // s 为空(null 或空串)时回退默认值,否则用 s
        return (s == null || s.isEmpty()) ? def : s;
    }

    // =====================================================================
    // 配置缓存主动失效(对外访问入口)
    // =====================================================================

    /** 主动失效某条连接的配置缓存(Update/Delete 后调用)。 */
    public void invalidateCfg(long id) {
        // 取写锁,串行化缓存修改
        lock.writeLock().lock();
        try {
            // 移除指定连接的缓存条目
            cfgCache.remove(id);
            cfgRevision++;
        } finally {
            // 无论成败都释放写锁
            lock.writeLock().unlock();
        }
    }

    /** 全部连接的生效配置缓存主动失效(供全局默认配置变更后调用,使各连接按新默认值重建)。 */
    public void invalidateAllCfg() {
        // 全局配置变更后清空整张配置缓存
        lock.writeLock().lock();
        try {
            // 清空全部缓存条目
            cfgCache.clear();
            cfgRevision++;
        } finally {
            // 释放写锁
            lock.writeLock().unlock();
        }
    }

    // =====================================================================
    // 连接 CRUD(对应 Go List/Get/Create/Update/Delete)
    // =====================================================================

    /** 返回全部连接(DTO)。 */
    public List<ConnectionDTO> list() {
        // 读取全部连接记录
        List<Connection> list = repository.listConnections();
        // 按记录数预分配结果容器
        List<ConnectionDTO> out = new ArrayList<>(list.size());
        // 逐条转成对外 DTO
        for (Connection c : list) {
            out.add(toDTO(c));
        }
        return out;
    }

    /** 按 ID 查询连接;不存在抛 404。 */
    public ConnectionDTO get(long id) {
        // 按 ID 取连接记录
        Connection c = repository.getConnection(id);
        // 记录不存在时报 404
        if (c == null) {
            throw ApiException.notFound("记录不存在");
        }
        // 转为 DTO 返回
        return toDTO(c);
    }

    /** 新增连接:补默认值 → 校验 → 加密 → 落库。 */
    public ConnectionDTO create(ConnectionInput in) {
        if (in == null) {
            throw ApiException.validation("请求内容不能为空");
        }
        // 必填项校验(在补默认值之前:否则 database/username 会被补成 "default",必填形同虚设)
        validateRequiredFields(in, true);
        // 先补齐缺省字段
        fillDefaults(in);
        // 再校验必填字段
        validateConnectionInput(in);
        // 对传入明文密码做 AES-GCM 加密
        String enc = Crypto.encrypt(secret, in.getPassword() == null ? "" : in.getPassword());
        // 新建连接实体
        Connection c = new Connection();
        c.setName(in.getName());
        c.setHost(in.getHost());
        c.setPort(in.getPort());
        // 协议归一化:未传或非法值一律按 native(保持既有行为)
        c.setProtocol(CkConfig.normalizeProtocol(in.getProtocol()));
        c.setDatabase(in.getDatabase());
        c.setUsername(in.getUsername());
        // 写入加密后的密码密文
        c.setPasswordEnc(enc);
        // 安全传输开关(null 视为 false)
        c.setSecure(in.getSecure() != null && in.getSecure());
        // 描述为空时置空串
        c.setDescription(defaultStr(in.getDescription()));
        // 高级配置原样保存(global 语义由前端控制,这里不处理)
        c.setDialTimeoutMs(in.getDialTimeoutMs());
        // 查询超时
        c.setQueryTimeoutMs(in.getQueryTimeoutMs());
        // 连接最大存活分钟
        c.setConnMaxLifetimeMin(in.getConnMaxLifetimeMin());
        // 是否只读
        c.setReadOnly(in.getReadOnly());
        // 自定义参数 JSON(由仓库保存)
        c.setParamsJson(normalizeParams(in.getParamsJson()));
        long id = repository.createConnection(c);
        // 回填自增主键
        c.setId(id);
        // 返回新建结果 DTO
        return toDTO(c);
    }

    /** 更新连接:密码留空 → 沿用原密码;advancedScope=global → 清除连接级高级覆盖。 */
    @Transactional
    public ConnectionDTO update(long id, ConnectionInput in) {
        if (in == null) {
            throw ApiException.validation("请求内容不能为空");
        }
        // 先取旧记录(缺省值与密码沿用都依赖它)
        Connection old = repository.getConnection(id);
        // 记录不存在时报 404
        if (old == null) {
            throw ApiException.notFound("记录不存在");
        }
        // 必填项校验(在补默认值之前):名称/主机/用户名/数据库必填;密码编辑时可选(留空 = 沿用原密码)
        validateRequiredFields(in, false);
        // 未填字段沿用旧值
        fillDefaultsFrom(in, old);
        // 校验必填字段
        validateConnectionInput(in);

        // 组装新的连接实体
        Connection c = new Connection();
        c.setId(id);
        c.setName(in.getName());
        c.setHost(in.getHost());
        c.setPort(in.getPort());
        // 协议归一化:未传或非法值一律按 native(保持既有行为)
        c.setProtocol(CkConfig.normalizeProtocol(in.getProtocol()));
        c.setDatabase(in.getDatabase());
        c.setUsername(in.getUsername());
        // 安全传输开关(null 视为 false)
        c.setSecure(in.getSecure() != null && in.getSecure());
        // 描述为空时置空串
        c.setDescription(defaultStr(in.getDescription()));
        // isDefault 仅由「设为默认」逻辑单独管理(启动种子置位),普通更新保持原值,
        // 避免用户在编辑时误把默认标记清掉
        c.setDefault(old.isDefault());

        // 密码处理:传了新密码则重新加密
        if (in.getPassword() != null && !in.getPassword().isEmpty()) {
            // 新密码重新 AES-GCM 加密后写入
            c.setPasswordEnc(Crypto.encrypt(secret, in.getPassword()));
        } else {
            // 密码留空则沿用旧密文
            c.setPasswordEnc(old.getPasswordEnc());
        }

        // 高级配置作用域处理
        if ("global".equals(in.getAdvancedScope())) {
            // 作用域为 global:清空该连接全部高级覆盖,交给全局默认
            c.setDialTimeoutMs(null);
            c.setQueryTimeoutMs(null);
            c.setConnMaxLifetimeMin(null);
            c.setReadOnly(null);
            c.setParamsJson("{}");
        } else {
            // 作用域为连接级:原样写入连接级高级覆盖
            c.setDialTimeoutMs(in.getDialTimeoutMs());
            c.setQueryTimeoutMs(in.getQueryTimeoutMs());
            c.setConnMaxLifetimeMin(in.getConnMaxLifetimeMin());
            c.setReadOnly(in.getReadOnly());
            c.setParamsJson(normalizeParams(in.getParamsJson()));
        }

        if (!repository.updateConnection(c)) {
            // 理论上前面 Get 已确认存在;并发删除时仍需返回明确的 404
            throw ApiException.notFound("记录不存在");
        }
        // 连接参数已变更:作废旧配置缓存并关闭旧连接池客户端
        // 注意顺序:先失效缓存再丢弃连接池客户端,避免后续请求拿到旧配置复活旧池
        AfterCommit.run(() -> {
            invalidateCfg(id);
            // 让连接池丢弃按旧配置建立的管理连接与全部用户身份连接。
            pool.remove(id);
            pool.removePrefix("c:" + id + ":");
            pool.removeContaining(":c:" + id + ":");
        });
        // 重新读取更新后的记录
        Connection updated = repository.getConnection(id);
        // 更新后取不到记录(可能被并发删除)时报 404
        if (updated == null) {
            throw ApiException.notFound("记录不存在");
        }
        // 返回更新后的 DTO
        return toDTO(updated);
    }

    /** 删除连接(级联清理 user_remarks 由 MetadataRepository 在事务内完成);并释放其连接池缓存。 */
    public void delete(long id) {
        // 删除连接记录(不存在时抛 NotFoundException)
        repository.deleteConnection(id);   // 不存在时抛 NotFoundException
        // 作废其配置缓存
        invalidateCfg(id);
        // 关闭并移除连接池中的管理连接及该连接下所有用户身份连接
        pool.remove(id);
        pool.removePrefix("c:" + id + ":");
    }

    /** 将某连接设为默认连接;清除其余连接的默认标记。 */
    public ConnectionDTO setDefault(long id) {
        // 目标连接必须存在
        if (repository.getConnection(id) == null) {
            throw ApiException.notFound("记录不存在");
        }
        // 由仓库在单条 UPDATE 中把默认标记移交给该连接
        repository.setDefaultConnection(id);
        // 返回设置后的连接
        return toDTO(repository.getConnection(id));
    }

    // =====================================================================
    // 连通性检测(对应 Go CheckStatus / CheckStatuses / TestConfig / TestByID)
    // =====================================================================

    /**
     * 检查单个已保存连接:打开连接执行 SELECT 1,失败抛异常,消息为驱动给出的错误文案。
     * 已保存连接的探测走 cfg() 组装生效配置;短超时由驱动的 connect_timeout 兜底。
     */
    public void testByID(long id) {
        // 取得生效配置
        CkConfig cfg = cfg(id);
        // 交给连接池做真实连通性探测
        pool.test(cfg);
    }

    /** 测试尚未保存的连接配置:用入参直接组装 CkConfig 后探测。 */
    public void testConfig(ConnectionInput in) {
        if (in == null) {
            throw ApiException.validation("请求内容不能为空");
        }
        // 必填项校验(在补默认值之前):测连同样要求 主机/用户名/数据库;密码可选(编辑态留空=沿用)
        validateRequiredFields(in, false);
        // 先补齐缺省字段
        fillDefaults(in);
        // 校验必填字段
        validateConnectionInput(in);
        // 用入参直接组装待测配置(密码为明文,null 按空串;规则见 fromInput)
        CkConfig cfg = fromInput(in, in.getPassword());
        // 交给连接池探测
        pool.test(cfg);
    }

    /** 测试编辑中的连接配置；密码为空时沿用已保存密码，不修改数据库。 */
    public void testConfig(long id, ConnectionInput in) {
        if (in == null) {
            throw ApiException.validation("请求内容不能为空");
        }
        Connection old = repository.getConnection(id);
        if (old == null) {
            throw ApiException.notFound("记录不存在");
        }
        fillDefaultsFrom(in, old);
        // 编辑态密码留空表示"不修改":临时解出已存密码用于本次探测,只存在于内存,不会回写数据库
        if (in.getPassword() == null || in.getPassword().isEmpty()) {
            try {
                in.setPassword(Crypto.decrypt(secret, old.getPasswordEnc()));
            } catch (RuntimeException e) {
                throw ApiException.badGateway("连接密码解密失败: " + Sql.messageOf(e));
            }
        }
        testConfig(in);
    }

    /**
     * 并发检查所有连接的健康状态(即连接列表的状态列)。永不抛出;单条失败记入 ConnStatus.error。
     * 对应 Go CheckStatuses。
     */
    public List<ConnStatus> checkStatuses() {
        // 读取全部连接记录
        List<Connection> list = repository.listConnections();
        // 按记录数预分配结果
        List<ConnStatus> results = new ArrayList<>(list.size());
        // 逐条探测;数量少且要迁就 ClickHouse 连接数限制,这里按序执行(语义与 Go 一致,串行更稳)
for (Connection c : list) {
            // 每条连接对应一个状态对象
            ConnStatus st = new ConnStatus();
            // 记录连接 ID
            st.setId(c.getId());
            // 记录探测开始时刻
            long start = System.currentTimeMillis();
            try {
                // 执行真实连通性探测
                testByID(c.getId());
                // 成功置 ok
                st.setOk(true);
            } catch (RuntimeException e) {
                // 失败置 ok=false 并记录错误文案
                st.setOk(false);
                st.setError(Sql.messageOf(e));
            }
            // 记录本次探测耗时(毫秒)
            st.setLatencyMs(System.currentTimeMillis() - start);
            // 加入结果集
            results.add(st);
        }
        return results;
    }

    // =====================================================================
    // 供其它服务使用:客户端 / 生效配置
    // =====================================================================

    /**
     * 取得<b>管理身份</b>的连接客户端(连接由连接池按 ID 缓存复用)。
     *
     * <p>⚠️ 只能用于平台管理动作(建/删连接、账号与授权管理、受控生命周期 DDL、系统监控),
     * <b>绝不能执行用户传入的任意业务 SQL</b>。业务查询/写入必须走
     * {@code CkIdentityService.client(connId)},由当前登录用户绑定的真实 CK 账号执行。
     */
    public CkClient client(long id) {
        // 取生效配置
        CkConfig cfg = cfg(id);
        try {
            // 从连接池按 ID 取出(或新建)客户端
            return pool.get(id, cfg);
        } catch (RuntimeException e) {
            // 目标库不可达统一报 502
            throw ApiException.badGateway(Sql.messageOf(e));
        }
    }

    /** {@link #client(long)} 的显式别名,避免新代码误把管理连接当成业务身份。 */
    public CkClient managementClient(long id) {
        return client(id);
    }

    /** 返回连接生效的完整配置(连接覆盖 > 全局默认 > 内置默认)。对应 Go EffectiveConfig()。 */
    public CkConfig effectiveConfig(long id) {
        return cfg(id);
    }

    /** 连接自身的生效配置(含存储的管理账号口令);业务身份在 {@code CkIdentityService} 中覆盖账号。 */
    public CkConfig managementConfig(long id) {
        return cfg(id);
    }

    /**
     * 开一条<b>专用</b>连接(不走连接池,用完由调用方 close)。导出专用。
     *
     * <p>为什么导出不能复用池里那条:
     * <ol>
     *   <li>导出可能跑几分钟,而池里那条连接是共享的(CkClient 的 query/exec 全带 synchronized),
     *       占着它等于让同一连接上的其它请求全部排队等导出跑完;</li>
     *   <li>导出允许指定库(数据探索页绑定的库常常不是连接自带的 default),而库名在
     *       JDBC 连接建立时就定死了,没法 per-query 切换。</li>
     * </ol>
     *
     * <p>代价是每次导出多一条连接 —— 导出是低频的显式动作,换来的是不拖住其它请求。
     *
     * @param database 目标库;空 = 用连接自带的库
     */
    public CkClient openDedicated(long id, String database) {
        // 取该连接的生效配置(密码/超时/隧道参数都在里面)。
        CkConfig cfg = cfg(id);
        // 指定了库、且与连接自带库不同时,复制配置换掉库名。
        String db = database == null ? "" : database.trim();
        if (!db.isEmpty() && !db.equals(cfg.getDatabase())) {
            // 库名会拼进 JDBC URL,同样要过标识符白名单。
            Sql.quoteIdent(db);
            // 复制而不是改原对象:配置是按连接缓存的,就地改会污染之后所有请求。
            cfg = cfg.copy();
            cfg.setDatabase(db);
        }
        try {
            // 建连失败统一转 502(与 client() 一致)。
            return CkClient.open(cfg);
        } catch (RuntimeException e) {
            throw ApiException.badGateway(Sql.messageOf(e));
        }
    }

    // =====================================================================
    // 配置组装 cfg()(带 TTL 缓存,对应 Go cfg())
    // =====================================================================

    /**
     * 组装某 ClickHouse 连接的生效参数:解密存储密码、解析高级配置,按
     * 连接覆盖 > 全局默认 > 内置默认 合并;结果缓存 5 分钟,Update/Delete 主动失效。
     * 连接不存在时报 404。
     */
    private CkConfig cfg(long id) {
        // 记录当前时刻(作为缓存判活与写回基准)
        long now = System.currentTimeMillis();
        // 取读锁查缓存
        lock.readLock().lock();
        // 缓存条目变量
        Entry e;
        long revision;
        try {
            // 按连接 ID 查缓存
            e = cfgCache.get(id);
            revision = cfgRevision;
        } finally {
            // 释放读锁
            lock.readLock().unlock();
        }
        // 缓存命中且未过期直接返回
        if (e != null && now < e.expires) {
            return e.cfg;
        }

        // 缓存失效:从仓库重读连接记录
        Connection c = repository.getConnection(id);
        // 连接不存在时报 404
        if (c == null) {
            throw ApiException.notFound("记录不存在");
        }
        // 解密后的密码
        String pwd;
        try {
            // 用全局密钥解密存储的密码密文
            pwd = Crypto.decrypt(secret, c.getPasswordEnc());
        } catch (RuntimeException ex) {
            // 解密失败视为下游错误 502
            throw ApiException.badGateway("连接密码解密失败: " + Sql.messageOf(ex));
        }
        // 读取全局默认高级配置
        AppSettings g = repository.getAppSettings();

        // 组装最终的 CkConfig(「连接级 > 全局 > 内置」的合并规则见 fromConnection)
        CkConfig cfg = fromConnection(c, pwd, g);

        // 读取期间若发生配置更新，只给在途调用返回本次快照，不把旧值写回共享缓存。
        lock.writeLock().lock();
        try {
            if (revision == cfgRevision) cfgCache.put(id, new Entry(cfg, now + CFG_CACHE_TTL_MS));
        } finally {
            // 释放写锁
            lock.writeLock().unlock();
        }
        return cfg;
    }

    // =====================================================================
    // DTO / 默认值 / 校验工具
    // =====================================================================

    /** 存储模型 → 对外 DTO(密码永不下传,仅以 hasPassword 标记是否已设置)。 */
    private ConnectionDTO toDTO(Connection c) {
        // 新建 DTO
        ConnectionDTO d = new ConnectionDTO();
        d.setId(c.getId());
        d.setName(c.getName());
        d.setHost(c.getHost());
        d.setPort(c.getPort());
        d.setDatabase(c.getDatabase());
        d.setUsername(c.getUsername());
        // 是否已设置密码(有非空密文即视为已设置)
        d.setHasPassword(c.getPasswordEnc() != null && !c.getPasswordEnc().isEmpty());
        d.setProtocol(c.getProtocol());
        d.setSecure(c.isSecure());
        d.setDescription(c.getDescription());
        d.setIsDefault(c.isDefault());
        d.setCreatedAt(c.getCreatedAt());
        d.setUpdatedAt(c.getUpdatedAt());
        d.setDialTimeoutMs(c.getDialTimeoutMs());
        d.setQueryTimeoutMs(c.getQueryTimeoutMs());
        d.setConnMaxLifetimeMin(c.getConnMaxLifetimeMin());
        d.setReadOnly(c.getReadOnly());
        d.setParamsJson(c.getParamsJson());
        return d;
    }

    /** 为新增连接补齐默认值(端口按协议选择 / 库 default / 用户 default / 密码空串等)。 */
    private static void fillDefaults(ConnectionInput in) {
        // 先归一化协议，再据此补端口：http 默认 8123，native 默认 9000。
        in.setProtocol(CkConfig.normalizeProtocol(in.getProtocol()));
        if (in.getPort() == null || in.getPort() <= 0) {
            in.setPort(CkConfig.defaultPort(in.getProtocol()));
        }
        // 数据库缺省补 default
        if (in.getDatabase() == null || in.getDatabase().isEmpty()) {
            in.setDatabase("default");
        }
        // 用户名缺省补 default
        if (in.getUsername() == null || in.getUsername().isEmpty()) {
            in.setUsername("default");
        }
        // secure 缺省补 false
        if (in.getSecure() == null) {
            in.setSecure(false);
        }
        // 描述缺省补空串
        if (in.getDescription() == null) {
            in.setDescription("");
        }
        // 密码缺省补空串
        if (in.getPassword() == null) {
            in.setPassword("");
        }
    }

    /** 更新场景:未填写的字段沿用旧记录值(密码由上层单独处理)。 */
    private static void fillDefaultsFrom(ConnectionInput in, Connection old) {
        // 名称未填则沿用旧值
        if (in.getName() == null || in.getName().isEmpty()) {
            in.setName(old.getName());
        }
        // 主机未填则沿用旧值
        if (in.getHost() == null || in.getHost().isEmpty()) {
            in.setHost(old.getHost());
        }
        // 旧客户端或部分更新没有协议字段时,保留已保存的 HTTP/native 配置。
        boolean protocolProvided = in.getProtocol() != null && !in.getProtocol().trim().isEmpty();
        String oldProtocol = CkConfig.normalizeProtocol(old.getProtocol());
        if (!protocolProvided) {
            in.setProtocol(oldProtocol);
        } else {
            in.setProtocol(CkConfig.normalizeProtocol(in.getProtocol()));
        }
        // 只改协议而未传端口时，切到新协议的标准端口；协议未变时仍保留原转发端口。
        if (in.getPort() == null || in.getPort() <= 0) {
            boolean protocolChanged = protocolProvided
                    && !CkConfig.normalizeProtocol(in.getProtocol()).equals(oldProtocol);
            in.setPort(protocolChanged ? CkConfig.defaultPort(in.getProtocol()) : old.getPort());
        }
        // 数据库未填则沿用旧值
        if (in.getDatabase() == null || in.getDatabase().isEmpty()) {
            in.setDatabase(old.getDatabase());
        }
        // 用户名未填则沿用旧值
        if (in.getUsername() == null || in.getUsername().isEmpty()) {
            in.setUsername(old.getUsername());
        }
        // secure 未填则沿用旧值
        if (in.getSecure() == null) {
            in.setSecure(old.isSecure());
        }
        // 描述未填则沿用旧值
        if (in.getDescription() == null) {
            in.setDescription(old.getDescription());
        }
        // 密码未填则置空串(由调用方决定是否沿用密文)
        if (in.getPassword() == null) {
            in.setPassword("");
        }
    }

    /** 连接入参基本校验:名称 / 主机 / 端口必填且合法。 */
    private static void validateConnectionInput(ConnectionInput in) {
        // 统一去除用户误输入的首尾空格,保证 API 直调用与前端提交语义一致
        if (in.getName() != null) {
            in.setName(in.getName().trim());
        }
        if (in.getHost() != null) {
            in.setHost(in.getHost().trim());
        }
        if (in.getDatabase() != null) {
            in.setDatabase(in.getDatabase().trim());
        }
        if (in.getUsername() != null) {
            in.setUsername(in.getUsername().trim());
        }
        // 名称必填
        if (in.getName() == null || in.getName().isEmpty()) {
            throw ApiException.validation("连接名称不能为空");
        }
        // 主机必填
        if (in.getHost() == null || in.getHost().isEmpty()) {
            throw ApiException.validation("主机地址不能为空");
        }
        // 端口必须处于 TCP 合法范围
        if (in.getPort() == null || in.getPort() < 1 || in.getPort() > 65535) {
            throw ApiException.validation("端口必须在 1-65535 之间");
        }
        try {
            CkConfig.requireCompatibleProtocolPort(in.getProtocol(), in.getPort());
        } catch (IllegalArgumentException e) {
            throw ApiException.validation(e.getMessage());
        }
    }

    /**
     * 必填项校验 —— 必须在补默认值【之前】调用。
     *
     * <p>⚠️ 为什么必须先于 fillDefaults / fillDefaultsFrom:前者会把空的 database/username 补成
     * {@code "default"},后者会从旧记录回填 —— 若把这几项必填校验放在它们之后,"必填"就形同虚设
     * (空值已被悄悄补上)。故这里直接对【原始入参】校验,并对纯空格的"假填写"一并拦截。
     *
     * @param requirePassword 新增连接为 true(密码必填);编辑为 false(留空 = 沿用原密码)
     */
    private static void validateRequiredFields(ConnectionInput in, boolean requirePassword) {
        // 先去掉误输入的首尾空格,避免"全是空格"混过必填
        if (in.getName() != null) {
            in.setName(in.getName().trim());
        }
        if (in.getHost() != null) {
            in.setHost(in.getHost().trim());
        }
        if (in.getUsername() != null) {
            in.setUsername(in.getUsername().trim());
        }
        if (in.getDatabase() != null) {
            in.setDatabase(in.getDatabase().trim());
        }
        if (in.getName() == null || in.getName().isEmpty()) {
            throw ApiException.validation("连接名称不能为空");
        }
        if (in.getHost() == null || in.getHost().isEmpty()) {
            throw ApiException.validation("主机地址不能为空");
        }
        if (in.getUsername() == null || in.getUsername().isEmpty()) {
            throw ApiException.validation("用户名不能为空");
        }
        if (in.getDatabase() == null || in.getDatabase().isEmpty()) {
            throw ApiException.validation("数据库不能为空");
        }
        // 密码只在新增时必填;编辑时留空表示沿用原密码
        if (requirePassword && (in.getPassword() == null || in.getPassword().isEmpty())) {
            throw ApiException.validation("密码不能为空");
        }
    }

    /** 空串兜底。 */
    private static String defaultStr(String s) {
        return s == null ? "" : s;
    }

    /** 可空引用回退到默认值。 */
    private static long deref(Long v, long def) {
        return v == null ? def : v;
    }

    // =====================================================================
    // CkConfig 组装:连接来源的统一入口
    // =====================================================================

    /**
     * 由「连接记录 + 全局设置」组装 ClickHouse 配置。
     *
     * <p>合并优先级统一为「连接级 &gt; 全局默认 &gt; 内置」,<b>全项目只此一处实现</b> ——
     * 本类的 {@link #cfg(long)}、{@code SyncEngine}、{@code Engine} 原先各自复制了一份,
     * 结果一致但改一处要动三遍,新增连接字段(如协议)时尤其容易漏。
     *
     * <p>只读标记在这里只做「连接覆盖全局」的透传,<b>是否据此拒绝操作由调用方按自身语义决定</b>:
     * 同步写入会拒绝(目标只读不该被写),查询与工作流不拒绝。
     *
     * @param c   连接记录
     * @param pwd 已解密的明文口令。解密刻意留在调用方,以便各站点抛出带连接名的错误文案
     * @param g   全局默认设置
     */
    public static CkConfig fromConnection(Connection c, String pwd, AppSettings g) {
        CkConfig cfg = new CkConfig();
        cfg.setHost(c.getHost());
        cfg.setPort(c.getPort());
        cfg.setProtocol(CkConfig.normalizeProtocol(c.getProtocol()));
        cfg.setDatabase(c.getDatabase());
        cfg.setUsername(c.getUsername());
        cfg.setPassword(pwd);
        cfg.setSecure(c.isSecure());
        // 建连超时:连接级 > 全局默认 > 内置 5000ms
        cfg.setDialTimeoutMs(deref(c.getDialTimeoutMs(), g.getDialTimeoutMs() == null ? 5000L : g.getDialTimeoutMs()));
        // 查询超时:连接级 > 全局默认 > 内置 60000ms
        cfg.setQueryTimeoutMs(deref(c.getQueryTimeoutMs(), g.getQueryTimeoutMs() == null ? 60000L : g.getQueryTimeoutMs()));
        // 连接最大存活:分钟换算为毫秒,连接级 > 全局默认 > 内置 30 分钟
        cfg.setConnMaxLifetimeMs(deref(c.getConnMaxLifetimeMin(),
                g.getConnMaxLifetimeMin() == null ? 30L : g.getConnMaxLifetimeMin()) * 60_000L);
        // 只读标记:连接显式配置则覆盖全局
        cfg.setReadOnly(c.getReadOnly() != null ? c.getReadOnly() : Boolean.TRUE.equals(g.getReadOnly()));
        cfg.setSettings(parseParamsJSON(resolveParamsJson(c, g)));
        return cfg;
    }

    /** 附加参数取连接自身的;连接未配置(空或 "{}")时回退全局。 */
    public static String resolveParamsJson(Connection c, AppSettings g) {
        String paramsJson = c.getParamsJson();
        if (paramsJson == null || paramsJson.isEmpty() || "{}".equals(paramsJson)) {
            return g.getParamsJson();
        }
        return paramsJson;
    }

    /**
     * 由表单入参组装「测试连接」用的配置。
     *
     * <p>与 {@link #fromConnection} 的区别是入参未经落库(没有 id、没有全局回退),
     * 超时一律用内置缺省。
     *
     * @param plainPassword 明文口令;null 按空串(编辑态留空表示沿用已保存口令,由调用方决定取值)
     */
    public static CkConfig fromInput(ConnectionInput in, String plainPassword) {
        CkConfig cfg = new CkConfig();
        cfg.setHost(in.getHost());
        cfg.setPort(in.getPort());
        cfg.setProtocol(CkConfig.normalizeProtocol(in.getProtocol()));
        cfg.setDatabase(in.getDatabase());
        cfg.setUsername(in.getUsername());
        cfg.setPassword(plainPassword == null ? "" : plainPassword);
        cfg.setSecure(in.getSecure() != null && in.getSecure());
        cfg.setDialTimeoutMs(deref(in.getDialTimeoutMs(), 5000L));
        cfg.setQueryTimeoutMs(deref(in.getQueryTimeoutMs(), 60000L));
        cfg.setConnMaxLifetimeMs(deref(in.getConnMaxLifetimeMin(), 30L) * 60_000L);
        cfg.setSettings(parseParamsJSON(in.getParamsJson()));
        return cfg;
    }

    /**
     * 确保参数是合法 JSON 对象;为空或非法则回退 "{}"。对应 Go normalizeParams。
     */
    public static String normalizeParams(String s) {
        // 空值直接返回空对象
        if (s == null || s.isEmpty()) {
            return "{}";
        }
        try {
            // 连接参数必须是 JSON 对象;数组/字符串等合法 JSON 也不能作为参数集
            Map<?, ?> object = JSON.readValue(s, Map.class);
            if (object == null) {
                return "{}";
            }
        } catch (Exception e) {
            // 非法 JSON 回退空对象
            return "{}";
        }
        // 合法则原样返回
        return s;
    }

    /**
     * 解析自定义 ClickHouse 参数:JSON → Map(数字尽量保留为整数)。对应 Go parseParamsJSON(UseNumber 语义)。
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseParamsJSON(String s) {
        // 保证返回结果顺序稳定
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        // 空值或空对象直接返回
        if (s == null || s.isEmpty() || "{}".equals(s)) {
            return out;
        }
        try {
            // 反序列化为原始 Map
            Map<String, Object> m = JSON.readValue(s, Map.class);
            // 遍历每个键值
            for (Map.Entry<String, Object> en : m.entrySet()) {
                Object v = en.getValue();
                // 数值类型需规整(去 .0 尾数)
                if (v instanceof Number) {
                    // 整数优先(去浮点 .0 尾数),小数保留浮点值
if (v instanceof Double || v instanceof Float) {
                        double d = ((Number) v).doubleValue();
                        // 恰为整数值的浮点按 long 保存
                        if (d == Math.rint(d) && !Double.isInfinite(d)) {
                            out.put(en.getKey(), (long) d);
                        } else {
                            // 真小数按 double 保存
                            out.put(en.getKey(), d);
                        }
                    } else {
                        // 整数按 long 保存
                        out.put(en.getKey(), ((Number) v).longValue());
                    }
                } else {
                    // 非数值原样保存
                    out.put(en.getKey(), v);
                }
            }
        } catch (Exception e) {
            // 解析失败回退为空参数集
}
        return out;
    }
}
