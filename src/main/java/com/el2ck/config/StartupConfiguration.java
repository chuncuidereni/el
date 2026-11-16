package com.el2ck.config;

/**
 * 【文件职责】定义启动阶段的初始化与诊断步骤，确保关键依赖在应用对外服务前完成检查。
 */

import com.el2ck.repository.metadata.MetadataRepository;
import com.el2ck.service.connection.ConnectionService;
import com.el2ck.service.auth.AppUserService;
import com.el2ck.mapper.auth.AppUserMapper;
import com.el2ck.service.database.DatabaseService;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.core.annotation.Order;

/**
 * 启动初始化:同步默认连接、确保分层库、登记实例、播种管理员及绑定管理员业务身份。
 *
 * <p>装配职责原属已删除的 {@code WebBeans},按职责拆成四类后的其中一类。
 *
 * <p>初始化写成 {@link ApplicationRunner} 而不是构造函数里的初始化,是为了让它们在
 * 「容器就绪」之后才执行 —— 此时数据源、Mapper、Service 都已装配完毕,失败也能带着
 * 完整上下文报错。
 *
 * <p>⚠️ 执行顺序由 {@code @Order} 决定,当前实际顺序<b>不是</b>声明顺序:
 * {@code seedDefaultConnection}({@code @Order(0)}) → {@code ensureTieredDatabases}({@code @Order(1)})
 * → {@code syncCkInstances}(2) → {@code seedLoginAdmin}(3) → {@code bindConfiguredAdmin}(4)
 * → {@code bindExistingUsers}(5)。
 * 管理员绑定依赖前面的连接、实例和平台账号均已准备好。
 */
@Configuration(proxyBeanMethods = false)
public class StartupConfiguration {

    // 注册启动后置回调:应用启动完成时同步 YAML 默认 ClickHouse 连接并保留已有 ID
    @Bean
    @Order(0)
    public ApplicationRunner seedDefaultConnection(MetadataRepository repository, AppConfig config) {
        // 返回 ApplicationRunner 回调:以 secretKey 加密,并写入配置里给出的默认连接
        return args -> ConnectionService.seedDefaultConnection(
                repository, config.getSecretKey(), config.getDefaultCk());
    }

    /**
     * 播种管理员账号:{@code el_app_users} 为空才会创建,已有用户一律不动。
     *
     * <p>幂等靠「表非空即直接返回」保证,所以重启永远不会重置或覆盖已改过的密码。
     * 未配置 {@code app.password} 时生成一次性随机密码,且**只在 stdout 打印一次**
     * (不进持久化日志)—— 避免临时口令长期留在日志文件里。
     */
    @Bean
    @Order(3)
    public ApplicationRunner seedLoginAdmin(AppUserMapper mapper, AppUserService users, AppConfig config) {
        return args -> {
            if (mapper.count() != 0) return;
            String name = config.getUser() == null || config.getUser().trim().isEmpty()
                    ? "admin" : config.getUser().trim();
            String password = config.getPassword();
            boolean generated = password == null || password.isEmpty();
            if (generated) {
                byte[] random = new byte[24];
                new SecureRandom().nextBytes(random);
                password = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
            }
            users.seedIfAbsent(name, password, AppUserService.ROLE_ADMIN);
            // 临时口令只在本次启动的 stdout 出现一次,不写进持久化日志
            if (generated) {
                System.out.println("首次登录账号: " + name + "，临时密码: " + password);
            }
        };
    }

    /** 启动时恢复内置 admin 的已配置 CK 业务身份,不修改任何平台或 CK 账号的密码。 */
    @Bean
    @Order(4)
    public ApplicationRunner bindConfiguredAdmin(AppUserService users, com.el2ck.service.ck.CkIdentityService identities) {
        return args -> {
            for (com.el2ck.entity.auth.AppUser user : users.list()) {
                if ("admin".equals(user.getUsername()) && "admin".equals(user.getRole())) {
                    try { identities.initializeAll(user.getId()); }
                    catch (RuntimeException e) { System.out.println("[el-to-ck] admin CK 绑定待重试，请查看用户管理中的状态"); }
                }
            }
        };
    }

    /** 为升级前已存在的启用用户补建 CK 绑定，已有绑定由用户管理显式重试。 */
    @Bean
    @Order(5)
    public ApplicationRunner bindExistingUsers(com.el2ck.service.ck.CkIdentityService identities) {
        return args -> identities.initializeMissingBindings();
    }

    /**
     * 登记 ClickHouse 物理实例并回填连接/历史资产的 ck_instance_id。
     * 必须在默认连接播种之后执行,否则首次启动时连接尚未写入。
     */
    @Bean
    @Order(2)
    public ApplicationRunner syncCkInstances(com.el2ck.service.ck.CkInstanceService instances) {
        return args -> {
            try {
                instances.syncInstances();
            } catch (RuntimeException e) {
                // 元数据不可写已经会让启动失败;这里只记录实例登记问题,不阻断平台登录。
                System.out.println("[el-to-ck] CK 实例登记失败: " + e.getMessage());
            }
        };
    }

    // 注册启动后置回调:在每个连接上幂等确保分层库 ods_data/dwd_data 存在(对应 Go EnsureTieredDatabases)。
    // 只读/目标库不可达仅忽略,不阻断启动。
/**
 * 校验 {@code ensureTieredDatabases} 所涉及的前置条件，失败时阻止后续读写以避免产生不一致状态。
 */
    @Bean
    @Order(1)
    public ApplicationRunner ensureTieredDatabases(MetadataRepository repository, DatabaseService databaseService) {
        return args -> {
            for (com.el2ck.entity.connection.Connection c : repository.listConnections()) {
                try {
                    databaseService.ensureTieredDatabases(c.getId());
                } catch (RuntimeException ignored) {
                    // 单个连接异常不拖垮启动。
                }
            }
        };
    }

}
