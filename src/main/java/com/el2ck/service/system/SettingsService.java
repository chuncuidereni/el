/**
 * 【文件职责】本文件定义系统监控与设置模块的业务服务 SettingsService。承载该模块的核心业务规则，协调仓储、连接器或外部服务，并统一转换业务异常和操作状态。
 */

package com.el2ck.service.system;

import com.el2ck.service.connection.ConnectionService;

import com.el2ck.ckpool.CkPool;
import com.el2ck.entity.system.AppSettings;
import com.el2ck.exception.ApiException;
import com.el2ck.repository.metadata.MetadataRepository;
import com.el2ck.util.AfterCommit;
import org.springframework.transaction.annotation.Transactional;

/**
 * 全局默认高级配置服务(对应 Go internal/service/settings_service.go)。
 *
 * 提供「平台级默认」的连接高级参数(dial/query 超时、连接保持时长、默认只读、自定义参数),
 * 作为连接级高级覆盖的回退层。值保存在 MySQL app_settings 表。
 */
public class SettingsService {

    /** MySQL 元数据仓库：读写全局 app_settings 表。 */
    private final MetadataRepository repository;
    /** 连接池:全局默认配置变更时关闭全部已缓存客户端。 */
    private final CkPool pool;
    /** 连接服务:全局默认配置变更时让全部生效配置缓存失效。 */
    private final ConnectionService connectionService;
    /**
     * 创建系统设置业务服务并装配后续调用所需的依赖。
     *
     * 依赖由 Spring 容器统一管理；本构造器只保存引用或初始化轻量适配对象，不执行远程业务操作。
     *
     * @param repository 元数据仓储，用于读取或写入平台业务记录
     * @param pool ClickHouse 客户端池，用于管理共享连接资源
     * @param connectionService ClickHouse 连接服务，用于读取连接和校验配置
     */
    public SettingsService(MetadataRepository repository, CkPool pool,
                           ConnectionService connectionService) {
        // 保存元数据仓库引用
        this.repository = repository;
        // 保存连接池引用
        this.pool = pool;
        // 保存连接服务引用
        this.connectionService = connectionService;
    }

    /** 读取全局默认高级配置(缺失键由 MetadataRepository 填内置默认:dial 5000/query 60000/lifetime 30/readOnly false)。 */
    public AppSettings get() {
        // 直接返回仓库中的全局默认配置(缺失键由仓库自动补齐内置默认值)
        return repository.getAppSettings();
    }

    /**
     * 保存全局默认高级配置。
     * 非正数超时回退内置默认;params_json 强制为合法 JSON 对象。
     */
    @Transactional
    public void update(AppSettings in) {
        if (in == null) {
            throw ApiException.validation("请求内容不能为空");
        }
        // 以下默认值与 AppSettings.DEFAULT_*(及 MetadataRepository.getAppSettings 的内置基准)保持一致，
        // 否则「保存过一次」与「从未保存」会得到不同的生效配置。
        // dial 超时未设置或非正数时回退内置默认 5000ms
        if (in.getDialTimeoutMs() == null || in.getDialTimeoutMs() <= 0) {
            // 回填内置默认值
            in.setDialTimeoutMs(5000L);
        }
        // query 超时未设置或非正数时回退内置默认 60000ms
        if (in.getQueryTimeoutMs() == null || in.getQueryTimeoutMs() <= 0) {
            // 回填内置默认值
            in.setQueryTimeoutMs(60000L);
        }
        // 连接保持时长未设置或非正数时回退内置默认 30 分钟
        if (in.getConnMaxLifetimeMin() == null || in.getConnMaxLifetimeMin() <= 0) {
            // 回填内置默认值
            in.setConnMaxLifetimeMin(30L);
        }
        // 默认只读未指定时置为 false
        if (in.getReadOnly() == null) {
            // 显式落 false,保证读取端拿到的不是 null
            in.setReadOnly(false);
        }
        // 自定义参数强制归一化为合法 JSON 对象(不合法则退化为 "{}")
        in.setParamsJson(ConnectionService.normalizeParams(in.getParamsJson()));
        // 将清洗后的全局默认配置写入元数据库
        repository.setAppSettings(in);
        // 全局默认配置是每条生效连接配置的一部分:关闭全部已缓存客户端,
        // 避免后续请求继续沿用旧的超时/只读值。
        AfterCommit.run(() -> {
            // 先失效配置，再关闭旧客户端；事务回滚时两者均保持原状。
            connectionService.invalidateAllCfg();
            pool.closeAll();
        });
    }
}
