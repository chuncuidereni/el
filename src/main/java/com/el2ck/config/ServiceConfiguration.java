package com.el2ck.config;

/**
 * 【文件职责】集中装配业务 Service，并明确它们对仓储、连接池、DuckDB 和身份服务的依赖。
 */

import com.el2ck.ckpool.CkPool;
import com.el2ck.duck.Duck;
import com.el2ck.repository.metadata.MetadataRepository;
import com.el2ck.service.connection.ConnectionService;
import com.el2ck.service.datasource.DataSourceService;
import com.el2ck.service.database.DatabaseService;
import com.el2ck.service.query.QueryService;
import com.el2ck.service.system.SettingsService;
import com.el2ck.service.sync.SyncService;
import com.el2ck.service.table.TableGroupService;
import com.el2ck.service.table.TableService;
import com.el2ck.service.user.UserService;
import com.el2ck.service.workflow.WorkflowService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.io.File;

/**
 * 业务服务装配:连接、库表、查询、同步、工作流、用户等各模块 Service。
 *
 * <p>装配职责原属已删除的 {@code WebBeans},按职责拆成四类后的其中一类 —— 底层共享资源
 * (数据源/连接池/DuckDB)由 {@link InfrastructureConfiguration} 提供,本类只做业务服务的
 * 构造注入。全部使用显式构造器注入(不用字段注入),依赖关系在编译期即可见。
 */
@Configuration(proxyBeanMethods = false)
public class ServiceConfiguration {

    // 装配连接管理服务(负责 ClickHouse 连接的增删改查与连接测试)
    @Bean
    public ConnectionService connectionService(MetadataRepository repository,
                                               CkPool pool,
                                               AppConfig config) {
        // 显式构造注入仓库、连接池与解密密钥
        return new ConnectionService(repository, pool, config.getSecretKey());
    }

    // 装配数据库列表服务(依赖连接管理服务列出库表)
    @Bean
    public DatabaseService databaseService(ConnectionService connectionService,
                                           com.el2ck.service.ck.CkIdentityService identities,
                                           com.el2ck.service.table.TableReferenceService tableReferences) {
        // 列表使用当前用户身份,建库/删库使用管理身份
        return new DatabaseService(connectionService, identities, tableReferences);
    }

    // 装配表信息服务(依赖连接管理服务列出指定库的表,以及表分组服务做删除/改名的归属联动)
    @Bean
    public TableService tableService(ConnectionService connectionService, TableGroupService tableGroupService,
                                     com.el2ck.service.table.TableReferenceService tableReferenceService,
                                     com.el2ck.service.ck.CkIdentityService identities,
                                     com.el2ck.service.ck.TableLifecycleService lifecycle) {
        // 用户业务连接与受控表生命周期
        return new TableService(connectionService, tableGroupService, tableReferenceService, identities, lifecycle);
    }

    // 装配查询服务(依赖连接管理服务执行 SQL 查询)
    @Bean
    public QueryService queryService(ConnectionService connectionService,
                                     com.el2ck.service.ck.CkIdentityService identities,
                                     com.el2ck.service.table.TableReferenceService tableReferences) {
        // 任意 SQL 使用当前用户的真实 CK 身份执行
        return new QueryService(connectionService, identities, tableReferences);
    }

    // 装配用户服务(依赖连接管理服务与元数据仓库管理登录用户)
    @Bean
    public UserService userService(ConnectionService connectionService,
                                   MetadataRepository repository,
                                   com.el2ck.service.ck.CkIdentityService identities) {
        // 显式构造注入连接管理服务、元数据仓库与 CK 管理身份
        return new UserService(connectionService, repository, identities);
    }

    // 装配数据源服务(依赖元数据仓库、DuckDB 与上传目录管理本地文件数据源)
    @Bean
    public DataSourceService dataSourceService(MetadataRepository repository,
                                               Duck duck,
                                               AppConfig config,
                                               com.el2ck.service.datasource.SourceObservationService observations) {
        // 显式构造注入,并计算上传目录的绝对路径作为参数
        return new DataSourceService(repository, duck, config.getSecretKey(), uploadsDirectory(config),observations);
    }

    // 装配设置服务(依赖元数据仓库、连接池与连接管理服务读写系统设置)
    @Bean
    public SettingsService settingsService(MetadataRepository repository,
                                           CkPool pool,
                                           ConnectionService connectionService) {
        // 显式构造注入仓库、连接池与连接管理服务
        return new SettingsService(repository, pool, connectionService);
    }

    // 装配数据同步服务(依赖元数据仓库、连接池与 DuckDB 完成数据抽取与写入)
    @Bean
    public SyncService syncService(MetadataRepository repository,
                                   CkPool pool,
                                   Duck duck,
                                   AppConfig config,
                                   DataSourceService dataSourceService,
                                   com.el2ck.service.ck.CkIdentityService identities,
                                   com.el2ck.service.ck.TableLifecycleService lifecycle) {
        // 遗留记录由 SyncExecutionGuard 取得对应任务锁后恢复，启动不修改其他实例的 running。
        // 显式构造注入仓库、连接池、DuckDB 与解密密钥
        return new SyncService(repository, pool, duck, config.getSecretKey(), dataSourceService, identities, lifecycle, config);
    }

    // 装配工作流服务(依赖元数据仓库、连接池与 DuckDB 编排多步工作流)
    @Bean
    public WorkflowService workflowService(MetadataRepository repository,
                                           CkPool pool,
                                           Duck duck,
                                           AppConfig config,
                                           com.el2ck.service.ck.CkIdentityService identities) {
        // 工作流写入使用任务所有者绑定的真实 CK 身份
        return new WorkflowService(repository, pool, duck, config.getSecretKey(), identities);
    }

    // 计算上传目录绝对路径:未配置数据目录时回退到 ./data 下的 uploads 子目录
    private static String uploadsDirectory(AppConfig config) {
        // 数据目录为空时回退到默认的 ./data
        String dataDirectory = config.getDataDir() == null || config.getDataDir().trim().isEmpty()
                ? "./data" : config.getDataDir();
        // 由数据目录拼出上传子目录
        File uploads = new File(dataDirectory, "uploads");
        // 目录不存在则创建;失败仅打印警告,不影响启动
        if (!uploads.exists() && !uploads.mkdirs()) {
            System.out.println("[el-to-ck] 警告: 创建上传目录失败: " + uploads);
        }
        // 返回上传目录的绝对路径字符串
        return uploads.getAbsolutePath();
    }
}
