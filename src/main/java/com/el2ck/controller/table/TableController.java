/**
 * 【文件职责】本文件定义表管理与数据编辑模块的HTTP 控制器 TableController。接收 Web 请求并完成路由与参数适配，统一封装响应；核心校验、权限和持久化逻辑委托给服务层。
 */

package com.el2ck.controller.table;

import com.el2ck.controller.common.ApiResponse;
import com.el2ck.controller.common.ApiSupport;
import com.el2ck.controller.common.CsvResponse;

import com.el2ck.entity.table.DataEditRequest;
import com.el2ck.entity.table.ExportRequest;
import com.el2ck.entity.table.FilterNode;
import com.el2ck.entity.table.GroupByRequest;
import com.el2ck.entity.table.MaterializeRequest;
import com.el2ck.entity.table.SortSpec;
import com.el2ck.entity.table.TableAlterRequest;
import com.el2ck.service.table.TableService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 表管理 HTTP 接口(对应 Go internal/api/table_api.go)。
 *
 * 覆盖:表列表/建表/结构/键/DDL、数据分页预览(带过滤树)、单元格编辑、
 * 删表/清空/结构变更、CSV 导出。目标 ClickHouse 失败统一 502。
 *
 * 供前端「数据仓库工作台」(/workspace)在选定连接、库后管理表:库树选表 → 预览/编辑数据、
 * 查看结构与 DDL;Basic Auth 保护(配置了 user 时)。
 */
@RestController
@RequestMapping("/api/connections/{id}/databases/{db}/tables")
public class TableController {

    // 依赖的表服务:负责表结构、数据分页与各类表级操作
    private final TableService svc;

    // 构造器注入表服务
    /**
     * 创建ClickHouse 表管理 HTTP 控制器并装配后续调用所需的依赖。
     *
     * 依赖由 Spring 容器统一管理；本构造器只保存引用或初始化轻量适配对象，不执行远程业务操作。
     *
     * @param svc 该控制器对应的领域业务服务，承载实际校验和数据处理
     */
    public TableController(TableService svc) {
        // 保存服务引用供接口调用
        this.svc = svc;
    }

    /** 建表请求体(仅 DDL)。 */
    public static class CreateReq {
        // 建表 DDL 语句
        public String ddl;
    }

    /** 数据分页预览请求体(对应 Go inline struct)。 */
    public static class DataReq {
        // 分页起始偏移量
        public int offset;
        // 单页条数(页大小)
        public int size;
        // 过滤树(支持复合条件)
        public FilterNode filter;
        // 需要返回的列(为空时返回全部列)
        public List<String> columns;
        // 是否同时统计总行数
        public Boolean withCount;
        // 排序列;与导出共用同一份渲染,前端点列头的排序就靠它
        public List<SortSpec> orders;
    }

    /** GET .../tables 表列表。 */
    @GetMapping
    public ApiResponse list(@PathVariable("id") String id, @PathVariable("db") String db) {
        // 解析连接 ID 后列出指定库下的全部表
        return ApiResponse.ok(svc.list(ApiSupport.parseId(id), db));
    }

    /** POST .../tables 按 DDL 建表。 */
    @PostMapping
    public ApiResponse create(@PathVariable("id") String id, @PathVariable("db") String db,
                              @RequestBody(required = false) CreateReq body) {
        // 请求体可能为空,取 ddl 字段做空保护
        String ddl = body == null ? null : body.ddl;
        // 在目标连接上按受控生命周期执行建表 SQL(占用表名、临时授权、核对、授权)
        svc.createBySQL(ApiSupport.parseId(id), db, ddl);
        // 建表成功返回 {ok:true}
        return ApiResponse.ok(okMap());
    }

    /** GET .../tables/{table}/schema 表结构。 */
    @GetMapping("/{table}/schema")
    public ApiResponse schema(@PathVariable("id") String id, @PathVariable("db") String db,
                              @PathVariable("table") String table) {
        // 返回表的字段/类型等结构信息
        return ApiResponse.ok(svc.schema(ApiSupport.parseId(id), db, table));
    }

    /** GET .../tables/{table}/comment 表注释。 */
    @GetMapping("/{table}/comment")
    public ApiResponse comment(@PathVariable("id") String id, @PathVariable("db") String db,
                               @PathVariable("table") String table) {
        return ApiResponse.ok(Collections.singletonMap("comment", svc.comment(ApiSupport.parseId(id), db, table)));
    }

    /** GET .../tables/{table}/keys 主键/分区键/排序键。 */
    @GetMapping("/{table}/keys")
    public ApiResponse keys(@PathVariable("id") String id, @PathVariable("db") String db,
                            @PathVariable("table") String table) {
        // 返回表的主键/分区键/排序键定义
        return ApiResponse.ok(svc.keys(ApiSupport.parseId(id), db, table));
    }

    /** GET .../tables/{table}/ddl 建表语句。 */
    @GetMapping("/{table}/ddl")
    public ApiResponse ddl(@PathVariable("id") String id, @PathVariable("db") String db,
                           @PathVariable("table") String table) {
        // 返回可重建该表的完整 DDL 语句
        return ApiResponse.ok(svc.ddl(ApiSupport.parseId(id), db, table));
    }

    /** GET .../tables/{table}/check 表体检(只读)。 */
    @GetMapping("/{table}/check")
    public ApiResponse check(@PathVariable("id") String id, @PathVariable("db") String db,
                             @PathVariable("table") String table) {
        // 把平台的护栏(列类型 / 默认值 / 分区键)一次问出来:只报告不阻断,不做任何写操作
        return ApiResponse.ok(svc.checkTable(ApiSupport.parseId(id), db, table));
    }

    /** POST .../tables/{table}/group-by 按维度做分布统计(画像查询)。 */
    @PostMapping("/{table}/group-by")
    public ApiResponse groupBy(@PathVariable("id") String id, @PathVariable("db") String db,
                               @PathVariable("table") String table,
                               @RequestBody(required = false) GroupByRequest body) {
        // 过滤条件与数据页同源:返回「值 + 条数 + 占比分母」,分布在后端 GROUP BY 全表算
        return ApiResponse.ok(svc.groupBy(ApiSupport.parseId(id), db, table, body));
    }

    /** POST .../tables/{table}/optimize 整理表(OPTIMIZE TABLE ... FINAL 合并数据片段)。 */
    @PostMapping("/{table}/optimize")
    public ApiResponse optimize(@PathVariable("id") String id, @PathVariable("db") String db,
                                @PathVariable("table") String table) {
        // 合并只影响物理结构、不动数据;执行后服务端会失效该库的表统计缓存
        svc.optimize(ApiSupport.parseId(id), db, table);
        // 整理成功返回 {ok:true}(合并是异步的,行数/大小随后才真正变小)
        return ApiResponse.ok(okMap());
    }

    /** POST .../tables/{table}/materialize 把圈选结果落成一张表(「数据包」)。 */
    @PostMapping("/{table}/materialize")
    public ApiResponse materialize(@PathVariable("id") String id, @PathVariable("db") String db,
                                   @PathVariable("table") String table,
                                   @RequestBody(required = false) MaterializeRequest body) {
        // dryRun=true 时只回建表语句不落库;真落库是写操作,只读连接会被服务层拒(403)
        return ApiResponse.ok(svc.materialize(ApiSupport.parseId(id), db, table, body));
    }

    /** POST .../tables/{table}/data 分页预览(支持过滤条件)。 */
    @PostMapping("/{table}/data")
    public ApiResponse data(@PathVariable("id") String id, @PathVariable("db") String db,
                            @PathVariable("table") String table, @RequestBody(required = false) DataReq body) {
        // 解析连接数字 ID
        long cid = ApiSupport.parseId(id);
        // 请求体为空时按默认分页参数处理
        if (body == null) {
            body = new DataReq();
        }
        // withCount 只在显式为 true 时才统计总数
        boolean withCount = body.withCount != null && body.withCount;
        // 按偏移/页大小/过滤条件/列清单/排序查询分页数据并装入成功信封;
        // 注意夹取在服务层做:offset<0 归零、size 超出 (0,1000] 时回退默认 100 行(故请求体缺省 = 首页 100 行)
        return ApiResponse.ok(svc.data(cid, db, table, body.offset, body.size,
                body.filter, body.columns, withCount, body.orders));
    }

    /** POST .../tables/{table}/data/edit 单元格级数据增删改。 */
    @PostMapping("/{table}/data/edit")
    public ApiResponse editData(@PathVariable("id") String id, @PathVariable("db") String db,
                                @PathVariable("table") String table, @RequestBody(required = false) DataEditRequest body) {
        // 执行单元格级数据变更并返回影响行数等结果
        return ApiResponse.ok(svc.editData(ApiSupport.parseId(id), db, table, body));
    }

    /** DELETE .../tables/{table} 删除表。 */
    @DeleteMapping("/{table}")
    public ApiResponse drop(@PathVariable("id") String id, @PathVariable("db") String db,
                            @PathVariable("table") String table) {
        // 解析连接 ID 后删除指定表
        svc.drop(ApiSupport.parseId(id), db, table);
        // 删除成功返回 {ok:true}
        return ApiResponse.ok(okMap());
    }

    /** POST .../tables/{table}/truncate 清空表。 */
    @PostMapping("/{table}/truncate")
    public ApiResponse truncate(@PathVariable("id") String id, @PathVariable("db") String db,
                                @PathVariable("table") String table) {
        // 清空表中全部数据(保留结构)
        svc.truncate(ApiSupport.parseId(id), db, table);
        // 清空成功返回 {ok:true}
        return ApiResponse.ok(okMap());
    }

    /** PUT .../tables/{table}/alter 修改表结构。 */
    @PutMapping("/{table}/alter")
    public ApiResponse alter(@PathVariable("id") String id, @PathVariable("db") String db,
                             @PathVariable("table") String table, @RequestBody(required = false) TableAlterRequest body) {
        // 按请求体执行增删列/改类型等结构变更
        svc.alter(ApiSupport.parseId(id), db, table, body);
        // 变更成功返回 {ok:true}
        return ApiResponse.ok(okMap());
    }

    /**
     * POST .../tables/{table}/export 按过滤条件导出 CSV(不受分页限制)。
     *
     * <p>⚠️ 响应体是<b>裸 CSV 流</b>,不是 JSON 信封:前端拿到的就是文件本身
     * ({@code postBlob} + {@code downloadBlob('xxx.csv')})。返回 JSON 的话,用户下载到的
     * .csv 里会是一段 JSON —— 这正是本端点此前的问题。
     *
     * <p>先用 prepareExport 把「列不存在」这类错误在读任何数据行之前抛出来(那时还能返回
     * JSON 错误对象),再进流式写出。
     */
    @PostMapping("/{table}/export")
    public void export(@PathVariable("id") String id, @PathVariable("db") String db,
                       @PathVariable("table") String table,
                       @RequestBody(required = false) ExportRequest body,
                       HttpServletResponse resp) throws IOException {
        // 准备阶段:校验 + 组装查询(只探列,不读数据行)。失败时还来得及抛成 JSON 错误。
        TableService.TableExportPlan plan = svc.prepareExport(ApiSupport.parseId(id), db, table, body);
        try {
            // 写出阶段:逐行流式写 CSV,服务端不攒整份。
            CsvResponse.write(resp, "", w -> plan.writeTo(w));
        } finally {
            // 无论写成功、写失败还是客户端中途断开,这条专用连接都要还回去。
            plan.close();
        }
    }

    // 便捷工具:组装单键 {ok:true} 的 Map
    /**
     * 构造控制器成功响应使用的单键映射。
     *
     * @return 仅包含 {@code ok=true} 的不可变映射
     */
    private static Map<String, Boolean> okMap() {
        // 布尔单键 Map 表示操作成功
        return Collections.singletonMap("ok", Boolean.TRUE);
    }
}
