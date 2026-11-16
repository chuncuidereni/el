/**
 * 【文件职责】本文件定义ck模块的访问状态核对服务 CkAccessReconciler。负责 ClickHouse 用户/授权变更及其状态核对，调用失败时保留可重试或审计所需的操作状态。
 */

package com.el2ck.service.ck;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

/**
 * CK 账号与授权操作的持久化对账。
 *
 * <p>元数据库与 ClickHouse 没有跨库事务,共享/撤权/禁用可能中断在一半。这里按操作状态
 * 周期重试:撤权(revoking)必须收敛,失败的共享(pending)重新尝试授权,禁用(disabling)
 * 重试凭据轮换。任何失败只记录,不把平台状态提前改成成功。
 */
@Component
public class CkAccessReconciler {

    private static final Logger LOG = LoggerFactory.getLogger(CkAccessReconciler.class);

    private final JdbcTemplate jdbc;
    private final CkAccessProvisioner provisioner;
    private final CkIdentityService identities;

    /**
     * 注入元数据库、表级授权供给器和身份管理服务。
     *
     * @param ds 平台元数据库
     * @param provisioner 表共享/撤权执行器
     * @param identities 用户 CK 身份禁用重试执行器
     */
    public CkAccessReconciler(DataSource ds, CkAccessProvisioner provisioner, CkIdentityService identities) {
        this.jdbc = new JdbcTemplate(ds);
        this.provisioner = provisioner;
        this.identities = identities;
    }

    /**
     * 周期重试所有跨系统操作中尚未收敛的 CK 状态。
     *
     * <p>调度异常由各子步骤隔离；单个对象失败只记日志，不阻断后续对象。
     */
    @Scheduled(initialDelay = 30_000, fixedDelayString = "${app.ck-access-retry-ms:60000}")
    public void reconcile() {
        retryDisabling();
        retryRevoking();
        retryPendingGrants();
    }

    /**
     * 重试处于 disabling 状态的用户 CK 凭据禁用。
     *
     * <p>每个用户独立捕获运行时异常，下一调度周期仍可继续重试。
     */
    private void retryDisabling() {
        List<Long> users = jdbc.queryForList("SELECT DISTINCT user_id FROM el_user_ck_bindings WHERE status='disabling'", Long.class);
        for (long userId : users) {
            try {
                identities.retryDisabling(userId);
            } catch (RuntimeException e) {
                LOG.warn("CK 禁用重试失败 userId={}: {}", userId, CkIdentityService.safeMessage(e));
            }
        }
    }

    /**
     * 重试处于 revoking 状态的 CK 表读取授权撤销。
     */
    private void retryRevoking() {
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT g.resource_id,g.grantee_user_id,g.expected_version FROM el_resource_grants g"
                        + " JOIN el_data_assets a ON a.id=g.resource_id"
                        + " WHERE g.resource_type='table' AND g.status='revoking'"
                        + " AND a.backend_type='clickhouse' AND a.ck_instance_id IS NOT NULL")) {
            long assetId = num(row.get("resource_id"));
            long userId = num(row.get("grantee_user_id"));
            long version = num(row.get("expected_version"));
            try {
                provisioner.revokeRead(assetId, userId, version);
            } catch (RuntimeException e) {
                LOG.warn("CK 撤权重试失败 asset={} user={}: {}", assetId, userId, CkIdentityService.safeMessage(e));
            }
        }
    }

    /**
     * 重试 active 资产上仍处于 pending 的 CK 读取授权。
     */
    private void retryPendingGrants() {
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT g.resource_id,g.grantee_user_id,g.expected_version FROM el_resource_grants g"
                        + " JOIN el_data_assets a ON a.id=g.resource_id"
                        + " WHERE g.resource_type='table' AND g.status='pending' AND a.status='active'"
                        + " AND a.backend_type='clickhouse' AND a.ck_instance_id IS NOT NULL")) {
            long assetId = num(row.get("resource_id"));
            long userId = num(row.get("grantee_user_id"));
            long version = num(row.get("expected_version"));
            try {
                provisioner.grantRead(assetId, userId, version);
            } catch (RuntimeException e) {
                LOG.warn("CK 共享重试失败 asset={} user={}: {}", assetId, userId, CkIdentityService.safeMessage(e));
            }
        }
    }

    /**
     * 将 JDBC 返回的数值或数值字符串转换为 long。
     *
     * @param v 原值
     * @return 长整数值
     */
    private static long num(Object v) {
        return v instanceof Number ? ((Number) v).longValue() : Long.parseLong(String.valueOf(v));
    }
}
