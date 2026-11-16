/**
 * 【文件职责】工作流 DAG 执行引擎，负责拓扑排序、节点数据引用、DuckDB/CK 执行、临时表清理和取消检查。
 * <p>维护本文件时应同步检查调用方契约、失败恢复、并发边界和敏感数据处理，避免只修改单侧实现。
 */
package com.el2ck.workflow;

import com.el2ck.ckpool.CkClient;
import com.el2ck.ckpool.CkConfig;
import com.el2ck.ckpool.CkPool;
import com.el2ck.ckpool.QueryResult;
import com.el2ck.util.Crypto;
import com.el2ck.duck.Duck;
import com.el2ck.duck.DuckQueryResult;
import com.el2ck.entity.common.Column;
import com.el2ck.entity.connection.Connection;
import com.el2ck.entity.datasource.DataSource;
import com.el2ck.entity.sync.SyncTask;
import com.el2ck.entity.workflow.WfEdge;
import com.el2ck.entity.workflow.WfNode;
import com.el2ck.entity.workflow.WfTask;
import com.el2ck.repository.metadata.MetadataRepository;
import com.el2ck.service.datasource.DbSource;
import com.el2ck.util.SourceFile;
import com.el2ck.util.RemoteSourceFile;
import com.el2ck.util.SyncTime;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.Method;
import java.sql.Date;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作流执行引擎:按 DAG 拓扑顺序串行执行工作流的各个节点(对应 Go 端 workflow/engine.go)。
 * 工作流 = 节点(nodes) + 连线(edges)。入口为 datasource(本地文件),中间经 过滤/映射/去重/代码块 等处理,
 * 出口为 ck_write(ClickHouse)。执行按 Kahn 拓扑序;节点之间传递的是“数据引用”而非数据拷贝:
 *   - Kind=file:  直接引用源文件路径 + header/names(尚未物化);
 *   - Kind=table: 引用 DuckDB 物化出的临时表 wf_tmp_&lt;idx&gt;_&lt;nodeId&gt;(处理类节点都会先物化中间结果)。
 * 任一节点失败即中止运行,结束后清理全部临时表;环等非法结构在真正跑前先行拦截,多输入按列名合并。
 * 取消/超时不在此处计时,而由外部中断执行线程、经 checkCancelled 触发中止;单节点行数/耗时汇总进
 * NodeResult,任一节点失败抛 {@link WfException} 并携带已完成(含失败节点)的结果列表。
 */
public class Engine {

    /** 分批处理/写入时,单批数据的行数上限。 */
    static final int PAGE_SIZE = 10000;

    // 合法标识符正则:字母或下划线开头,后跟字母/数字/下划线(校验列名、库表名)
    private static final java.util.regex.Pattern IDENT_RE =
            java.util.regex.Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    // 全局 JSON 序列化器,用于解析/生成节点与连线的配置文本
    private static final ObjectMapper JSON = new ObjectMapper();

    // 元数据仓库:按 ID 读取数据源、连接、全局设置等持久化配置
    private final MetadataRepository store;
    // ClickHouse 连接池,按连接 ID 获取客户端
    private final CkPool pool;
    // DuckDB 引擎:负责文件读取、物化 SQL 执行等
    private final Duck duck;
    // 全局密钥,用于解密数据源/连接中加密保存的敏感配置
    private final String secret;
    // 任务所有者的真实 CK 身份
    private final com.el2ck.service.ck.CkIdentityService identities;

    // 构造器:注入元数据仓库、CK 连接池、DuckDB 与解密密钥
    public Engine(MetadataRepository store, CkPool pool, Duck duck, String secret,
                  com.el2ck.service.ck.CkIdentityService identities) {
        // 将依赖逐一保存到实例字段,供后续各处理方法使用
        this.store = store;
        this.pool = pool;
        this.duck = duck;
        this.secret = secret;
        this.identities = identities;
    }

    /** 节点间传递的数据引用:file 指向源文件,table 指向已物化的临时表。 */
    // 供引擎内部在各节点间传递的数据引用结构
    public static class NodeData {
        String kind;                 // "file" | "table"
        String path;                 // file 类型:源文件路径(Excel 已先转临时 CSV)
        // 是否把首行当作表头(file 类型有效)
        boolean header;
        // 手动指定的列名;为 null 表示交给 DuckDB 按文件头推断
        List<String> names;
        // 自定义分隔符;为 null 表示使用 DuckDB 默认逗号分隔
        String delimiter;
        // 已物化的临时表名(仅 table 类型有效)
        String table;
        // 行数:file 类型为读取前的估算/统计;table 类型为物化后的实际行数
        long rows;

        // file 工厂:按解析参数新建一个“文件型”引用
        static NodeData file(String path, boolean header, List<String> names, String delimiter) {
            // 创建空引用并逐项写入文件解析参数
            NodeData d = new NodeData();
            d.kind = "file";
            d.path = path;
            d.header = header;
            d.names = names;
            d.delimiter = delimiter;
            return d;
        }

        // table 工厂:新建一个引用已物化临时表的“表型”引用
        static NodeData table(String table, long rows) {
            // 创建空引用并记录表名与行数
            NodeData d = new NodeData();
            d.kind = "table";
            d.table = table;
            d.rows = rows;
            return d;
        }
    }

    /** 单个节点的执行结果(供上层服务记录日志/上报状态)。 */
    // 单节点执行结果:记录行数、耗时与失败原因
    public static class NodeResult {
        // 节点 ID
        public String nodeId;
        // 节点类型(datasource/filter/map/dedup/code/ck_write)
        public String nodeType;
        // 输入行数(无输入时保持 0)
        public long rowsIn;
        // 输出行数(尚未产出时为 0)
        public long rowsOut;
        // 执行耗时(毫秒)
        public long durationMs;
        // 执行异常;为 null 表示该节点成功
        public Throwable err;
    }

    /** 工作流执行异常:除 message 外,还携带失败前已产出的各节点结果(含失败节点本身)。 */
    // 携带部分结果的运行时异常,便于上层在失败时仍能拿到已执行节点的统计
    public static class WfException extends RuntimeException {
        // 已执行节点(含失败节点)的结果列表
        public final List<NodeResult> results;

        // 构造器:保存错误信息,并把结果列表归一化为非空
        public WfException(String message, List<NodeResult> results) {
            super(message);
            // 未传结果时兜底为空列表,避免上层判空
            this.results = results == null ? new ArrayList<NodeResult>() : results;
        }
    }

    /**
     * 执行整个工作流:解析节点与连线,拓扑排序后逐个节点执行,按执行顺序返回结果。
     * 任一节点失败都会抛出 {@link WfException}:message 描述失败原因,
     * results 中保存失败前已完成(含失败节点)的执行结果。
     */
    public List<NodeResult> run(WfTask wf) {
        // 已执行完成的节点结果列表(失败时随异常一并返回)
        List<NodeResult> done = new ArrayList<>();
        if (wf == null) {
            throw new WfException("工作流配置不能为空", done);
        }
        // 解析出的节点集合
        List<WfNode> nodes;
        // 解析出的连线集合
        List<WfEdge> edges;
        // 解析工作流持久化的节点/连线 JSON 配置
        try {
            // 节点数组反序列化为 List<WfNode>
            nodes = JSON.readValue(wf.getNodesJson(), new com.fasterxml.jackson.core.type.TypeReference<List<WfNode>>() {
            });
            // 连线数组反序列化为 List<WfEdge>
            edges = JSON.readValue(wf.getEdgesJson(), new com.fasterxml.jackson.core.type.TypeReference<List<WfEdge>>() {
            });
        // JSON 非法则直接包装为工作流异常
        } catch (Exception e) {
            throw new WfException("工作流节点与连线配置解析失败: " + msg(e), done);
        }
        // 无节点视为非法工作流
        if (nodes == null || nodes.isEmpty()) {
            // 提示工作流名称,便于定位
            throw new WfException(String.format("工作流 \"%s\" 无节点", wf.getName()), done);
        }
        // 2. 校验节点 ID 唯一性与节点类型合法性
        // 以节点 ID 为键建立索引,供去重校验与后续快速查找
        Map<String, WfNode> nodeById = new LinkedHashMap<>();
        // 遍历所有节点做合法性校验
        for (WfNode n : nodes) {
            if (n == null) {
                throw new WfException("工作流节点不能为空", done);
            }
            // ID 为空或重复都属非法
            if (n.getId() == null || n.getId().isEmpty() || nodeById.containsKey(n.getId())) {
                throw new WfException(String.format("节点 ID 缺失或重复: \"%s\"", n.getId()), done);
            }
            // 节点类型必须在枚举允许范围内
            if (!WfNode.validType(n.getType())) {
                throw new WfException(String.format("节点 \"%s\" 类型不合法: \"%s\"", n.getId(), n.getType()), done);
            }
            // 通过校验后收入索引
            nodeById.put(n.getId(), n);
        }
        // 允许无边;为空时按空列表处理
        if (edges == null) {
            edges = new ArrayList<>();
        }

        // 3. 拓扑排序(Kahn 算法):得到可顺序执行的节点列表
        // 为什么用 Kahn 而非递归 DFS:节点的输入要靠“上游已执行完并缓存在 outputs 里”才能取到,
        // 所以这里要的不只是一个合法顺序,而是“每个节点出队时其所有前驱都已执行”的保证——
        // 入度归零恰好等价于该保证,判环只是它的副产品。
        // 边界:允许重复边(A->B 出现两次),此时 inDegree[B] 累计为 2、outNodes[A] 里也有两个 B,
        // 削减时会连续减两次同样归零,排序结果不变;自环则永远无法归零,落到下方判环分支。
        // 记录每个节点的入度(前驱数)
        Map<String, Integer> inDegree = new LinkedHashMap<>();
        // 记录每个节点的出边后继列表
        Map<String, List<String>> outNodes = new LinkedHashMap<>();
        // 先给全部节点初始化入度与后继容器
        for (String id : nodeById.keySet()) {
            // 初始入度都为 0
            inDegree.put(id, 0);
            // 后继容器先建空表
            outNodes.put(id, new ArrayList<String>());
        }
        // 遍历连线,累加入度并建立后继关系
        for (WfEdge ed : edges) {
            if (ed == null) {
                throw new WfException("工作流连线不能为空", done);
            }
            // 连线两端必须都存在于节点集合
            if (!nodeById.containsKey(ed.getFrom()) || !nodeById.containsKey(ed.getTo())) {
                throw new WfException(String.format("连线引用不存在的节点: %s -> %s", ed.getFrom(), ed.getTo()), done);
            }
            // 目标节点入度 +1
            inDegree.put(ed.getTo(), inDegree.get(ed.getTo()) + 1);
            // 源节点后继表追加目标节点
            outNodes.get(ed.getFrom()).add(ed.getTo());
        }
        // 拓扑结果列表
        List<String> order = new ArrayList<>();
        // 入度为 0(无前驱)的节点先入队,队列即待执行候选
        java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>();
        // 初始把所有零入度节点入队
        for (Map.Entry<String, Integer> en : inDegree.entrySet()) {
            // 入度为零即无前驱,可先执行
            if (en.getValue() == 0) {
                // 加入待执行队列
                queue.add(en.getKey());
            }
        }
        // Kahn 主循环:依次弹出并“消除”出边
        // 队列为 FIFO:同一层(互不依赖)的节点按“入度归零的先后”出队,顺序稳定可复现;
        // 每个节点只会被入队一次(仅当入度恰好降到 0 的那一次),因此下面的削减不会重复膨胀。
        while (!queue.isEmpty()) {
            // 取出一个待执行节点
            String cur = queue.poll();
            // 记入拓扑顺序
            order.add(cur);
            // 削减其后继的入度
            for (String nx : outNodes.get(cur)) {
                // 后继入度减一
                int d = inDegree.get(nx) - 1;
                // 写回新的入度
                inDegree.put(nx, d);
                // 入度降为 0 表示前驱全部完成,可入队
                if (d == 0) {
                    // 加入队列等待执行
                    queue.add(nx);
                }
            }
        }
        // 若排出的节点数不足,说明存在环导致部分节点永远无法入队
        if (order.size() != nodeById.size()) {
            // 报“存在环”异常(该中文句已正确,保留)
            // 用节点总数比对即可判定,无需再区分“环”与“孤立子图”:二者都表现为入度无法归零,
            // 且都在此被统一拦下,真正的执行阶段不会再遇到取不到输入的情况。
            throw new WfException("工作流存在环,无法执行", done);
        }
        // 4. 按拓扑顺序逐节点执行,并缓存每个节点的输出引用
        // 节点 ID -> 其输出引用,供下游节点取输入
        Map<String, NodeData> outputs = new LinkedHashMap<>();
        Map<String, String> tmpTables = new LinkedHashMap<>(); // 记录 nodeID -> 物化临时表名,便于运行后统一清理
        // 逐个执行;无论成功与否,finally 都会清理临时表
        try {
            // 按拓扑顺序取出待执行节点
            for (String id : order) {
                // 每次执行前检查线程中断(取消/超时)
                checkCancelled();
                // 取当前节点定义
                WfNode node = nodeById.get(id);
                // 收集所有指向本节点的上游输出。
                // 这里反查整张 edges(而非用正向的 outNodes)只为拿到“上游”方向;节点数不多,
                // 每次 O(E) 扫一遍换来的是无需维护反向邻接表。按拓扑序执行保证了 ed.getFrom()
                // 必定已执行完,outputs 中已有其键;upstream==null 仅为防御性判断。
                NodeData input = null;
                List<NodeData> inputs = new ArrayList<>();
                for (WfEdge ed : edges) {
                    if (ed.getTo().equals(id)) {
                        NodeData upstream = outputs.get(ed.getFrom());
                        // NodeData 未重写 equals,contains 走引用相等:目的是把“同一条上游到本节点的
                        // 重复连线”去掉(否则会把同一份数据跟自己合并),而不是按内容去重。
                        if (upstream != null && !inputs.contains(upstream)) inputs.add(upstream);
                    }
                }
                // 单上游:直接引用其输出;多上游(分支汇聚):按列名合并成一份输入;无上游(入口节点):保持 null
                // 注意:只有多上游才会真正物化;单上游零拷贝地把上游引用直接交给下游,这是本引擎
                // “节点间传引用不传数据”的关键——多输入才会付一次 UNION ALL 的代价。
                if (inputs.size() == 1) input = inputs.get(0);
                else if (inputs.size() > 1) input = mergeInputs(id, inputs, tmpTables);
                NodeResult res = new NodeResult();
                // 记录节点 ID
                res.nodeId = id;
                // 记录节点类型
                res.nodeType = node.getType();
                // 有输入则把输入行数作为 rowsIn
                // 注意口径:rowsIn 取自输入引用记录的 rows——file 型是预统计的估算值(统计失败
                // 时甚至是 0),table 型才是物化后的真实行数;而 rowsOut 永远是本节点产出的实际
                // 行数。因此 rowsIn - rowsOut 不能当作“被过滤掉的行数”,上游估算偏差会混进来。
                if (input != null) {
                    // 复制输入行数作为入站行数
                    res.rowsIn = input.rows;
                }
                // 记录执行起始时间,用于统计耗时
                long start = System.currentTimeMillis();
                // 输出引用(可能为 null)
                NodeData out;
                // 分发执行当前节点
                // 失败即断流(fail-fast):不做“跳过失败分支、继续跑其它无关分支”的容错——下游要么
                // 依赖本节点的输出、要么与之无关;后者重跑成本低,而带着半成品数据继续执行更危险。
                // 只捕 RuntimeException:Error(含 OOM)与中断异常直接向上冒,交由线程池/调用方处理。
                try {
                    out = runNode(id, node, input, tmpTables, wf.getDescription());
                // 运行时异常:记录失败并随异常向外抛,让上层拿到已完成节点结果
                } catch (RuntimeException e) {
                    // 记录失败原因
                    res.err = e;
                    // 记录失败节点的耗时
                    res.durationMs = System.currentTimeMillis() - start;
                    // 把失败节点也纳入结果列表
                    done.add(res);
                    throw new WfException(String.format("节点 \"%s\"(%s) 执行失败: %s", id, node.getType(), msg(e)), done);
                }
                // 成功路径同样记录耗时
                res.durationMs = System.currentTimeMillis() - start;
                // 缓存当前节点的输出引用
                outputs.put(id, out);
                // 有输出则记下行数
                if (out != null) {
                    // 输出行数取自引用中的行数
                    res.rowsOut = out.rows;
                }
                // 该节点已结束,登记结果
                done.add(res);
            }
        // 无论循环是否被异常打断,都清理全部临时表
        } finally {
            // 无论是否发生异常,收尾时清理本次运行创建的全部临时表
            // 收尾与结果返回解耦:done/results 已由上层持有,这里只负责回收 DuckDB 侧的中间表与
            // 下载类源产生的本地临时文件;单个表删除失败被吞掉(见 cleanupTmp),不让收尾再抛异常
            // 覆盖掉真正的业务失败原因。临时文件的删除范围也做了收窄,见 cleanupRemoteFiles。
            cleanupTmp(tmpTables);
            cleanupRemoteFiles(outputs);
        }
        // 全部成功则返回有序结果
        return done;
    }

    /**
     * 多输入汇聚(分支合并):把指向本节点的全部上游引用各自包成 SELECT * 子查询后,
     * 以 UNION ALL BY NAME 按列名纵向拼接,并物化为一张临时表,返回其引用与总行数。
     * 调用方负责把产物登记进 tmpTables,便于运行结束后统一清理;空输入(拓扑异常)直接抛错。
     */
    private NodeData mergeInputs(String nodeId, List<NodeData> inputs, Map<String, String> tmpTables) {
        if (inputs == null || inputs.isEmpty()) {
            throw new IllegalStateException("合并输入不能为空");
        }
        List<String> selects = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            String expr = inputExpr(inputs.get(i));
            // 输入表达式可能是 DuckDB 表函数(read_csv/read_json)，不能直接写成
            // FROM (table_function(...))；这里统一让 SELECT 消费原始表达式。
            selects.add("SELECT * FROM " + expr);
        }
        // BY NAME 而不是位置对齐:按列名纵向拼接,列名对不上不会报错——缺失方补 NULL、多出的列
        // 原样保留。这正好对应“分支经不同处理后再汇聚”的场景(各分支列不完全一致),但也是边界:
        // 上游若改了列名(rename)会静默多出一列空列,而不是执行失败。
        // 物化的意义:① 给下游一个稳定的表名与行数;② N 路 UNION 若每页重算,分页查询会把 N 个
        // 文件/表各重扫一遍,物化一次只扫一遍,后续分页都打在临时表上。
        String table = tmpTableName(tmpTables.size(), nodeId);
        long rows = materialize(table, String.join(" UNION ALL BY NAME ", selects), "合并输入");
        // 已知边界(不改变行为,仅记录):本表以本节点 nodeId 为键登记,而该节点随后物化自己的
        // 输出时会用同一个键覆盖此处条目,cleanupTmp 便扫不到这张合并表,它会留在 files.db 中。
        tmpTables.put(nodeId, table);
        return NodeData.table(table, rows);
    }

    /** 执行单个节点:读取节点配置,按类型分发到对应的数据源/处理/写节点逻辑,并返回其输出引用。 */
    private NodeData runNode(String nodeId, WfNode node, NodeData input, Map<String, String> tmpTables,
                             String workflowComment) {
        // 取出节点类型
        String type = node.getType();
        // 节点配置;未配置时用空映射,避免各处判空
        Map<String, Object> cfg = node.getConfig() == null ? new LinkedHashMap<String, Object>() : node.getConfig();
        // 按类型分发到各节点处理器
        switch (type) {
            // 数据源节点:产出首个输入引用
            case WfNode.TYPE_DATASOURCE:
                // 交给数据源节点处理器并返回其引用
                return nodeDatasource(nodeId, cfg);
            // 过滤节点
            case WfNode.TYPE_FILTER:
                // 交给过滤节点处理器
                return nodeFilter(nodeId, cfg, input, tmpTables);
            // 映射节点
            case WfNode.TYPE_MAP:
                // 交给映射节点处理器
                return nodeMap(nodeId, cfg, input, tmpTables);
            // 去重节点
            case WfNode.TYPE_DEDUP:
                // 交给去重节点处理器
                return nodeDedup(nodeId, cfg, input, tmpTables);
            // 代码块节点(JS)
            case WfNode.TYPE_CODE:
                // 交给代码块节点处理器
                return nodeCode(nodeId, cfg, input, tmpTables);
            // 出口:写 ClickHouse
            case WfNode.TYPE_CK_WRITE:
                // 交给出口写节点处理
                return nodeCkWrite(cfg, input, workflowComment);
            // 未知类型直接报错(正常情况下已被节点类型校验拦下)
            default:
                throw new IllegalStateException(String.format("未知节点类型: \"%s\"", type));
        }
    }

    // =====================================================================
    // 数据源 / 处理节点
    // =====================================================================

    /** 数据源节点:按配置产出“输入引用”。本地文件直接引用;数据库源先整表导出为临时 CSV 再引用。 */
    private NodeData nodeDatasource(String nodeId, Map<String, Object> cfg) {
        // 读取配置指定的数据源 ID
        long id = cfgInt(cfg, "dataSourceId", 0);
        // 未配置(ID<=0)视为节点非法
        if (id <= 0) {
            throw new IllegalStateException("数据源节点未配置数据源");
        }
        // 按 ID 加载数据源元数据
        DataSource ds = store.getDataSource(id);
        // 记录不存在说明数据源已被删除
        if (ds == null) {
            throw new IllegalStateException("记录不存在");
        }
        // 解密该数据源加密保存的连接/文件配置
        Map<String, Object> dsCfg = decryptConfig(ds);
        // 本地文件类型:以文件方式直接引用
        if (DataSource.isLocalFile(ds.getType())) {
            // 本地文件源:以文件形式直接引用
            return nodeLocalFile(id, ds.getCode(), ds.getType(), dsCfg);
        }
        // SQL 数据库类型:整表导出为临时 CSV 后引用
        if (DbSource.isSqlSource(ds.getType())) {
            // 数据库源:整表导出后引用
            return nodeDbSource(nodeId, id, ds.getCode(), ds.getType(), cfg, dsCfg);
        }
        // SFTP / MongoDB 等远端源:先下载物化为本地文件,再复用统一文件解析链路
        if (DataSource.SFTP.equals(ds.getType()) || DataSource.MONGODB.equals(ds.getType())) {
            return nodeRemoteSource(ds.getType(), cfg, dsCfg);
        }
        // 其余类型暂不支持作为工作流源
        throw new IllegalStateException(String.format("暂不支持将 \"%s\" 数据源作为工作流源", ds.getType()));
    }

    /** SFTP/Mongo 工作流源:先物化为本地文件，再复用统一文件解析链路。 */
    private NodeData nodeRemoteSource(String type, Map<String, Object> nodeCfg, Map<String, Object> dsCfg) {
        Map<String, Object> cfg = new LinkedHashMap<>(dsCfg);
        String object = cfgStr(nodeCfg, "table", "");
        if (object.isEmpty()) object = cfgStr(nodeCfg, "sourceObject", "");
        String path;
        java.nio.file.Path temporary = null;
        try {
            if (DataSource.SFTP.equals(type)) {
                if (!object.isEmpty()) cfg.put("remotePath", object);
                temporary = RemoteSourceFile.downloadSftp(cfg);
                path = temporary.toString();
                cfg.put("path", path);
            } else {
                if (object.isEmpty()) object = cfgStr(cfg, "collection", "");
                temporary = RemoteSourceFile.exportMongoJsonl(cfg, object);
                path = temporary.toString();
                cfg.put("path", path);
                cfg.put("format", DataSource.JSON);
            }
            String fileType = DataSource.SFTP.equals(type)
                    ? RemoteSourceFile.fileType(new java.io.File(path).toPath(), cfgStr(cfg, "format", ""))
                    : DataSource.JSON;
            String readable = SourceFile.readableCsv(duck, fileType, cfg);
            return NodeData.file(readable, SourceFile.header(cfg), SourceFile.names(cfg), SourceFile.delimiter(cfg));
        } catch (RuntimeException e) {
            RemoteSourceFile.deleteTemp(temporary);
            throw new IllegalStateException(type + " 源物化失败: " + msg(e));
        }
    }

    /** 本地文件(csv/json/xlsx):Excel 先转成临时 CSV;csv/json 其余类型原样引用。 */
    private NodeData nodeLocalFile(long id, String code, String type, Map<String, Object> dsCfg) {
        // 取配置中的文件路径并去掉首尾空白
        String path = cfgStr(dsCfg, "path", "").trim();
        // 路径为空:配置缺失
        if (path.isEmpty()) {
            throw new IllegalStateException(String.format("数据源 \"%s\" 未配置文件路径", code));
        }
        // 文件必须真实存在,否则直接报错
        if (!new java.io.File(path).exists()) {
            throw new IllegalStateException("源文件不存在: " + path);
        }
        // 解析“首行是否为表头”
        boolean header = headerFromConfig(dsCfg);
        // 解析手动指定的列名(可能为 null)
        List<String> names = namesFromConfig(dsCfg);
        // 解析自定义分隔符(可能为 null)
        String delimiter = SourceFile.delimiter(dsCfg);
        // 默认直接使用原始路径
        String readPath = path;
        // DuckDB 就绪时才需要解析/转换
        // 边界:未就绪时不转 Excel、不做任何转换而直接返回原始路径,真正的失败会推迟到下游
        // materialize 时以“duckdb 未初始化”暴露,而不是在这里给出更贴切的“Excel 未转换”提示。
        if (duck.isReady()) {
            // Excel 先转成临时 CSV 再作为 CSV 引用;csv/json 类型原样使用。转换失败即中止本节点。
        try {
                // Excel 交由 readableCsv 转换(内部负责临时 CSV 与缓存)
                readPath = SourceFile.readableCsv(duck, type, dsCfg);
            } catch (RuntimeException e) {
                throw new IllegalStateException(msg(e));
            }
        }
        // 生成文件型引用(记录路径/表头/列名/分隔符)
        NodeData out = NodeData.file(readPath, header, names, delimiter);
        // DuckDB 就绪则预统计行数,便于上层展示估算规模
        // 这是一次额外的 count(*) 全文件扫描,只为把 NodeResult.rowsIn 填成真实规模;
        // 失败被吞(rows 保持 0)是刻意的降级——估算值缺失不该让整个工作流跑不起来。
        if (duck.isReady()) {
            // 统计行数
            try {
                out.rows = duck.countFile(readPath, header, names, delimiter);
            } catch (RuntimeException ignored) {
                // 计数失败可忽略:只是少了估算行数,不影响后续处理
}
        }
        // 返回引用,供下游节点消费
        return out;
    }

    /** 数据库源(mysql/pgsql/clickhouse):整表导出为临时 CSV(含表头),由 DuckDB 推断类型。 */
    private NodeData nodeDbSource(String nodeId, long dsId, String code, String type,
                                  Map<String, Object> nodeCfg, Map<String, Object> dsCfg) {
        // 目标表名:优先取节点自身配置的 table
        String object = cfgStr(nodeCfg, "table", "");
        // 兼容旧画布:早期配置存于 sourceObject 字段
        if (object.isEmpty()) {
            // 兼容早期画布版本使用的 sourceObject 字段(旧字段名)
// 回退读取 sourceObject
object = cfgStr(nodeCfg, "sourceObject", "");
        }
        // 其次回退到数据源级配置的 table
        if (object.isEmpty()) {
            // 使用数据源默认表
            object = cfgStr(dsCfg, "table", "");
        }
        // 仍未指定任何源表则配置非法
        if (object.isEmpty()) {
            throw new IllegalStateException(String.format("数据库数据源 \"%s\" 未指定源表:请在数据源节点配置 table", code));
        }
        // 整表导出依赖 DuckDB,必须就绪
        if (!duck.isReady()) {
            throw new IllegalStateException("duckdb 未初始化");
        }
        // 由数据类型解析出 JDBC 引擎名
        String engine = DbSource.engineOf(type);
        // 把 nodeId 中的非字母数字下划线统一替换为下划线,保证文件名安全
        String clean = nodeId.replaceAll("[^A-Za-z0-9_]", "_");
        // 取得 DB 导出 CSV 的缓存目录
        java.io.File dir = dbCsvCacheDir();
        // 目标文件:缓存目录下 wf_<数据源ID>_<清洗后节点ID>.csv
        // 为什么绕一圈导出 CSV 而不是让 DuckDB 直接 attach 源库:平台只保证内嵌 DuckDB 有
        // read_csv/read_json,不保证装了 mysql/postgres 扩展;导成 CSV 后可完全复用与本地文件
        // 相同的读取链路(分页、行数、类型推断都只实现一份)。
        // 文件名按 数据源ID+节点ID 稳定生成:重跑同一节点会覆盖同名文件,故不会无限增长;
        // 代价是残留文件不会随运行结束清理(见 cleanupRemoteFiles 的边界说明)。
        java.io.File dst = new java.io.File(dir, "wf_" + dsId + "_" + clean + ".csv");
        // 以 UTF-8 缓冲写出整表数据
        long rows;
        try (java.io.BufferedWriter w = new java.io.BufferedWriter(
                new java.io.OutputStreamWriter(new java.io.FileOutputStream(dst),
                        java.nio.charset.StandardCharsets.UTF_8))) {
            // 导出成功则记下行数
            rows = DbSource.writeCsv(engine, dsCfg, object, w);
        // 导出过程任何异常都包装抛出
        } catch (Exception e) {
            throw new IllegalStateException("导出源表 \"" + object + "\" 失败: " + msg(e));
        }
        // 把导出的 CSV 文件包装成文件型引用
        NodeData out = NodeData.file(dst.getAbsolutePath(), true, null, null);
        // 记录实际导出行数
        out.rows = rows;
        // 返回引用
        return out;
    }

    /** DB 源导出缓存目录(位于 duckdb 库目录下的 dbcsv-cache 子目录)。 */
    private java.io.File dbCsvCacheDir() {
        // DuckDB 库文件对象
        java.io.File duckFile = new java.io.File(duck.file());
        // 缓存目录取 duckdb 库文件的同级 dbcsv-cache 子目录
        java.io.File dir = new java.io.File(duckFile.getParentFile(), "dbcsv-cache");
        // 目录不存在则创建;创建失败即报错
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("创建 DB 导出缓存目录失败: " + dir);
        }
        // 返回可用缓存目录
        return dir;
    }

    /** 把输入引用转换成 DuckDB 可用的读取表达式。 */
    // 输入引用 -> DuckDB FROM 子句表达式
    private String inputExpr(NodeData in) {
        // 无输入即缺少前驱数据
        if (in == null) {
            throw new IllegalStateException("节点缺少输入");
        }
        // file 类型:生成 DuckDB 文件读取表达式
        if ("file".equals(in.kind)) {
            // 生成 DuckDB 文件读取表达式
            return Duck.dataFileReader(in.path, in.header, in.names, in.delimiter);
        }
        // table 类型:直接引用已物化的临时表名
        if ("table".equals(in.kind)) {
            // 直接使用已物化的临时表名
            return in.table;
        }
        throw new IllegalStateException(String.format("未知数据引用类型: \"%s\"", in.kind));
    }

    /** 生成物化临时表名:前缀 wf_tmp_ + 拓扑顺序索引 + 清洗后的节点 ID。 */
    // 清洗节点 ID,仅保留字母数字下划线
    static String tmpTableName(int idx, String nodeId) {
        String clean = nodeId.replaceAll("[^a-zA-Z0-9_]", "_");
        // 组合出临时表名
        return "wf_tmp_" + idx + "_" + clean;
    }

    /** 过滤节点:把条件构造成 WHERE,物化 SELECT 结果为临时表。 */
    // 输入表达式
    private NodeData nodeFilter(String nodeId, Map<String, Object> cfg, NodeData in, Map<String, String> tmp) {
        // 说明(过滤/映射/去重/代码块共用):处理类节点一律物化中间结果,不做流式管道串联。
        // 每个节点因此都是一张落在 DuckDB 上的真实表:下游分页有稳定语义、行数可直接 count、
        // 失败能精确定位到节点;代价是每级都付一次写盘与一次全量扫描。
        // 由 filter 配置生成 WHERE 子句(无有效条件时为空串)
        String expr = inputExpr(in);
        // 分配临时表名(序号取当前已物化的数量,保证唯一)
        String where = buildFilterWhere(cfg);
        // 基础查询:读取输入全列
        String table = tmpTableName(tmp.size(), nodeId);
        String select = "SELECT * FROM " + expr;
        // 有条件则追加 WHERE
        if (!where.isEmpty()) {
            // 拼接过滤条件
            select += " WHERE " + where;
        }
        // 物化为临时表并返回行数
        long cnt = materialize(table, select, "过滤");
        // 登记临时表,供运行结束后统一清理
        tmp.put(nodeId, table);
        // 返回过滤结果(临时表)引用
        return NodeData.table(table, cnt);
    }

    /** 映射节点:把源列(必要时加 CAST)输出为目标列,并物化为临时表。 */
    private NodeData nodeMap(String nodeId, Map<String, Object> cfg, NodeData in, Map<String, String> tmp) {
        // 输入表达式
        String expr = inputExpr(in);
        // 读取字段映射列表配置
        Object raw = cfg.get("mapping");
        // 映射必须是 List,否则视为未配置
        List<Object> mappings = raw instanceof List ? (List<Object>) raw : null;
        // 空映射不合法
        if (mappings == null || mappings.isEmpty()) {
            throw new IllegalStateException("映射节点未配置字段映射");
        }
        // 逐条映射生成 SELECT 列表达式,收集到 sel
        List<String> sel = new ArrayList<>();
        // 遍历每条映射
        for (Object o : mappings) {
            // 每条映射必须是键值对象
            if (!(o instanceof Map)) {
                throw new IllegalStateException("映射配置格式错误");
            }
            // 读取源列名与目标列名
            Map<?, ?> mm = (Map<?, ?>) o;
            // 源列名
            String src = String.valueOf(mm.get("sourceColumn"));
            // 目标列名
            String tgt = String.valueOf(mm.get("targetColumn"));
            // 源列/目标列缺失、为空或为字符串 "null" 均非法
            if (src == null || src.isEmpty() || "null".equals(src)
                    || tgt == null || tgt.isEmpty() || "null".equals(tgt)) {
                throw new IllegalStateException("映射的源列或目标列不能为空");
            }
            // 目标列必须是合法标识符,否则无法建列
            if (!validIdent(tgt)) {
                throw new IllegalStateException(String.format("目标列 \"%s\" 非法", tgt));
            }
            // 源列名加反引号作为默认列表达式
            String colExpr = Duck.quoteDuckIdent(src);
            // 可选的类型转换配置
            // 为什么要在这里 CAST:DuckDB 从文件推断的列类型未必是用户想要的(如全数字列被推到
            // BIGINT、"2020-01-01" 被推到 DATE),提前在库内转换后,中间表类型即与最终落库类型
            // 一致,下游节点与 ck_write 不必再猜;仅声明了非 String 类型时才加 CAST,避免无谓转换。
            // 边界:CAST 由 DuckDB 一次性执行,任一行转换失败即整节点失败(无法定位到具体行);
            // 逐行、可定位的转换只发生在 ck_write 的 convertRows/convertValue 里。
            Object t = mm.get("type");
            // 仅当显式声明且不是 String 时才需要 CAST
            if (t instanceof String && !((String) t).isEmpty()
                    && !"String".equalsIgnoreCase((String) t)) {
                // 类型映射为 DuckDB 类型并生成 CAST 表达式
                colExpr = "CAST(" + Duck.quoteDuckIdent(src) + " AS " + ckToDuckType((String) t) + ")";
            }
            // 收集 “源 AS 目标” 形式的 SELECT 片段
            sel.add(colExpr + " AS " + Duck.quoteDuckIdent(tgt));
        }
        // 分配临时表名
        String table = tmpTableName(tmp.size(), nodeId);
        // 用各列表达式拼出完整 SELECT
        String select = "SELECT " + String.join(", ", sel) + " FROM " + expr;
        // 物化并取得行数
        long cnt = materialize(table, select, "映射");
        // 登记临时表
        tmp.put(nodeId, table);
        // 返回映射结果引用
        return NodeData.table(table, cnt);
    }

    /** 去重节点:先按配置生成去重 SELECT,再物化为临时表。 */
    private NodeData nodeDedup(String nodeId, Map<String, Object> cfg, NodeData in, Map<String, String> tmp) {
        // 输入表达式
        String expr = inputExpr(in);
        // 读取去重列配置(可空,空表示整行去重)
        List<?> columns = cfg.get("columns") instanceof List ? (List<?>) cfg.get("columns") : null;
        // 生成去重 SQL
        String select = dedupSelect(expr, columns);
        // 分配临时表名
        String table = tmpTableName(tmp.size(), nodeId);
        // 物化并返回行数
        long cnt = materialize(table, select, "去重");
        // 登记临时表
        tmp.put(nodeId, table);
        // 返回去重结果引用
        return NodeData.table(table, cnt);
    }

    // 依据是否指定去重列,生成不同形态的 SQL
    static String dedupSelect(String expr, List<?> columns) {
        // 输入表达式缺失时无意义
        if (expr == null || expr.trim().isEmpty()) {
            throw new IllegalStateException("去重输入不能为空");
        }
        // 指定了去重列:按这些列取值相同的行只保留第一条
        if (columns != null && !columns.isEmpty()) {
            // 收集加反引号后的列名
            List<String> quoted = new ArrayList<>();
            // 逐列处理
            for (Object o : columns) {
                // 去除空格后得到列名
                String column = String.valueOf(o).trim();
                // 空列或值为 "null" 的字符串视为非法
                if (column.isEmpty() || "null".equalsIgnoreCase(column)) {
                    throw new IllegalStateException("去重列不能为空");
                }
                // 列名加反引号后加入列表
                quoted.add(Duck.quoteDuckIdent(column));
            }
            // QUALIFY row_number()=1:每个 (去重列) 分区保留首行
            // 边界:窗口未指定 ORDER BY,保留的“首行”取决于扫描顺序(单线程文件/表扫描下通常
            // 稳定),因此这里保证的是“同键只留一行”,而不是“留哪一行”的可预期性;若需要确定性,
            // 应先用映射节点排序或改用整行 DISTINCT。
            return "SELECT * FROM " + expr + " QUALIFY row_number() OVER (PARTITION BY "
                    + String.join(", ", quoted) + ") = 1";
        }
        // 未指定列:对整行做 DISTINCT 去重
        return "SELECT DISTINCT * FROM " + expr;
    }

    /** 依据 filter 节点配置的 conditions 构造 WHERE 条件(默认 and 连接)。 */
    private static String buildFilterWhere(Map<String, Object> cfg) {
        // 条件默认用 and 连接
        String logic = "and";
        // 配置 logic=or 时改用 or
        if ("or".equalsIgnoreCase(cfgStr(cfg, "logic", ""))) {
            // 连接词切换为 or
            logic = "or";
        }
        // 读取条件数组
        Object conds = cfg.get("conditions");
        // 条件必须是非空列表才有 WHERE
        if (!(conds instanceof List) || ((List<?>) conds).isEmpty()) {
            return "";
        }
        // 收集每个条件的 SQL 片段
        List<String> parts = new ArrayList<>();
        // 遍历所有条件
        for (Object o : (List<?>) conds) {
            // 非对象条件无法解析,跳过
            if (!(o instanceof Map)) {
                continue;
            }
            // 单个条件对象
            Map<?, ?> cc = (Map<?, ?>) o;
            // 取列名
            String col = String.valueOf(cc.get("column"));
            // 列名缺失、为空或为 "null" 则忽略该条件
            if (col == null || col.trim().isEmpty() || "null".equals(col)) {
                continue;
            }
            // 取操作符
            String op = String.valueOf(cc.get("op"));
            // 取比较值;缺省按空串处理
            String val = cc.get("value") == null ? "" : String.valueOf(cc.get("value"));
            // 列名加反引号,防止与关键字冲突
            String q = Duck.quoteDuckIdent(col.trim());
            // 没有操作符则忽略该条件
            // (注:map 缺 op 键时 String.valueOf(null) 得到字符串 "null",此处判空恒不成立,
            //  但未知操作符会落到下方 default 被忽略,行为上等价于忽略该条件。)
            if (op == null) {
                continue;
            }
            // 依据操作符生成对应的比较谓词(值统一按字符串字面量转义)
            // 边界:值一律拼成字符串字面量,故比较结果的语义取决于列类型——列被 DuckDB 推断为
            // 数值/日期时会把常量隐式转换成该类型再比较(符合直觉);列为字符串时则是字典序
            // 比较,此时 "10" < "9" 成立,排序/比较类过滤需自行保证列类型。
            switch (op.toLowerCase()) {
                case "eq":
                    parts.add(q + " = " + quoteStr(val));
                    break;
                case "ne":
                    parts.add(q + " <> " + quoteStr(val));
                    break;
                case "gt":
                    parts.add(q + " > " + quoteStr(val));
                    break;
                case "ge":
                    parts.add(q + " >= " + quoteStr(val));
                    break;
                case "lt":
                    parts.add(q + " < " + quoteStr(val));
                    break;
                case "le":
                    parts.add(q + " <= " + quoteStr(val));
                    break;
                case "contains":
                    parts.add(q + " LIKE " + quoteStr("%" + val + "%"));
                    break;
                case "not_contains":
                    parts.add(q + " NOT LIKE " + quoteStr("%" + val + "%"));
                    break;
                case "is_empty":
                    parts.add("(" + q + " IS NULL OR " + q + " = '')");
                    break;
                case "is_not_empty":
                    parts.add("(" + q + " IS NOT NULL AND " + q + " <> '')");
                    break;
                default:
                    // 未知操作符忽略,避免生成非法的 SQL 片段
            }
        }
        // 没有任何有效条件则返回空串
        // 边界:条件全被忽略(列名为空/操作符未知)时返回空串,filter 节点并不报错,而是退化成
        // “原样拷贝”的一张物化表——对用户而言表现为过滤没生效,排查时应先看节点配置是否残缺。
        if (parts.isEmpty()) {
            return "";
        }
        // 用连接词拼接全部条件片段
        return String.join(" " + logic + " ", parts);
    }

    /** ClickHouse 类型名 → DuckDB CAST 目标类型名。 */
    // 类型名转小写后按族分发
    static String ckToDuckType(String ck) {
        switch (ck.toLowerCase()) {
            // 各种整型统一映射为 BIGINT
            case "int8":
            case "int16":
            case "int32":
            case "int64":
            case "uint64":
                return "BIGINT";
            // 整型族统一映射为 BIGINT
            case "float32":
            // 浮点族映射为 DOUBLE
            case "float64":
                return "DOUBLE";
            case "bool":
                // 布尔映射为 BOOLEAN
                return "BOOLEAN";
            case "date":
                // 日期/日期时间直接对应
                return "DATE";
            case "datetime":
                return "TIMESTAMP";
            default:
                // 其余未知类型一律回退 VARCHAR
                return "VARCHAR";
        }
    }

    /** DuckDB 物化执行(CREATE OR REPLACE TABLE ... AS SELECT),失败抛出带文案的异常。 */
    // 执行物化并把结果行数返回给调用方
    private long materialize(String table, String select, String errPrefix) {
        // 物化依赖 DuckDB,须先就绪
        if (!duck.isReady()) {
            throw new IllegalStateException("duckdb 未初始化");
        }
        // 调用 DuckDB 完成建表并返回行数
        try {
            // 物化成功,返回结果行数
            return duck.materialize(table, select);
        // 失败时补上前缀文案后抛出
        } catch (RuntimeException e) {
            throw new IllegalStateException(errPrefix + ": " + msg(e));
        }
    }

    /** 运行收尾:删除本次执行物化的全部临时表。 */
    // 清理临时表
    private void cleanupTmp(Map<String, String> tmp) {
        // DuckDB 未就绪则无从清理
        if (!duck.isReady()) {
            return;
        }
        // 逐个删除已登记的临时表
        for (String t : tmp.values()) {
            try {
                // 删除单个临时表
                duck.dropTable(t);
            } catch (RuntimeException ignored) {
                // 忽略单个清理失败,继续删其余表
}
        }
    }

    /** 清理 SFTP/Mongo 工作流源产生的本地临时文件。 */
    private void cleanupRemoteFiles(Map<String, NodeData> outputs) {
        if (outputs == null) return;
        for (NodeData data : outputs.values()) {
            // 只有 file 型引用才带路径;table 型物化结果由 cleanupTmp 负责。
            if (data == null || !"file".equals(data.kind) || data.path == null) continue;
            java.io.File file = new java.io.File(data.path);
            java.io.File parent = file.getParentFile();
            // 安全性边界:只删位于 RemoteSourceFile 自建的 el2ck-* 临时目录内的文件。
            // 用户自己的源文件(本地文件数据源)、以及 dbcsv-cache 下按 数据源ID+节点ID 命名的
            // DB 导出 CSV 都落在此前缀之外,因而被刻意保留——前者不能删,后者要留给下次复用。
            if (parent != null && parent.getName().startsWith("el2ck-")) {
                RemoteSourceFile.deleteTemp(file.toPath());
            }
        }
    }

    // =====================================================================
    // 出口: ck_write
    // =====================================================================

    /** 出口节点:读取输入 → 类型转换 → 分批写入 ClickHouse(含自动建表/清表/增量逻辑)。 */
    // 处理目标为 ClickHouse 的写入节点
    private NodeData nodeCkWrite(Map<String, Object> cfg, NodeData in, String workflowComment) {
        // 缺少输入无法写入
        if (in == null) {
            throw new IllegalStateException("写入节点缺少输入");
        }
        // 读取目标连接 ID
        long connId = cfgInt(cfg, "connectionId", 0);
        // 目标库名(去空白)
        String dbName = cfgStr(cfg, "db", "").trim();
        // 目标表名(去空白)
        String tableName = cfgStr(cfg, "table", "").trim();
        // 连接/库/表任一缺失,视为配置不完整
        if (connId <= 0 || dbName.isEmpty() || tableName.isEmpty()) {
            throw new IllegalStateException("写入节点未配置连接或目标库表");
        }
        // 库名与表名都必须是合法标识符
        if (!validIdent(dbName) || !validIdent(tableName)) {
            throw new IllegalStateException("目标库或表名非法");
        }
        // 读取写入字段映射
        Object m = cfg.get("mapping");
        // 映射必须是列表
        List<Object> mappings = m instanceof List ? (List<Object>) m : null;
        // 未配置映射不合法
        if (mappings == null || mappings.isEmpty()) {
            throw new IllegalStateException("写入节点未配置字段映射");
        }
        // 收集目标列名
        List<String> targetCols = new ArrayList<>();
        // 收集目标列类型
        List<String> types = new ArrayList<>();
        // 遍历每条映射
        for (Object o : mappings) {
            // 映射项必须是对象
            if (!(o instanceof Map)) {
                throw new IllegalStateException("映射配置格式错误");
            }
            // 取源/目标列名
            Map<?, ?> mm = (Map<?, ?>) o;
            String src = String.valueOf(mm.get("sourceColumn"));
            String tgt = String.valueOf(mm.get("targetColumn"));
            // 源列或目标列缺失、为空、为 "null" 均非法
            if (src == null || src.isEmpty() || "null".equals(src)
                    || tgt == null || tgt.isEmpty() || "null".equals(tgt)) {
                throw new IllegalStateException("映射的源列或目标列不能为空");
            }
            // 目标列名入列
            targetCols.add(tgt);
            // 类型经归一化后记录
            types.add(normalizeCkWriteType(String.valueOf(mm.get("type"))));
        }

        // 按连接 ID 取得 ClickHouse 客户端
        CkClient client = targetClient(connId);
        // 拼接限定名:库名.表名(均加反引号)
        String qualified = quoteCK(dbName) + "." + quoteCK(tableName);
        // 探测目标表是否已存在
        boolean exists = ckTableExists(client, dbName, tableName);
        // 排序键;未显式配置时回退到首个目标列
        String orderKey = cfgStr(cfg, "orderKey", "").trim();
        // 空则取首列(首列已存在的前提)
        if (orderKey.isEmpty()) {
            orderKey = targetCols.isEmpty() ? "" : targetCols.get(0);
        }
        // 写入模式:全量 / 增量
        String mode = cfgStr(cfg, "mode", "");
        // 增量列名(增量模式使用)
        String incrCol = cfgStr(cfg, "incrColumn", "").trim();
        // 表不存在则先按映射建表
        if (!exists) {
            // 目标表不存在,先自动建表再继续
            createTable(client, qualified, targetCols, types, orderKey, workflowComment);
        }

        // 增量起始水位(最大值),用于本次过滤增量数据
        // 这里记录的是“目标表各数值列当前最大值”,key 为源列名;真正的含义见下方 applyOffsets:
        // 它不是过滤条件,而是给本次要写入的数值列整体叠加的偏移量。
        Map<String, String> columnOffsets = new LinkedHashMap<>();
        // 增量模式需要增量列与当前表水位
        if (SyncTask.MODE_INCREMENTAL.equals(mode)) {
            // 先按源列名定位增量列,换算成其目标列名
            String incrTarget = incrCol;
            // 遍历映射查找
            // incrMapped 初始值即“增量列名为空也算已映射”:允许不填增量列,此时仅追加数据、
            // 不做任何偏移(见下方 exists && !incrCol.isEmpty() 的双重条件)。
            boolean incrMapped = incrCol.isEmpty();
            for (int mappingIndex = 0; mappingIndex < mappings.size(); mappingIndex++) {
                Object o = mappings.get(mappingIndex);
                if (o instanceof Map) {
                    Map<?, ?> mm = (Map<?, ?>) o;
                    // 源列名即增量列,则目标列名为记录值
                    if (String.valueOf(mm.get("sourceColumn")).equals(incrCol)) {
                        incrTarget = String.valueOf(mm.get("targetColumn"));
                        incrMapped = true;
                        if (!isNumericCkType(types.get(mappingIndex))) {
                            throw new IllegalStateException("增量字段必须是可按数值排序的数字类型: " + incrCol);
                        }
                        // 找到即结束
                        break;
                    }
                }
            }
            // 表已存在才需要读取当前水位
            if (!incrMapped) {
                throw new IllegalStateException("增量字段必须存在于字段映射中: " + incrCol);
            }
            if (exists && !incrCol.isEmpty()) {
                // 取目标列当前最大值作为增量起点
                // 偏移追加时，所有数值映射列均叠加目标对应列的当前最大值。
                // 为什么要“所有”而不是只叠加增量列:增量列常常是自增主键,而同一行的父级 ID、
                // 关联编号等数值列与它同处一个编号空间;只偏移主键会让新批次的关联关系指向旧数据。
                // 边界:按“目标列当前 max”偏移意味着跑两次增量的结果与跑一次的中间态相关——
                // 表里已有历史数据时,本次写入的编号会被顶到历史最大值之上,不会与已有键冲突。
                for (int i = 0; i < mappings.size(); i++) {
                    Map<?, ?> mm = (Map<?, ?>) mappings.get(i);
                    if (isNumericCkType(types.get(i))) {
                        String max = ckMax(client, qualified, targetCols.get(i));
                        if (max != null && !max.trim().isEmpty()) {
                            columnOffsets.put(String.valueOf(mm.get("sourceColumn")), max);
                        }
                    }
                }
            }
        // 全量模式:决定是否先清空目标表
        // 默认语义是“追加”:未勾选 truncate 时重复执行同一工作流会把数据反复叠进目标表;
        // 而新建的表即使勾了也无需清空,故用 !exists 短路,省掉一次无意义的 TRUNCATE。
        } else {
            // 读取 truncate 配置
            Object trunc = cfg.get("truncate");
            // 新建的表无需清空;旧表仅当显式开启 truncate 才清空
            boolean doTruncate = !exists || Boolean.TRUE.equals(trunc);
            // 需要清空则执行 TRUNCATE
            if (doTruncate) {
                // 截断表并等待副本同步完成(SYNC)
                // SYNC 是刻意加的:不带 SYNC 的 TRUNCATE 在副本表上可能尚未生效就开始写入,
                // 造成新旧数据短暂共存;代价是等待副本确认,会略微拖慢启动。
                try {
                    client.exec("TRUNCATE TABLE IF EXISTS " + qualified + " SYNC");
                } catch (RuntimeException e) {
                    throw new IllegalStateException("清空目标表失败: " + msg(e));
                }
            }
        }

        // 累计从输入读到的行数
        long rowsIn = 0;
        // 累计实际写入的行数
        long rowsOut = 0;
        // 读取偏移量,与 PAGE_SIZE 配合分批拉取
        int offset = 0;
        // 分批循环,直到某批读不到更多行
        // 分页的意义是内存上限:每批最多 PAGE_SIZE 行转成 Java 对象后在内存里,而不是把整个
        // 中间表读进堆。取消/超时的响应粒度也就是一批(见下面的 checkCancelled)。
        // 边界:offset 分页要求“同一份输入顺序稳定”。文件读取未加 ORDER BY,理论上不保证两次
        // 查询同序——这里依赖的是同一次运行内输入文件/临时表不被改动。
        while (true) {
            // 每批开始先检查线程中断
            checkCancelled();
            // 增量采用偏移追加，不再用源值大于目标最大值过滤，避免源端编号重置后读 0 行。
            // where 因此恒为 null(keyset 过滤被替换后留下的可空入参,保留是为了以后能重新启用
            // 条件读取而不必改 readInput 的签名)。
            String where = null;
            // 保存本批查询结果
            DuckQueryResult res;
            // 读取本批数据
            try {
                res = readInput(in, where, PAGE_SIZE, offset);
            // 读取失败即终止写入
            } catch (RuntimeException e) {
                throw new IllegalStateException("读取输入数据失败: " + msg(e));
            }
            // 本批行数
            int n = res.getRows().size();
            // 无行说明已读到末尾
            if (n == 0) {
                // 已读到末尾,结束分批循环
                break;
            }
            // 累加输入行数
            rowsIn += n;
            // 预先算出每个源列在本次结果中的列下标
            // 按列名而非位置定位:输入表的列顺序由上游节点决定(映射/合并都可能改变顺序),
            // 只有列名才是稳定的契约;每页重算一次是因为列名随结果集元数据来,成本可忽略。
            int[] srcIdx = new int[mappings.size()];
            // 逐个源列定位下标
            for (int i = 0; i < mappings.size(); i++) {
                // 取当前映射项
                Map<?, ?> mm = (Map<?, ?>) mappings.get(i);
                String src = String.valueOf(mm.get("sourceColumn"));
                // 定位源列下标,未找到返回 -1
                srcIdx[i] = colIndex(res.getColumns(), src);
                // 输入缺少该列则报错并列出全部可用列
                if (srcIdx[i] < 0) {
                    throw new IllegalStateException(String.format("输入列 \"%s\" 不存在,可用列: %s", src, String.join(", ", res.getColumns())));
                }
            }
            // 把整批字符串按目标类型转换为 Java 值
            // 偏移必须先于类型转换:先把增量值加上水位(仍是字符串),再按目标类型解析,这样
            // 得到的仍是目标类型的 Java 值;顺序反了则要在数值对象上再做加法,类型分支会翻倍。
            List<List<String>> offsetRows = applyOffsets(res.getRows(), mappings, srcIdx, types, columnOffsets);
            List<List<Object>> converted = convertRows(offsetRows, srcIdx, types);
            // 批量插入目标表
            try {
                client.insertBatch(qualified, targetCols, converted);
            // 写入失败即终止
            } catch (RuntimeException e) {
                throw new IllegalStateException("写入目标表失败: " + msg(e));
            }
            // 写入成功则累计输出行数
            rowsOut += n;
            // 本批不足一页说明已处理完
            if (n < PAGE_SIZE) {
                // 本批不足一页即全部读完,结束循环
                // 边界:行数恰为 PAGE_SIZE 整数倍时会多跑一次查询(返回 0 行后由上方 n==0 退出),
                // 多付一次空查询换取判定逻辑只需一处。
                break;
            }
            // 否则偏移量前进一页,继续读下一批
            offset += PAGE_SIZE;
        }
        // 返回“无物化表”的占位引用,行数即写入总数
        // 空表名是刻意占位:ck_write 是出口,不产出可被下游消费的表;若真被连了下游,空表名会在
        // DuckDB 侧因非法标识符直接报错,而不是静默读到脏数据。rowsIn/rowsOut 在此必然相等。
        return NodeData.table("", rowsOut);
    }

    /** 按引用类型分页读取输入(可携带 WHERE 过滤条件)。 */
    // 依据引用类型选择文件/临时表查询
    private DuckQueryResult readInput(NodeData in, String where, int limit, int offset) {
        // file 引用:走文件查询
        if ("file".equals(in.kind)) {
            // 文件类型:走 DuckDB 文件分页查询
            return duck.queryFile(in.path, in.header, in.names, in.delimiter, where, limit, offset);
        }
        // table 引用:走临时表查询
        return duck.queryTable(in.table, where, limit, offset);
    }

    // =====================================================================
    // 代码块节点(JS)
    // =====================================================================

    /**
     * 代码块节点:对输入逐行执行用户编写的 JS 脚本,脚本须定义 function transform(row)。
     * 返回 null/undefined 表示过滤该行;返回对象则按输入列取成员,统一按 VARCHAR 落库。
     * JS 引擎优先用 Nashorn(Java 8 内置);找不到引擎时报清晰错误,不影响平台其它功能。
     */
    private NodeData nodeCode(String nodeId, Map<String, Object> cfg, NodeData in, Map<String, String> tmp) {
        // 缺少输入则无数据可处理
        if (in == null) {
            throw new IllegalStateException("代码块节点缺少输入");
        }
        // 读取脚本源码并去掉首尾空白
        String script = cfgStr(cfg, "script", "").trim();
        // 空脚本无意义
        if (script.isEmpty()) {
            // 脚本内容为空则无意义
            throw new IllegalStateException("代码块节点缺少输入");
        }
        // 先取输入引用的列名(用于建输出表与取值)
        // 先把输入列名探出来:输出表 DDL 必须在跑脚本之前建好(边跑边写),因此列集合在编译
        // 脚本前就固定了。这也决定了脚本的能力边界——只能改写/丢弃输入列的值,不能新增列,
        // transform 返回对象上多出来的成员会被忽略(下方只按 cols 取成员)。
        List<String> cols = inputColumns(in);
        // 输入无任何列则无法继续
        if (cols.isEmpty()) {
            throw new IllegalStateException("输入无列");
        }
        // 分配本节点物化临时表名
        String table = tmpTableName(tmp.size(), nodeId);
        // 拼装建表 DDL:CREATE TABLE 表 (列1 VARCHAR,列2 VARCHAR, ...)
        // 全部落 VARCHAR:脚本返回值的类型不受控(可能是数字、布尔、日期对象甚至 undefined),
        // 统一按文本落库可保证任何一行都不会因类型不匹配而写不进去;真正的类型转换交给下游
        // 映射节点或 ck_write 的字段映射完成。
        StringBuilder ddl = new StringBuilder("CREATE TABLE " + table + " (");
        // 把每个输入列都建成 VARCHAR 列
        for (int i = 0; i < cols.size(); i++) {
            // 除首列外列间以逗号分隔
            if (i > 0) {
                ddl.append(", ");
            }
            // 列名加反引号,类型统一 VARCHAR
            ddl.append(Duck.quoteDuckIdent(cols.get(i))).append(" VARCHAR");
        }
        // 表定义收口
        ddl.append(')');
        // 建表依赖 DuckDB,须先就绪
        if (!duck.isReady()) {
            throw new IllegalStateException("duckdb 未初始化");
        }
        // 先建输出表
        try {
            // 执行建表 DDL
            duck.execRaw(ddl.toString());
        // 建表失败即报错
        } catch (RuntimeException e) {
            throw new IllegalStateException("创建代码块输出表失败: " + msg(e));
        }
        // 登记临时表,供运行后清理
        tmp.put(nodeId, table);

        // 加载可用的 JS 引擎(可能为 null)
        Js js = Js.load();
        // 无引擎则代码块节点无法执行
        if (js == null) {
            throw new IllegalStateException("当前 JVM 没有可用的 JS 引擎(Nashorn/GraalJS),代码块节点无法执行");
        }
        // 先编译并执行脚本(定义 transform 函数)
        // 脚本只 eval 一次,之后逐行 invokeFunction:JS 引擎全局状态因此跨行保留,脚本里用
        // 全局变量做累计(序号、去重集合)是可行的;但这也意味着脚本出错后的状态难回滚。
        try {
            // 执行脚本,使其中的 function transform(row) 生效
            js.eval(script);
        // 脚本解析失败即报错
        } catch (Exception e) {
            throw new IllegalStateException("脚本解析失败: " + msg(e));
        }
        // 脚本必须提供 transform 全局函数
        if (!js.hasTransform()) {
            throw new IllegalStateException("脚本必须定义 function transform(row)");
        }

        // 累计输出行数
        long rowsOut = 0;
        // 攒批待写的数据(每批最多 5000 行)
        List<List<String>> batch = new ArrayList<>();
        // 读取偏移量,配合 PAGE_SIZE 分批
        int offset = 0;
        // 整体 try/finally:保证即便中途出错也不残留输出表
        try {
            // 分批读取并逐行 transform
            while (true) {
                // 每批开始检查线程中断
                checkCancelled();
                // 保存本批查询结果
                DuckQueryResult res;
                try {
                    // 读取一批输入行
                    res = readInput(in, null, PAGE_SIZE, offset);
                // 读取失败即终止
                } catch (RuntimeException e) {
                    throw new IllegalStateException("读取输入失败: " + msg(e));
                }
                // 本批行数
                int n = res.getRows().size();
                // 无行说明读完了
                if (n == 0) {
                    break;
                }
                // 逐行调用用户 transform
                for (int ri = 0; ri < n; ri++) {
                    // 行级也检查中断
                    // 检查粒度细化到行:用户脚本可能很慢,一批 10000 行下按批检查会让取消/超时
                    // 迟迟不生效。isInterrupted() 只是读一个标志位,每行调用开销可忽略。
                    checkCancelled();
                    // 当前行(字符串形态)
                    List<String> row = res.getRows().get(ri);
                    // 尽可能取保留原始类型的“裸”行(不足时退化为字符串行)
                    // rawRows 与 rows 由 DuckDB 查询同步填充,二者等长;这里的下标保护是防御性的:
                    // 一旦长度不一致就退回字符串行,宁可丢类型信息也不让行号错位。
                    List<Object> rawRow = res.getRawRows().size() > ri
                            ? res.getRawRows().get(ri) : new ArrayList<Object>(row);
                    // 组装当前行 {列名: 值} 的行对象:typed 保留各列的原始值类型,交给 JS 使用
                    // 传给 JS 的是“列名 → 原始值”的 Map 形态:脚本里既可按 row.col 也可按
                    // row["col"] 取值,数字/布尔/日期保持运行时类型,方便做运算后再字符串化。
                    Map<String, Object> typed = new LinkedHashMap<>();
                    // 逐列填充
                    for (int ci = 0; ci < cols.size(); ci++) {
                        // 优先用原始类型值,否则退回字符串行值
                        typed.put(cols.get(ci), ci < rawRow.size() ? rawRow.get(ci) : row.get(ci));
                    }
                    // 把 typed 转成 JS 引擎内的对象
                    Object rowObj = js.rowObject(typed);
                    // transform 的返回值
                    Object result;
                    try {
                        // 调用用户定义的 transform(row)
                        result = js.transform(rowObj);
                    // transform 抛错:定位到第几行后报出
                    } catch (Exception e) {
                        throw new IllegalStateException(String.format("脚本处理第 %d 行失败: %s", offset + ri + 1, msg(e)));
                    }
                    // 返回 null/undefined 表示丢弃该行
                    // 同时判 null 与字符串 "undefined":不同引擎把 JS 的 undefined 交回 Java 时
                    // 表现不一(有的给 null,有的给内部 Undefined 单例,后者的 toString 是
                    // "undefined"),两种都按“丢弃该行”处理,避免把 undefined 写成文本。
                    if (result == null || "undefined".equals(String.valueOf(result))) {
                        continue; // 丢弃(过滤)该行
}
                    // 构造输出行:只取输入列,缺值补空串
                    List<String> outRow = new ArrayList<>(cols.size());
                    // 逐列从 JS 返回值上取成员
                    for (String col : cols) {
                        // 成员值转成字符串
                        String v = Js.member(result, col);
                        // 空值归一为空串
                        outRow.add(v == null ? "" : v);
                    }
                    // 本行加入待写批次
                    batch.add(outRow);
                    // 攒够 5000 行即落库,避免单条 INSERT 过大
                    // 5000 是经验值:insertValues 会把整批拼成一条 SQL 文本,批越大字符串越占内存
                    // (值还要单引号转义),批太小则 INSERT 次数过多;它独立于 PAGE_SIZE,只影响
                    // 写 DuckDB 的频率,不影响读取分页。
                    if (batch.size() >= 5000) {
                        // 批量写入当前批次
                        insertValues(table, cols, batch);
                        // 累计输出行数
                        rowsOut += batch.size();
                        // 清空批次继续攒
                        batch.clear();
                    }
                }
                // 本批不足一页说明已处理完全部输入
                if (n < PAGE_SIZE) {
                    break;
                }
                // 否则偏移量前进一页,继续读下一批
                offset += PAGE_SIZE;
            }
        } finally {
            // 空 finally:无资源需要关闭;Js 封装仅本次执行使用,结束即随之释放
            // 之所以要这个空块:输出表在本方法开头就已登记进 tmp,即使循环中途抛错,run() 的
            // 收尾 cleanupTmp 也一定会把它删掉——失败路径不会留下半成品的临时表。
        }
        // 收尾:把剩余不足一批的行也写掉
        if (!batch.isEmpty()) {
            // 写入剩余行
            insertValues(table, cols, batch);
            // 累计剩余行数
            rowsOut += batch.size();
            batch.clear();
        }
        // 返回输出表引用与累计行数
        return NodeData.table(table, rowsOut);
    }

    /** 取输入引用的列名列表(供代码块节点建输出表与取值)。 */
    // 返回的列名列表
    private List<String> inputColumns(NodeData in) {
        // file 引用:读取文件结构得到列
        List<String> cols = new ArrayList<>();
        // 文件描述依赖 DuckDB,须先就绪
        if ("file".equals(in.kind)) {
            // 描述文件需要 DuckDB
            if (!duck.isReady()) {
                throw new IllegalStateException("duckdb 未初始化");
            }
            // 保存文件列描述
            List<Column> list;
            // 解析文件列结构
            try {
                // 描述文件结构,得到各列定义
                list = duck.describeFile(in.path, in.header, in.names, in.delimiter);
            // 读取失败即报错
            } catch (RuntimeException e) {
                throw new IllegalStateException("读取输入结构失败: " + msg(e));
            }
            // 逐列取出列名
            for (Column c : list) {
                // 收集列名
                cols.add(c.getName());
            }
        // table 引用:查一行即可拿到列名
        } else {
            // 只取 1 行用于获取结果集列名
            DuckQueryResult res = duck.queryTable(in.table, null, 1, 0);
            // 结果集列名即输入列
            cols.addAll(res.getColumns());
        }
        return cols;
    }

    /** 批量构造 INSERT ... VALUES 并交给 DuckDB 执行(值统一按字符串字面量转义)。 */
    private void insertValues(String table, List<String> cols, List<List<String>> rows) {
        // 把目标列名都加上反引号
        List<String> quoted = new ArrayList<>(cols.size());
        // 逐列加引号
        for (String c : cols) {
            // 列名加反引号后收集
            quoted.add(Duck.quoteDuckIdent(c));
        }
        // INSERT 语句头:INSERT INTO 表 (列...) VALUES
        StringBuilder sb = new StringBuilder("INSERT INTO " + table + " (" + String.join(", ", quoted) + ") VALUES ");
        // 逐行拼出值组
        for (int ri = 0; ri < rows.size(); ri++) {
            // 行之间用逗号分隔
            if (ri > 0) {
                sb.append(", ");
            }
            // 每行值用括号包裹
            sb.append('(');
            // 当前行值
            List<String> row = rows.get(ri);
            // 列之间用逗号分隔
            for (int ci = 0; ci < row.size(); ci++) {
                if (ci > 0) {
                    sb.append(", ");
                }
                // 值做单引号转义后写入
                sb.append(quoteStr(row.get(ci)));
            }
            sb.append(')');
        // 收口括号,语句完成
        }
        // 执行批量写入
        try {
            // 执行整条批量 INSERT
            duck.execRaw(sb.toString());
        // 写入失败即抛出
        } catch (RuntimeException e) {
            throw new IllegalStateException("写入输出表失败: " + msg(e));
        }
    }

    // =====================================================================
    // 目标连接 / 表工具(与 sync 引擎共用)
    // =====================================================================

    /** 加载目标连接并从连接池取出客户端(只读连接禁止写入)。 */
    private CkClient targetClient(long id) {
        // 按 ID 读取连接记录
        Connection c = store.getConnection(id);
        // 连接不存在则报错
        if (c == null) {
            throw new IllegalStateException("记录不存在");
        }
        // 工作流写入使用任务所有者绑定的真实 CK 身份;只读标记不在这里拦截(与同步语义不同)。
        try {
            return identities.client(id);
        } catch (RuntimeException e) {
            throw new IllegalStateException(msg(e));
        }
    }

    // 查询系统表判断目标表是否已存在
    private boolean ckTableExists(CkClient client, String db, String table) {
        // 按库名/表名精确匹配(参数化查询防注入)
        QueryResult res = client.query("SELECT count() FROM system.tables WHERE database = ? AND name = ?",
                // 库名与表名作为查询占位参数(防注入)
                java.util.Arrays.asList((Object) db, table));
        // 取到 count 结果才做判断
        if (!res.getRows().isEmpty() && !res.getRows().get(0).isEmpty() && res.getRows().get(0).get(0) != null) {
            // 解析 count 值
            try {
                // 行数大于 0 即表存在
                return Long.parseLong(String.valueOf(res.getRows().get(0).get(0)).trim()) > 0;
            // 数值异常按不存在处理
            } catch (NumberFormatException e) {
                // 数值解析异常时按“表不存在”处理
                return false;
            }
        }
        // 无结果默认不存在
        return false;
    }

    /** 自动建表:列类型透传(空类型默认 String),排序键可空时附加 allow_nullable_key 设置。 */
    // 拼装 CREATE TABLE IF NOT EXISTS 语句
    private void createTable(CkClient client, String qualified, List<String> cols, List<String> types,
                             String orderKey, String tableComment) {
        // 逐列拼出 “反引号列名 + 空格 + 类型”
        StringBuilder sb = new StringBuilder("CREATE TABLE IF NOT EXISTS " + qualified + " (");
        // 遍历所有目标列
        for (int i = 0; i < cols.size(); i++) {
            // 列间以逗号分隔
            if (i > 0) {
                sb.append(", ");
            }
            // 取该列类型并去空白
            String t = types.get(i).trim();
            // 空类型补默认 String
            if (t.isEmpty()) {
                // 空类型补默认 String
                t = "String";
            }
            // MergeTree 默认不允许 Nullable 排序键，工作流自动建表时将排序键声明为非 Nullable。
            if (orderKey.equals(cols.get(i)) && t.startsWith("Nullable(") && t.endsWith(")")) {
                t = t.substring("Nullable(".length(), t.length() - 1).trim();
            }
            // 追加一列定义
            sb.append(quoteCK(cols.get(i))).append(' ').append(t);
        }
        // 指定表引擎与排序键:ENGINE = MergeTree ORDER BY (orderKey)
        sb.append(") ENGINE = MergeTree ORDER BY (").append(quoteCK(orderKey)).append(')');
        // 工作流说明仅在创建目标表时写入，显式传递以隔离并发工作流的说明。
        if (tableComment != null && !tableComment.trim().isEmpty()) {
            sb.append(" COMMENT ").append(com.el2ck.util.Sql.quoteString(tableComment));
        }
        // 执行建表语句
        try {
            // 执行建表语句
            client.exec(sb.toString());
        // 建表失败即终止
        } catch (RuntimeException e) {
            throw new IllegalStateException("自动建表失败: " + msg(e));
        }
    }

    // 判断排序键对应列的类型是否以 Nullable( 开头
    static boolean hasNullableOrderKey(List<String> columns, List<String> types, String orderKey) {
        // 任一关键入参为空则无从判断
        if (columns == null || types == null || orderKey == null) {
            // 参数缺失时无从判断,默认返回 false
            return false;
        // 同时遍历列与类型数组
        }
        // 逐列比对
        for (int i = 0; i < columns.size() && i < types.size(); i++) {
            // 该列类型去空白(空视为非可空)
            String type = types.get(i) == null ? "" : types.get(i).trim();
            // 列名等于排序键,且类型以 Nullable( 起始(忽略大小写)
            if (orderKey.equals(columns.get(i))
                    // 命中则说明排序键声明为可空
                    && type.regionMatches(true, 0, "Nullable(", 0, "Nullable(".length())) {
                return true;
            }
        }
        // 均不满足返回 false
        return false;
    }

    // 写入类型归一化:Bool/Boolean 统一映射为 UInt8
    static String normalizeCkWriteType(String type) {
        // 类型去空白(空串原样返回)
        String value = type == null ? "" : type.trim();
        // 布尔类型等价写法归一
        if ("Bool".equalsIgnoreCase(value) || "Boolean".equalsIgnoreCase(value)) {
            // 布尔类型统一归一为 UInt8
            return "UInt8";
        }
        // 其它类型原样透传
        return value;
    }

    /** 查询目标表某列的最大值;空表或无值返回 null。 */
    // 执行 max() 聚合查询
    private String ckMax(CkClient client, String qualified, String col) {
        // 取该列最大值
        QueryResult res = client.query("SELECT max(" + quoteCK(col) + ") FROM " + qualified);
        // 取到首行结果才继续
        if (!res.getRows().isEmpty() && !res.getRows().get(0).isEmpty()) {
            // 单元格值
            Object v = res.getRows().get(0).get(0);
            // 非空值转为字符串返回
            return v == null ? null : String.valueOf(v);
        }
        // 空表返回 null
        return null;
    }

    // =====================================================================
    // 值转换(与 sync 引擎共用)
    // =====================================================================

    /** 按映射下标与目标类型把整批结果行转成目标值;逐行逐列做校验。 */
    static List<List<Object>> convertRows(List<List<String>> rows, int[] srcIdx, List<String> types) {
        // 输出:与行数等长的转换后行集合
        List<List<Object>> out = new ArrayList<>(rows.size());
        // 逐行转换
        for (int ri = 0; ri < rows.size(); ri++) {
            // 当前原始字符串行
            List<String> row = rows.get(ri);
            // 当前行的目标类型值列表
            List<Object> vals = new ArrayList<>(srcIdx.length);
            // 逐列转换
            for (int ci = 0; ci < srcIdx.length; ci++) {
                // 该列在输入结果集中的下标
                int idx = srcIdx[ci];
                // 下标越界属于数据异常
                if (idx < 0 || idx >= row.size()) {
                    throw new IllegalStateException("第 " + (ri + 1) + " 行取值越界");
                }
                // 单值按目标类型转换
                try {
                    // 单值转换成功,收入本行结果
                    vals.add(convertValue(row.get(idx), types.get(ci)));
                // 转换失败:按第几行第几列定位
                // 这里的“第 ci+1 列”是字段映射里的序号(不是源表列号),定位时需对照写入节点的
                // 映射列表;行号是最外层 for 的原始行序,与 offset 无关(本方法按批调用)。
                } catch (RuntimeException e) {
                    throw new IllegalStateException(String.format("第 %d 行第 %d 列转换失败: %s", ri + 1, ci + 1, msg(e)));
                }
            }
            // 完成本行,加入输出
            out.add(vals);
        }
        // 返回全部转换结果
        return out;
    }

    /** 对工作流写入节点的数值映射列叠加目标水位偏移。 */
    private static List<List<String>> applyOffsets(List<List<String>> rows, List<Object> mappings,
                                                    int[] srcIdx, List<String> types,
                                                    Map<String, String> offsets) {
        if (offsets == null || offsets.isEmpty()) return rows;
        // 只在“目标列是数值类型且该源列确实有水位数”时才改写单元格;其余单元格原样保留,
        // 因此全量模式或未填增量列时本方法等价于无操作(直接返回入参,零拷贝)。
        List<List<String>> out = new ArrayList<>(rows.size());
        for (List<String> row : rows) {
            List<String> copy = new ArrayList<>(row);
            for (int i = 0; i < mappings.size(); i++) {
                if (!isNumericCkType(types.get(i))) continue;
                Object item = mappings.get(i);
                if (!(item instanceof Map)) continue;
                String source = String.valueOf(((Map<?, ?>) item).get("sourceColumn"));
                String offset = offsets.get(source);
                if (offset == null || srcIdx[i] < 0 || srcIdx[i] >= copy.size()) continue;
                String raw = copy.get(srcIdx[i]);
                // 空值不参与偏移:避免把“缺值”变成水位本身(那样会凭空造出一个等于目标最大值
                // 的值),留空交由 convertValue 按类型补默认值。
                if (raw == null || raw.trim().isEmpty() || "null".equalsIgnoreCase(raw.trim())) continue;
                // 用 BigDecimal 而非 double:增量水位动辄是雪花 ID/18~19 位整数,double 只有 53
                // 位有效精度会静默丢低位;stripTrailingZeros+toPlainString 则避免输出 1E+3 这类
                // 科学计数法(ClickHouse 数字字面量不接受)。
                try {
                    copy.set(srcIdx[i], new java.math.BigDecimal(raw.trim())
                            .add(new java.math.BigDecimal(offset.trim()))
                            .stripTrailingZeros().toPlainString());
                } catch (NumberFormatException e) {
                    throw new IllegalStateException("增量字段无法执行数值偏移: " + raw, e);
                }
            }
            out.add(copy);
        }
        return out;
    }

    // 判断 ClickHouse 类型是否可按数值处理(用于增量偏移/数值水位):先剥掉外层 Nullable(...),再按 整型/浮点/Decimal/Numeric 族正则匹配
    private static boolean isNumericCkType(String type) {
        String t = type == null ? "" : type.trim().toLowerCase();
        while (t.startsWith("nullable(") && t.endsWith(")")) {
            t = t.substring(9, t.length() - 1).trim();
        }
        return t.matches("u?int(8|16|32|64|128|256)|float(32|64)|decimal.*|numeric.*");
    }

    /** 把单个字符串值按目标 ClickHouse 类型转换为 Java 值。 */
    // 依据目标类型把字符串解析成对应 Java 对象
    static Object convertValue(String s, String ckType) {
        // 类型名小写并去空白,便于匹配
        String t = ckType == null ? "" : ckType.trim().toLowerCase();
        // 剥掉外层 Nullable(...),按内层类型处理
        // 只剥一层:Nullable(Decimal(10,2)) 这类嵌套结构剥一次即可命中内层类型;而
        // Nullable(Array(...)) 之类本方法不认识的类型会落到 default 原样透传——空值语义
        // 由调用方(空值填充分支)决定,这里不负责构造复杂类型的默认值。
        if (t.startsWith("nullable(") && t.endsWith(")")) {
            t = t.substring("nullable(".length(), t.length() - 1).trim();
        }
        // 空值/null/字符串 "null":按各类型补默认值
        // 空串与字面量 "null" 都算缺失:源数据经 DuckDB 读出后缺失值表现为空串,而从 JSON/DB
        // 导出的数据常见把 null 写成文本 "null"。此处不区分“列为 Nullable”而一律补默认值,
        // 好处是写入永远不会因类型不匹配失败;代价是可空列也拿不到真正的 NULL。
        if (s == null || s.trim().isEmpty() || "null".equalsIgnoreCase(s.trim())) {
            // 数值类型默认补 0
            if (t.startsWith("int") || t.startsWith("uint") || t.startsWith("float") || t.startsWith("decimal")) s = "0";
            // 布尔默认 false
            else if ("bool".equals(t) || "boolean".equals(t)) s = "false";
            // 日期默认纪元日
            else if ("date".equals(t)) s = "1970-01-01";
            // 日期时间默认纪元时刻
            else if ("datetime".equals(t)) s = "1970-01-01 00:00:00";
        }
        // DateTime/DateTime64 统一走精度感知解析：普通 DateTime 截断小数秒，
        // DateTime64(n) 按目标精度保留，避免把 ".029" 原样交给普通 DateTime。
        if (SyncTime.isTimestamp(t)) {
            String timeValue = s;
            if (timeValue == null || timeValue.trim().isEmpty()
                    || "null".equalsIgnoreCase(timeValue.trim())) {
                timeValue = "1970-01-01 00:00:00";
            }
            try {
                return SyncTime.forTarget(SyncTime.parse(timeValue), t);
            } catch (RuntimeException e) {
                throw new IllegalStateException("无法解析时间 \"" + s + "\"", e);
            }
        }
        // 按类型分发解析
        switch (t) {
            case "string":
            case "lowcardinality(string)":
            case "":
                return s == null ? "" : s;
            // int8 -> Byte
            case "int8":
                return Byte.parseByte(s.trim());
            // int16 -> Short
            case "int16":
                return Short.parseShort(s.trim());
            // int32 -> Integer
            case "int32":
                return Integer.parseInt(s.trim());
            // uint8 无符号,用 Short 承装
            case "uint8":
                return Short.parseShort(s.trim());
            // uint16 用 Integer 承装
            case "uint16":
                return Integer.parseInt(s.trim());
            // uint32 用 Long 承装
            case "uint32":
                return Long.parseLong(s.trim());
            // int64 -> Long
            case "int64":
                return Long.parseLong(s.trim());
            // uint64 超出 long,用 BigInteger
            // Java 无 64 位无符号整型,long 装不下 [2^63, 2^64);BigInteger 才能无损表达,
            // 最终由 CkClient.sqlLiteral 原样拼成数字字面量写回 ClickHouse。
            case "uint64":
                return new java.math.BigInteger(s.trim());
            // float32 -> Float
            case "float32":
                return Float.parseFloat(s.trim());
            // float64 -> Double
            case "float64":
                return Double.parseDouble(s.trim());
            // bool:按多种字面量解析成 Boolean
            case "bool": {
                // 取小写值串
                String v = s == null ? "" : s.trim().toLowerCase();
                switch (v) {
                    // 视为 true 的字面量
                    case "true":
                    // "1" 也视为 true
                    case "1":
                    case "yes":
                    case "y":
                        return Boolean.TRUE;
                    // 视为 false 的字面量
                    case "false":
                    case "0":
                    case "no":
                    case "n":
                    case "":
                        return Boolean.FALSE;
                    // 无法识别的布尔字面量报错
                    default:
                        throw new IllegalStateException("无法解析布尔值 \"" + s + "\"");
                }
            }
            // date:解析为 java.sql.Date
            case "date": {
                try {
                    // 日期文本(可能带时刻)
                    String d = s == null ? "" : s.trim();
                    // 兼容纯日期或带时刻的来源(DuckDB/ClickHouse 的 date 也可能仍带 00:00:00)
        // 超过 10 位则截断为 yyyy-MM-dd
        // 盲截前 10 位而非做格式解析:覆盖 "2020-01-01 00:00:00"/"...T00:00:00Z" 这类
        // “日期+时刻”来源;若分隔符不是 '-' 或格式不规则,截出来的串会在 Date.valueOf 抛错,
        // 错误信息里带原文,便于定位到具体单元格。
        if (d.length() > 10) {
                        d = d.substring(0, 10);
                    }
                    // 日期解析成功,返回该日期
                    return Date.valueOf(d);
                // 非法日期串报错
                } catch (IllegalArgumentException e) {
                    throw new IllegalStateException("无法解析日期 \"" + s + "\"");
                }
            }
            // datetime:解析为 java.sql.Timestamp
            case "datetime": {
                // 先规范化字符串再解析
                try {
                    // 时间解析成功,返回时间戳
                    return Timestamp.valueOf(normalizeDateTime(s));
                // 非法时间串报错
                } catch (IllegalArgumentException e) {
                    throw new IllegalStateException("无法解析时间 \"" + s + "\"");
                }
            }
            // 其余未识别类型一律按原字符串返回
            default:
                return s == null ? "" : s;
        }
    }

    /** 时间字符串规范化:兼容 纯日期/日期时间/毫秒小数,并把 ISO 的 T 分隔改为空格。 */
    static String normalizeDateTime(String s) {
        // null 输入归一为空串
        if (s == null) {
            return "";
        }
        // 去空白
        String v = s.trim();
        // 纯日期 yyyy-MM-dd:补零时刻
        if (v.length() == 10 && v.indexOf('-') == 4) {
            // 返回补全零时刻后的日期时间文本
            return v + " 00:00:00";
        }
        // ISO 8601 的 T 分隔替换为空格
        v = v.replace('T', ' ');
        // 定位毫秒小数点
        int dot = v.indexOf('.');
        // 在小数点前找秒冒号,确认确实是毫秒部分
        if (dot > 0) {
            // 确为毫秒则截断到秒精度
            int sec = v.indexOf(":", dot - 4);
            if (sec > 0 && dot > sec + 1) {
                // 确有毫秒,截断到秒精度
                v = v.substring(0, dot);
            }
        }
        // 返回规范化结果
        return v;
    }

    // 在列名列表中查找目标列下标,未找到返回 -1
    private static int colIndex(List<String> cols, String name) {
        // 线性遍历列名
        for (int i = 0; i < cols.size(); i++) {
            // 名字一致即命中
            if (name.equals(cols.get(i))) {
                // 命中目标列,返回其下标
                return i;
            }
        }
        // 未找到返回 -1
        return -1;
    }

    // =====================================================================
    // 纯工具
    // =====================================================================

    // 解密并反序列化数据源配置(供读取文件/导出表使用)
    private Map<String, Object> decryptConfig(DataSource d) {
        // 无密文配置则返回空 Map
        if (d.getConfigEnc() == null || d.getConfigEnc().isEmpty()) {
            // 未加密存储,直接返回空配置
            return new LinkedHashMap<>();
        }
        // 解密/解析失败统一包装为异常
        try {
            // 解密得到原始配置串
            String raw = Crypto.decrypt(secret, d.getConfigEnc());
            // 反序列化为 Map
            Map<String, Object> m = JSON.readValue(raw, Map.class);
            // 结果为空时兜底返回空 Map
            return m == null ? new LinkedHashMap<String, Object>() : m;
        // 任何失败都以带数据源名的异常抛出
        } catch (Exception e) {
            throw new IllegalStateException(String.format("数据源 \"%s\" 配置解密失败: %s", d.getCode(), msg(e)));
        }
    }

    // 读字符串配置项;非空字符串才采用,否则回退默认值
    private static String cfgStr(Map<String, Object> cfg, String key, String def) {
        // 取配置值
        Object v = cfg.get(key);
        // 仅当值为非空字符串才使用
        if (v instanceof String && !((String) v).isEmpty()) {
            // 取到非空字符串配置并返回
            return (String) v;
        }
        // 其余情况用默认值
        return def;
    }

    // 读整型配置项;支持 Number 与可解析字符串
    private static long cfgInt(Map<String, Object> cfg, String key, long def) {
        // 取配置值
        Object v = cfg.get(key);
        // Number 直接转 long
        if (v instanceof Number) {
            // 数值类型直接取其整型值
            return ((Number) v).longValue();
        }
        // 字符串尝试按 long 解析
        if (v instanceof String) {
            try {
                // 解析成功返回 long
                return Long.parseLong(((String) v).trim());
            // 解析失败回退默认值
            } catch (NumberFormatException e) {
                return def;
            }
        }
        // 其余情况回退默认值
        return def;
    }

    // 读取“首行是否表头”:仅显式 Boolean 生效,缺省 true
    private static boolean headerFromConfig(Map<String, Object> cfg) {
        // 取配置值
        Object h = cfg.get("header");
        // 未显式配置则默认有表头
        return h instanceof Boolean ? (Boolean) h : true;
    }

    // 读取手动列名:兼容逗号分隔字符串与字符串数组
    private static List<String> namesFromConfig(Map<String, Object> cfg) {
        // 取配置值
        Object raw = cfg.get("names");
        // 未配置直接返回 null
        if (raw == null) {
            return null;
        }
        // 输出的列名列表
        List<String> out = new ArrayList<>();
        // 字符串形式:按逗号拆分
        if (raw instanceof String) {
            // 逐段切分
            for (String p : ((String) raw).split(",")) {
                // 段去空格
                String s = p.trim();
                // 非空段才收集
                if (!s.isEmpty()) {
                    // 收集拆分出的非空列名
                    out.add(s);
                }
            }
        // 数组形式:直接遍历元素
        } else if (raw instanceof List) {
            // 仅收非空字符串项
            for (Object it : (List<?>) raw) {
                // 非空字符串入列
                if (it instanceof String && !((String) it).isEmpty()) {
                    out.add((String) it);
                }
            }
        }
        // 收集为空时归一为 null
        return out.isEmpty() ? null : out;
    }

    /** 用反引号包裹 ClickHouse/DuckDB 标识符(内部的 ` 翻倍转义)。 */
    // 加反引号并把内部 ` 转义为 ``
    private static String quoteCK(String s) {
        // null 按空串处理
        return "`" + (s == null ? "" : s.replace("`", "``")) + "`";
    }

    /** SQL 字符串字面量转义:用单引号包裹,内部单引号翻倍。 */
    // 值中单引号翻倍后整体用单引号包裹
    private static String quoteStr(String s) {
        // null 按空串处理
        return "'" + (s == null ? "" : s.replace("'", "''")) + "'";
    }

    // 校验名称是否为合法标识符
    private static boolean validIdent(String name) {
        // 正则整串匹配
        return name != null && IDENT_RE.matcher(name).matches();
    }

    // 线程被中断(取消/超时)则立刻抛出,中止工作流
    // 协作式中断的全部实现:只认标志位,不持有任何自己的取消状态,因此超时/取消由外部
    // (中断执行线程的一方)定义。用 isInterrupted() 而不是 Thread.interrupted():后者会
    // 消费掉标志位,导致后续检查点感知不到,中断就只生效一次。
    // 边界:检查点分布在节点边界、分页边界与脚本逐行;单次 DuckDB/CK 查询内部不可中断,
    // 所以一个超长查询期间取消不会立即生效,延迟上界是“一次查询 + 一批处理”。
    private static void checkCancelled() {
        // 检查当前线程的中断标志
        if (Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("工作流执行已超时或被中止");
        }
    }

    // Long 空值回退:为 null 时用给定默认值
    private static long deref(Long v, long def) {
        // 空值取默认
        return v == null ? def : v;
    }

    // 提取异常链上第一个有信息者的信息
    private static String msg(Throwable e) {
        // 从传入异常开始向上钻取
        Throwable t = e;
        // 只要存在 cause 且当前节点没有信息就继续下钻
        while (t.getCause() != null && (t.getMessage() == null || t.getMessage().isEmpty())) {
            // 继续向异常链上层钻取
            t = t.getCause();
        }
        // 取到用于展示的信息
        String m = t.getMessage();
        // 全空则退化为异常的 toString
        return (m == null || m.isEmpty()) ? String.valueOf(e) : m;
    }

    // =====================================================================
    // JS 引擎薄封装:Nashorn/GraalJS 通过 javax.script 使用(反射读成员,避免编译期依赖)
    // =====================================================================

    /** JS 引擎封装:优先 Nashorn(Java 8 内置),其次 graal.js / javascript。 */
    // 极薄的一层 JS 引擎封装,抹平各引擎差异
    static final class Js {
        // 底层脚本引擎
        private final javax.script.ScriptEngine engine;
        // 函数调用能力(用于调用 transform)
        private final javax.script.Invocable invocable;

        // 构造器:保存引擎并取得其函数调用器
        private Js(javax.script.ScriptEngine engine) {
            // 保存引擎
            this.engine = engine;
            // 强转为 Invocable 以便 invokeFunction
            this.invocable = (javax.script.Invocable) engine;
        }

        /** 加载当前环境可用的 JS 引擎;找不到则返回 null。 */
        // 探测顺序即优先级:内置 Nashorn(Java 8,零依赖、启动最快)→ 外置 nashorn → graal.js
        // → graaljs → 泛化的 javascript 别名。返回 null 时由调用方给出可读错误,而不是让
        // NoClassDefFoundError 冒上去——缺少 JS 引擎只影响代码块节点,不该拖垮整个平台。
        static Js load() {
            // Java 8 自带 Nashorn 时直接通过反射获取，避免类路径上的新版本
            // nashorn-core 服务提供者初始化失败后阻断整个 ScriptEngineManager。
            try {
                Class<?> factoryType = Class.forName("jdk.nashorn.api.scripting.NashornScriptEngineFactory");
                Object factory = factoryType.getDeclaredConstructor().newInstance();
                Object value = factoryType.getMethod("getScriptEngine").invoke(factory);
                if (value instanceof javax.script.ScriptEngine
                        && value instanceof javax.script.Invocable) {
                    return new Js((javax.script.ScriptEngine) value);
                }
            } catch (Throwable ignored) {
                // JDK 15+ 已移除内置 Nashorn，继续尝试外置或 GraalJS。
            }
            // 逐个引擎名探测
            javax.script.ScriptEngineManager mgr = new javax.script.ScriptEngineManager();
            // 优先 Nashorn
            javax.script.ScriptEngine e = mgr.getEngineByName("nashorn");
            // 其次 graal.js
            if (e == null) {
                // 引擎回退:graal.js
                e = mgr.getEngineByName("graal.js");
            }
            // 再试 graaljs
            if (e == null) {
                // 引擎回退:graaljs
                e = mgr.getEngineByName("graaljs");
            }
            // 最后兜底 javascript
            if (e == null) {
                // 引擎回退:javascript
                e = mgr.getEngineByName("javascript");
            }
            // 找不到或不支持函数调用则返回 null
            if (e == null || !(e instanceof javax.script.Invocable)) {
                return null;
            }
            // 找到可用引擎,返回封装对象
            return new Js(e);
        }

        // 执行脚本源码
        void eval(String script) throws Exception {
            // 执行脚本源码
            engine.eval(script);
        }

        // 判断脚本是否已定义 transform 全局函数
        boolean hasTransform() {
            try {
                // 从引擎全局命名空间读取
                Object t = engine.get("transform");
                // 已定义 transform,返回 true
                return t != null;
            // 读取异常视为未定义
            } catch (Exception e) {
                // 读取异常视为未定义,返回 false
                return false;
            }
        }

        /** 求值一段 JSON 对象字面量并返回 JS 对象。 */
        Object evalObj(String jsonLiteral) {
            // 加括号 eval,避免被当作语句块
            try {
                // 括号包裹后求值 JSON 字面量
                return engine.eval("(" + jsonLiteral + ")");
            // 求值失败包装为异常
            } catch (Exception e) {
                throw new IllegalStateException("构建行对象失败: " + msg(e));
            }
        }

        /** 把 JDBC 原始值映射成 JS 对象,保留数字/布尔/日期等原始类型。 */
        Object rowObject(Map<String, Object> row) {
            // 借道引擎全局变量把行对象注入 JS 命名空间
            // 为什么绕一圈而不是直接把 Map 递给 transform:直接传 Java Map 时,脚本侧拿到的是
            // “Java 对象”而非 JS 对象,row.col 取成员的行为随引擎而异;经引擎自身的 put/get
            // 往返后,引擎会把它包装成自己的对象表示,属性访问语义与普通 JS 对象一致。
            engine.put("__el2ck_row", row);
            // 取回即得到引擎原生的行对象
            try {
                // 取回即得到 JS 引擎原生的行对象
                return engine.get("__el2ck_row");
            // 用完务必清理该全局变量
            } finally {
                // 用完清理该临时全局变量
                // 置 null 而非删除:避免在全局命名空间长期挂住最后一行数据的引用(内存与可见性
                // 两个角度);代价是该全局名在脚本中始终存在(值为 null),脚本不应依赖它。
                engine.put("__el2ck_row", null);
            }
        }

        // 调用脚本定义的 transform(row)
        Object transform(Object rowObj) throws Exception {
            // 调用脚本定义的 transform(row) 并返回结果
            return invocable.invokeFunction("transform", rowObj);
        }

        /** 读取 JS 结果对象某成员:兼容 Map / ScriptObjectMirror / 通用对象。 */
        // 从结果对象中取指定列
        static String member(Object obj, String key) {
            // 空对象无成员
            if (obj == null) {
                return null;
            }
            // Java Map:直接按键取值
            if (obj instanceof Map) {
                // 取 Map 中对应成员值
                Object v = ((Map<?, ?>) obj).get(key);
                // 空值归一后转为字符串返回
                return v == null ? null : String.valueOf(v);
            }
            // 反射式读成员:依次尝试 getMember / get / getString,兼容不同引擎(Nashorn/GraalJS)
        // 逐个尝试候选方法名
        // 用反射而非编译期接口是为了不依赖具体引擎的 jar(各引擎返回类型不同);候选顺序即
        // 优先级,且只匹配“单个 String 入参”的签名,避免误命中 get(int) 之类的重载。
        for (String mn : new String[]{"getMember", "get", "getString"}) {
                // 反射查找单字符串入参的读方法
                try {
                    // 反射查找以字符串为参数的读成员方法
                    Method m = obj.getClass().getMethod(mn, String.class);
                    // 跳过返回 void 的方法
                    if (m.getReturnType() != Void.TYPE) {
                        // 反射调用读取成员值
                        Object v = m.invoke(obj, key);
                        // 非空且非 undefined 才返回
                        if (v != null && !"undefined".equals(String.valueOf(v))) {
                            // 反射调用成功,返回成员字符串值
                            return String.valueOf(v);
                        }
                    }
                // 引擎不支持该方法,尝试下一个候选
                } catch (NoSuchMethodException ignored) {
                // 其它反射异常按读取失败处理
                } catch (Exception ignored) {
                    // 反射调用失败时忽略该成员
                }
            }
            // 全部失败则返回 null
            // 语义:该列在 transform 返回值里取不到有效成员(缺失或 undefined),由调用方写成
            // 空串。刻意不做成“整行失败”,否则脚本少返回一个字段就会让整个节点中断。
            return null;
        }
    }
}

