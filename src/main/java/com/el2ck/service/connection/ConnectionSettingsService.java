package com.el2ck.service.connection;

import com.el2ck.config.AuthContext;
import com.el2ck.entity.connection.ConnectionDTO;
import com.el2ck.entity.connection.ConnectionSettingsInput;
import com.el2ck.exception.ApiException;
import com.el2ck.service.system.SettingsService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 连接编辑页选择“全局默认”时的事务入口。
 * 全局配置五个键和当前连接共享同一元数据库事务，任一步校验或写入失败都一起回滚；
 * 各服务的缓存清理在提交后执行，回滚不会关闭正在使用旧配置的客户端。
 */
@Service
public class ConnectionSettingsService {
    private final ConnectionService connections;
    private final SettingsService settings;

    public ConnectionSettingsService(ConnectionService connections, SettingsService settings) {
        this.connections = connections;
        this.settings = settings;
    }

    /** 保存已有连接及全局默认；普通连接编辑/新建仍使用原接口。 */
    @Transactional
    public ConnectionDTO update(long id, ConnectionSettingsInput input) {
        AuthContext.userId();
        if (!"admin".equals(AuthContext.get().getRole())) throw new ApiException(403, "仅管理员可修改连接和全局配置");
        if (input == null || input.getConnection() == null || input.getGlobalSettings() == null)
            throw ApiException.validation("请同时提供连接配置和全局高级参数");
        if (!"global".equals(input.getConnection().getAdvancedScope()))
            throw ApiException.validation("同时保存全局参数时，连接高级配置作用域必须为 global");

        settings.update(input.getGlobalSettings());
        // 包括连接不存在、名称冲突和端口错误；异常向事务边界传播，不吞掉后续写入失败。
        return connections.update(id, input.getConnection());
    }
}
