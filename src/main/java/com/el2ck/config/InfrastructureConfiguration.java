package com.el2ck.config;

/**
 * 【文件职责】装配元数据数据库、ClickHouse 连接池和 DuckDB 等底层共享资源，并定义其关闭顺序。
 */

import com.el2ck.ckpool.CkPool;
import com.el2ck.duck.Duck;
import com.el2ck.repository.metadata.MysqlMetadataDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * 基础设施装配:元数据数据源、事务管理器、DuckDB 与 ClickHouse 连接池。
 *
 * <p>装配职责原属已删除的 {@code WebBeans},按职责拆成四类后的其中一类 —— 这里只放
 * 「被业务服务共享的底层资源」,业务服务本身由 {@link ServiceConfiguration} 装配。
 *
 * <p>元数据侧固定走 MySQL(见 {@link MysqlMetadataDataSource}),不再有 SQLite 分支;
 * 已排除 Spring Boot 的 {@code DataSourceAutoConfiguration},故数据源必须在此显式声明。
 */
@Configuration(proxyBeanMethods = false)
@EnableTransactionManagement
public class InfrastructureConfiguration {

    /** 平台元数据固定使用配置的 MySQL 库;{@code open} 失败即启动失败,不做降级。 */
    @Bean(destroyMethod = "close")
    public MysqlMetadataDataSource metadataDataSource(AppConfig config) {
        return MysqlMetadataDataSource.open(config.getMetadata());
    }

    /** 元数据事务与 MyBatis 使用同一数据源，保证文件记账和恢复记录清理原子提交。 */
    @Bean
    public PlatformTransactionManager transactionManager(javax.sql.DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    // 装配 DuckDB 本地分析引擎;容器销毁时 Spring 会调用其 close 方法释放底层库
    @Bean(destroyMethod = "close")
    public Duck duck(AppConfig config) {
        // 创建 DuckDB 封装对象
        Duck duck = new Duck();
        // 以配置的数据目录打开 DuckDB 实例;打开失败则打印警告(文件解析/工作流功能不可用)
        if (!duck.open(config.getDataDir())) {
            System.out.println("[el-to-ck] 警告: DuckDB 初始化失败，文件解析与工作流功能不可用");
        }
        // 返回已打开的 DuckDB 实例
        return duck;
    }

    // 装配 ClickHouse 连接池 Bean(供连接管理/同步/设置/工作流等服务共享复用)
    @Bean
    public CkPool ckPool() {
        // 直接创建默认连接池实例
        return new CkPool();
    }

}
