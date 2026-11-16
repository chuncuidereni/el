/**
 * 【文件职责】本文件定义系统监控与设置模块的业务服务 MonitorService。承载该模块的核心业务规则，协调仓储、连接器或外部服务，并统一转换业务异常和操作状态。
 */

package com.el2ck.service.system;

import com.el2ck.service.connection.ConnectionService;

import com.el2ck.ckpool.CkClient;
import com.el2ck.ckpool.QueryResult;
import com.el2ck.entity.system.DiskStat;
import com.el2ck.entity.system.ServerStats;
import com.el2ck.exception.ApiException;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 采集「目标库服务」的运行指标(内存 / CPU / 磁盘 / 连接),
 * 对应 Go {@code internal/service/monitor_service.go}。
 *
 * <p>与 {@link SystemStatsService} 互补:那个采平台进程自身占用,这个采目标库服务的。
 * 全部只读、不落库;指标读自目标库自身的运行状态视图。
 */
@Service
public class MonitorService {

    private static final double BYTES_PER_MB = 1024 * 1024;
    private static final double BYTES_PER_GB = 1024 * BYTES_PER_MB;

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    /**
     * 运行状态指标名。取不到的指标在 map 里就是 0 ——
     * 所以凡是「0 等于未知」的项,一律用 -1 表示未知(见 {@link #ratioPct}),不要拿 0 当真实值展示。
     */
    private static final String M_MEM_RESIDENT = "MemoryResident";
    private static final String M_OS_MEM_TOTAL = "OSMemoryTotal";
    private static final String M_OS_MEM_AVAIL = "OSMemoryAvailable";
    private static final String M_TCP_CONN = "TCPConnection";
    private static final String M_HTTP_CONN = "HTTPConnection";
    private static final String M_ISECT_CONN = "InterserverConnection";
    private static final String M_QUERY = "Query";
    private static final String M_MERGE = "Merge";
    private static final String M_UPTIME = "Uptime";

    /**
     * 运行状态指标分两条查询读取。
     *
     * <p>⚠️ 不要 UNION ALL 成一条:两张视图的 value 列类型不同(整型 / 浮点),
     * 服务端合并后会把该列变成 Variant,驱动取回的是 {@code {1 Int64}} 这样的文本 ——
     * 数值全部解析成 0,而查询本身【不报错】,属于最难查的一类错。
     * (2026-09 实测:合并后第一行 value 就是 {@code {1 Int64}})
     */
    private static final String SQL_METRICS = "SELECT metric, value FROM system.metrics";
    private static final String SQL_ASYNC_METRICS = "SELECT metric, value FROM system.asynchronous_metrics";
    /** ClickHouse 的 ACCESS_DENIED、UNKNOWN_TABLE、UNKNOWN_IDENTIFIER 错误码。 */
    private static final int CK_ACCESS_DENIED = 497;
    private static final int CK_UNKNOWN_TABLE = 60;
    private static final int CK_UNKNOWN_IDENTIFIER = 47;

    /** 部分驱动只把服务端错误码写在消息里，SQLException.getErrorCode() 可能为 0。 */
    private static final Pattern CK_ERROR_CODE = Pattern.compile("(?i)\\bCode:\\s*(\\d+)");

    /** 服务自身的内存上限配置。 */
    private static final String SQL_SERVER_MEM =
            "SELECT name, value FROM system.server_settings"
                    + " WHERE name IN ('max_server_memory_usage', 'max_server_memory_usage_to_ram_ratio')";

    /**
     * 整机 CPU 时间指标。
     *
     * <p>⚠️ 这六个【不是累计量】:实测每个采样值都是「最近一个采样窗口内的 CPU 秒数」,
     * 六个加起来恒等于核数(16 核的机器上,四次采样合计都是 15.97~16.00)。
     * 所以不能像进程 CPU 那样取两次采样的差值 —— 那是在对两个独立窗口再做差,
     * 结果会在 0 附近抖动甚至为负。必须直接在【同一次采样】里求 忙/总。
     * 好处:比值与单位、与采样窗口长度都无关,跨版本口径变化也不会算错。
     */
    private static final List<String> CPU_TIME_METRICS = Arrays.asList(
            "OSUserTime", "OSSystemTime", "OSNiceTime",
            "OSIdleTime", "OSIOWaitTime", "OSStealTime");

    /** 分子:计入「忙」的那几项。 */
    private static final List<String> CPU_BUSY_METRICS =
            Arrays.asList("OSUserTime", "OSSystemTime", "OSNiceTime");

    private final ConnectionService connSvc;
    private final com.el2ck.service.ck.CkIdentityService identities;
    /**
     * 创建运行监控查询服务并装配后续调用所需的依赖。
     *
     * 依赖由 Spring 容器统一管理；本构造器只保存引用或初始化轻量适配对象，不执行远程业务操作。
     *
     * @param connSvc ClickHouse 连接服务，负责解析连接并按执行身份创建客户端
     * @param identities ClickHouse 用户身份服务，用于按任务所有者或当前用户解析执行身份
     */
    public MonitorService(ConnectionService connSvc, com.el2ck.service.ck.CkIdentityService identities) {
        this.connSvc = connSvc;
        this.identities = identities;
    }

    /** 采集一次目标库服务指标。 */
    public ServerStats serverStats(long connId) {
        CkClient client = identities.managementClient(connId);
        List<String> warnings = new ArrayList<>();
        Map<String, Double> metrics;
        List<DiskStat> disks;
        double memLimitMB;
        try {
            metrics = collectMetrics(client, warnings);
            disks = collectDisks(client, warnings);
            memLimitMB = collectMemLimitMB(client, mbValue(metrics, M_OS_MEM_TOTAL), warnings);
        } catch (RuntimeException e) {
            throw ApiException.badGateway("读取目标库监控失败: " + com.el2ck.util.Sql.messageOf(e));
        }

        ServerStats out = new ServerStats();
        out.setTime(LocalTime.now().format(TIME_FORMAT));
        out.setWarnings(warnings);
        out.setUptimeSec((long) value(metrics, M_UPTIME));

        out.setMemResidentMB(mbValue(metrics, M_MEM_RESIDENT));
        out.setOsMemTotalMB(mbValue(metrics, M_OS_MEM_TOTAL));
        out.setOsMemAvailMB(mbValue(metrics, M_OS_MEM_AVAIL));

        out.setTcpConnections((long) value(metrics, M_TCP_CONN));
        out.setHttpConnections((long) value(metrics, M_HTTP_CONN));
        out.setIsectConnections((long) value(metrics, M_ISECT_CONN));
        out.setTotalConnections(out.getTcpConnections() < 0 || out.getHttpConnections() < 0
                || out.getIsectConnections() < 0 ? -1
                : out.getTcpConnections() + out.getHttpConnections() + out.getIsectConnections());
        out.setRunningQueries((long) value(metrics, M_QUERY));
        out.setRunningMerges((long) value(metrics, M_MERGE));

        out.setDisks(disks);
        out.setCpuPercent(cpuPercentOf(metrics));
        out.setMemLimitMB(memLimitMB);
        out.setMemOfLimitPct(ratioPct(out.getMemResidentMB(), out.getMemLimitMB()));
        out.setMemOfOSTotalPct(ratioPct(out.getMemResidentMB(), out.getOsMemTotalMB()));
        return out;
    }

    /**
     * 由【一次】采样算出整机 CPU 使用率。指标缺失时返回 -1(前端显示 --)。
     */
    static double cpuPercentOf(Map<String, Double> m) {
        double busy = 0;
        double total = 0;
        for (String k : CPU_TIME_METRICS) {
            Double v = m.get(k);
            if (v == null) {
                continue;
            }
            total += v;
            if (CPU_BUSY_METRICS.contains(k)) {
                busy += v;
            }
        }
        if (total <= 0) {
            return -1;
        }
        return clampPct(busy / total * 100);
    }

    /** part / whole × 100;whole &lt;= 0(未知)时返回 -1。 */
    static double ratioPct(double part, double whole) {
        if (part < 0 || whole <= 0) {
            return -1;
        }
        return clampPct(part / whole * 100);
    }

    /** 把百分比收敛到 [0,100]。 */
    static double clampPct(double v) {
        if (v < 0) {
            return 0;
        }
        return Math.min(v, 100);
    }

    /** 读取 metric → value 映射(两张视图分别查询后合并)。 */
    private static Map<String, Double> collectMetrics(CkClient client, List<String> warnings) {
        Map<String, Double> out = new HashMap<>();
        for (String sql : Arrays.asList(SQL_METRICS, SQL_ASYNC_METRICS)) {
            String table = sql.equals(SQL_METRICS) ? "system.metrics" : "system.asynchronous_metrics";
            QueryResult res = optionalQuery(client, sql, table, warnings);
            if (res == null) continue;
            for (List<Object> row : res.getRows()) {
                if (row.size() < 2 || row.get(0) == null || row.get(1) == null) {
                    continue;
                }
                out.put(String.valueOf(row.get(0)), toF64(row.get(1)));
            }
        }
        return out;
    }

    /**
     * 读取各挂载点容量。
     *
     * <p>用 {@code SELECT *} 再按【列名】取值 —— 不写死列位置,也不假设某个列一定存在:
     * 各版本的容量视图列集合有差异(例如总量列在部分版本里没有暴露)。
     * 缺总量时 {@code usedPct} 记为 -1,前端退化成只展示剩余容量。
     */
    private static List<DiskStat> collectDisks(CkClient client, List<String> warnings) {
        QueryResult res = optionalQuery(client, "SELECT * FROM system.disks", "system.disks", warnings);
        if (res == null) return new ArrayList<>();
        Map<String, Integer> idx = new HashMap<>();
        for (int i = 0; i < res.getColumns().size(); i++) {
            idx.put(res.getColumns().get(i), i);
        }

        List<DiskStat> out = new ArrayList<>();
        for (List<Object> row : res.getRows()) {
            DiskStat d = new DiskStat();
            Object name = cell(row, idx, "name");
            if (name != null) {
                d.setName(String.valueOf(name));
            }
            Object path = cell(row, idx, "path");
            if (path != null) {
                d.setPath(String.valueOf(path));
            }
            Object free = cell(row, idx, "free_space");
            Object total = cell(row, idx, "total_space");
            if (free == null && total == null) {
                continue;
            }
            if (free != null) {
                d.setFreeGB(toF64(free) / BYTES_PER_GB);
            }
            if (total != null) {
                d.setTotalGB(toF64(total) / BYTES_PER_GB);
            }
            d.setUsedPct(-1);
            if (d.getTotalGB() > 0) {
                d.setUsedPct(clampPct((d.getTotalGB() - d.getFreeGB()) / d.getTotalGB() * 100));
            }
            out.add(d);
        }
        return out;
    }

    /**
     * 服务当前生效的内存上限(MB);取不到返回 0。
     *
     * <p>上限取配置项 {@code max_server_memory_usage};它为 0(默认,表示不设)时
     * 退化为 {@code max_server_memory_usage_to_ram_ratio × 机器总内存}。
     *
     * <p>⚠️ 这个值由服务自身维护并会在运行期按可用内存浮动(实测同一台机器上读到的
     * 并不是一个整齐的配置常量)—— 所以这里就是「当下读到的生效值」,
     * 拿它算「离上限还有多远」是对的。
     */
    private static double collectMemLimitMB(CkClient client, double osTotalMB, List<String> warnings) {
        QueryResult res = optionalQuery(client, SQL_SERVER_MEM, "system.server_settings", warnings);
        if (res == null) return 0;
        double hard = 0;
        double ratio = 0;
        for (List<Object> row : res.getRows()) {
            if (row.size() < 2 || row.get(0) == null) {
                continue;
            }
            String name = String.valueOf(row.get(0));
            if ("max_server_memory_usage".equals(name)) {
                hard = toF64(row.get(1));
            } else if ("max_server_memory_usage_to_ram_ratio".equals(name)) {
                ratio = toF64(row.get(1));
            }
        }
        if (hard > 0) {
            return hard / BYTES_PER_MB;
        }
        if (ratio > 0 && osTotalMB > 0) {
            return ratio * osTotalMB;
        }
        return 0;
    }

    /**
     * 按列名从查询行安全取值。
     *
     * @param row 查询结果行
     * @param idx 列名到索引的映射
     * @param col 目标列名
     * @return 单元格值；列不存在或索引越界时为 null
     */
    private static Object cell(List<Object> row, Map<String, Integer> idx, String col) {
        Integer i = idx.get(col);
        if (i == null || i >= row.size()) {
            return null;
        }
        return row.get(i);
    }

    /**
     * 读取监控指标，用 -1 明确表示“指标不可用”。
     *
     * @param m 指标映射
     * @param key 指标名
     * @return 指标值；缺失时为 -1
     */
    private static double value(Map<String, Double> m, String key) {
        Double v = m.get(key);
        return v == null ? -1 : v;
    }

    /**
     * 将字节指标换算为 MiB，并保留“不可用”哨兵值。
     *
     * @param metrics 字节指标映射
     * @param key 指标名
     * @return MiB 数值；指标缺失时为 -1
     */
    private static double mbValue(Map<String, Double> metrics, String key) {
        double bytes = value(metrics, key);
        return bytes < 0 ? -1 : bytes / BYTES_PER_MB;
    }

    /**
     * 查询一张可选的系统监控表。
     *
     * <p>仅当 {@link CkClient#query(String)} 已经进入执行阶段，且明确返回
     * ACCESS_DENIED、UNKNOWN_TABLE 或 UNKNOWN_IDENTIFIER 时，才把该表标记为
     * partial warning 并继续读取其它表。连接失效、超时、网络错误、鉴权失败以及其它
     * SQL 错误仍原样抛出，由调用方统一返回 502，避免把全局故障伪装成“部分指标缺失”。
     *
     * <p>CkClient 用 {@link CkClient.QueryExecutionException} 区分“查询执行失败”和
     * “建连/鉴权失败”。因此这里不按异常消息里的任意 SQLException 递归猜测来源。
     */
    private static QueryResult optionalQuery(CkClient client, String sql, String table, List<String> warnings) {
        try {
            return client.query(sql);
        } catch (CkClient.QueryExecutionException e) {
            if (isConnectionOrAuthenticationFailure(e)) {
                throw e;
            }
            Integer code = clickHouseErrorCode(e);
            String warning = monitoringWarning(table, code);
            if (warning == null) {
                throw e;
            }
            warnings.add(warning);
            return null;
        }
    }

    /**
     * JDBC SQLState 08xxx 表示连接故障，28xxx 表示认证失败。
     *
     * <p>即使驱动同时给出了可降级的 CK vendor code，这类错误也必须保持全局失败；
     * 否则一次断网或凭据失效会只显示“部分指标缺失”，掩盖真正原因。
     */
    private static boolean isConnectionOrAuthenticationFailure(Throwable failure) {
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 10; depth++) {
            if (cause instanceof java.sql.SQLException) {
                String state = ((java.sql.SQLException) cause).getSQLState();
                if (state != null && (state.startsWith("08") || state.startsWith("28"))) {
                    return true;
                }
            }
            Throwable next = cause.getCause();
            cause = next == cause ? null : next;
        }
        return false;
    }

    /** 从查询异常链中读取 ClickHouse 错误码；驱动未填充 errorCode 时再从精确的 Code 文本解析。 */
    private static Integer clickHouseErrorCode(Throwable failure) {
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 10; depth++) {
            if (cause instanceof java.sql.SQLException) {
                int code = ((java.sql.SQLException) cause).getErrorCode();
                if (code > 0) {
                    return code;
                }
            }
            String message = cause.getMessage();
            if (message != null) {
                Matcher matcher = CK_ERROR_CODE.matcher(message);
                if (matcher.find()) {
                    try {
                        return Integer.valueOf(matcher.group(1));
                    } catch (NumberFormatException ignored) {
                        // 超长/异常数字不参与降级，继续向上抛出原始查询错误。
                    }
                }
            }
            Throwable next = cause.getCause();
            cause = next == cause ? null : next;
        }
        return null;
    }

    /** 只映射能够由单表权限或旧版本表结构解释的错误码；其它错误一律保持失败。 */
    private static String monitoringWarning(String table, Integer code) {
        if (code == null) {
            return null;
        }
        switch (code) {
            case CK_ACCESS_DENIED:
                return table + "：当前 CK 账号缺少 SELECT 权限";
            case CK_UNKNOWN_TABLE:
                return table + "：当前 CK 版本未提供此监控表";
            case CK_UNKNOWN_IDENTIFIER:
                return table + "：当前 CK 版本的监控表结构不兼容";
            default:
                return null;
        }
    }

    /**
     * 把驱动返回的数值/字符串转成 double;转不了按 0 处理。
     *
     * <p>JDBC 侧的数值类型比 Go 杂(Long/Double/BigDecimal/字符串都可能出现),
     * 统一在这里收敛。
     */
    static double toF64(Object v) {
        if (v == null) {
            return 0;
        }
        if (v instanceof Number) {
            return ((Number) v).doubleValue();
        }
        if (v instanceof Boolean) {
            return ((Boolean) v) ? 1 : 0;
        }
        if (v instanceof String) {
            try {
                return Double.parseDouble(((String) v).trim());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    /** 供日志/提示用的展示名(与前端标签口径一致)。 */
    static String formatPct(double v) {
        return v < 0 ? "--" : String.format(Locale.ROOT, "%.1f%%", v);
    }
}
