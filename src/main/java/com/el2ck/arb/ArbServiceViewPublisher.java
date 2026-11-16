package com.el2ck.arb;

import com.el2ck.entity.arb.ArbWorkflow;
import com.el2ck.util.Sql;

/**
 * 以“临时对象、零行验证、原子换名”的流程发布 ARB 服务视图。
 *
 * <p>服务视图可能替换旧 VIEW，也可能替换历史 sum 分支留下的物理 TABLE。发布前直接删除正式
 * 对象会在新 DDL、源列或类型有误时清空正在使用的服务层。本类先把新定义创建到独立临时名称，
 * 执行零行查询验证，再通过 Atomic 数据库的单对 {@code EXCHANGE TABLES} 原子交换正式对象和临时
 * 对象；目标不存在时使用单对象 {@code RENAME TABLE}。
 *
 * <p>ClickHouse 的多对象 {@code RENAME TABLE a TO b, c TO d} 可能部分执行，不能用来实现本流程。
 * 因此本类只使用官方保证原子的单对 {@code EXCHANGE TABLES}，或只涉及一个对象的
 * {@code RENAME TABLE}。
 *
 * <p>本类只依赖 {@link Gateway}，便于用内存 fake 覆盖每个失败点。生产适配器由
 * {@link ArbEngine} 使用现有 {@code CkClient} 和 {@code RunRecorder} 提供，不额外建立连接，
 * 也不会绕开既有超时、错误包装和运行 SQL 记录。
 */
final class ArbServiceViewPublisher {

    /**
     * 发布所需的 ClickHouse 操作。
     *
     * <p>{@link #execute}、{@link #validate} 和 {@link #databaseEngine} 的异常必须原样抛给发布器；
     * {@link #cleanup} 只能尽力删除对象，不得用清理异常覆盖发布流程的原始异常。
     */
    interface Gateway {
        /** 查询对象是否存在；普通 VIEW 和物理 TABLE 都算存在。 */
        boolean exists(String database, String table);

        /** 返回数据库引擎，用于确认 {@code EXCHANGE TABLES} 的原子性保证成立。 */
        String databaseEngine(String database);

        /** 执行 DDL；失败时抛出运行时异常。 */
        void execute(String sql, String errorPrefix);

        /** 执行零行只读验证；失败时抛出运行时异常。 */
        void validate(String sql, String errorPrefix);

        /** 尽力删除指定 VIEW 或 TABLE；实现必须吞掉并记录清理失败。 */
        void cleanup(String database, String table);
    }

    private ArbServiceViewPublisher() {
    }

    /**
     * 安全发布服务视图。
     *
     * <p>流程如下：
     * <ol>
     *   <li>确认目标库使用 Atomic 引擎；</li>
     *   <li>清理同名临时对象残留；</li>
     *   <li>在临时名称创建新 VIEW；</li>
     *   <li>执行 {@code SELECT * FROM temp LIMIT 0} 验证查询体；</li>
     *   <li>目标存在时执行单对 {@code EXCHANGE TABLES target AND temp}，使新 VIEW 原子接管正式名称，
     *       旧 VIEW/TABLE 移到临时名称；目标不存在时执行单对象 {@code RENAME TABLE temp TO target}；</li>
     *   <li>交换成功后清理临时名称中的旧对象；清理失败只记录警告，不把已成功发布改为失败。</li>
     * </ol>
     *
     * <p>创建、验证或换名失败时只清理临时对象，不主动删除正式目标。Atomic 的单对 EXCHANGE 和
     * 单对象 RENAME 在服务器返回失败时不会留下半完成的名称变更，因此旧目标仍可继续查询。
     *
     * @param workflow 工作流配置
     * @param temporaryTable 本次发布独占的临时 VIEW 名
     * @param gateway ClickHouse 操作适配器
     */
    static void publish(ArbWorkflow workflow, String temporaryTable, Gateway gateway) {
        String database = workflow.getTargetDb();
        String target = workflow.svcTableName();
        String db = Sql.quoteCol(database);
        String temp = db + "." + Sql.quoteCol(temporaryTable);
        String targetQ = db + "." + Sql.quoteCol(target);

        // EXCHANGE TABLES 的原子性由 Atomic 数据库保证。必须在创建临时对象前拒绝其它引擎，
        // 否则后续只能退化为可能暴露短暂缺口的非原子流程。
        String engine = gateway.databaseEngine(database);
        if (!"Atomic".equals(engine)) {
            throw new IllegalStateException("服务视图原子发布要求目标库使用 Atomic 引擎，当前引擎: " + engine);
        }

        // 临时名由调用方按工作流和随机 token 生成。这里仍处理上一次进程异常退出留下的同名对象，
        // 防止 CREATE VIEW 因残留对象失败。此时正式目标尚未被改动。
        if (gateway.exists(database, temporaryTable)) {
            gateway.cleanup(database, temporaryTable);
        }

        // 目标是否存在只决定最终使用 EXCHANGE 还是 RENAME；旧 VIEW 与旧物理 TABLE 走同一安全路径。
        boolean targetExists = gateway.exists(database, target);
        String ddl = ArbPlanBuilder.buildSvcViewDdl(workflow, temporaryTable);

        try {
            gateway.execute(ddl, "建临时服务视图失败(旧服务对象未受影响)");
            gateway.validate("SELECT * FROM " + temp + " LIMIT 0",
                    "验证临时服务视图失败(旧服务对象未受影响)");

            if (targetExists) {
                // 单对 EXCHANGE 要么把 target/temp 的名称同时交换，要么都不变。不能改成
                // RENAME TABLE target TO backup, temp TO target，因为 ClickHouse 多对象 RENAME 不是原子的。
                gateway.execute("EXCHANGE TABLES " + targetQ + " AND " + temp,
                        "原子替换服务对象失败(旧服务对象未受影响)");
            } else {
                // 只涉及一个对象的 RENAME 可以作为“目标首次发布”的原子提交点。
                gateway.execute("RENAME TABLE " + temp + " TO " + targetQ,
                        "创建服务视图失败");
            }
        } catch (RuntimeException failure) {
            // 正常 SQL 失败时正式目标保持不变：EXCHANGE/RENAME 尚未提交，临时对象仍位于 temp。
            // 清理采用尽力而为策略，保证这里向调用方传播原始发布异常。
            cleanupQuietly(gateway, database, temporaryTable);
            throw failure;
        }

        if (targetExists) {
            // EXCHANGE 成功后 temp 指向旧 VIEW/TABLE。清理失败只会留下带随机 token 的残留对象，
            // 不能因此把已经成功接管正式名称的新 VIEW 报告为失败。
            cleanupQuietly(gateway, database, temporaryTable);
        }
    }

    /** 尽力清理临时对象；清理异常不能覆盖发布成功或最初的失败原因。 */
    private static void cleanupQuietly(Gateway gateway, String database, String table) {
        try {
            gateway.cleanup(database, table);
        } catch (RuntimeException ignored) {
            // 生产适配器会记录清理警告；此处兜底，保证清理异常不改变发布结果。
        }
    }
}