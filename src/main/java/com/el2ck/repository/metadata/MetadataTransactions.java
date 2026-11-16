package com.el2ck.repository.metadata;

import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/** 保护依赖“取锁后读取最新已提交数据”的元数据操作，防止外层旧快照事务悄悄改变语义。 */
public final class MetadataTransactions {
    private MetadataTransactions() { }

    public static void requireReadCommitted(DataSource database, String operation) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException(operation + "必须在 READ_COMMITTED 事务中执行");
        Connection connection = DataSourceUtils.getConnection(database);
        try {
            // @Transactional 加入既有事务时不会切换隔离级别，因此核对实际连接，而非只看注解。
            if (connection.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED)
                throw new IllegalStateException(operation + "必须在 READ_COMMITTED 事务中执行");
        } catch (SQLException e) { throw new IllegalStateException("无法确认" + operation + "事务隔离级别", e); }
        finally { DataSourceUtils.releaseConnection(connection, database); }
    }
}
