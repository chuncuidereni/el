/**
 * 【文件职责】本文件定义查询工作流模块的HTTP 控制器 QueryWorkflowController。接收 Web 请求并完成路由与参数适配，统一封装响应；核心校验、权限和持久化逻辑委托给服务层。
 */

package com.el2ck.controller.queryworkflow;

import com.el2ck.controller.common.ApiResponse;
import com.el2ck.controller.common.ApiSupport;
import com.el2ck.controller.common.CsvResponse;

import com.el2ck.entity.queryworkflow.QueryWorkflowConfig;
import com.el2ck.entity.queryworkflow.QueryWorkflowInput;
import com.el2ck.exception.ApiException;
import com.el2ck.service.query.QueryService;
import com.el2ck.service.queryworkflow.QueryWorkflowService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * 查询工作流(4 步向导:多表查询,只 SELECT 不落库)HTTP 接口,
 * 对应 Go {@code internal/api/query_workflow_api.go} 的 12 个端点。
 *
 * <p>本层只做「取参数 → 调服务 → 装信封」:校验、SQL 生成、运行历史、取消全在
 * {@link QueryWorkflowService}。
 */
@RestController
@RequestMapping("/api/query-workflows")
public class QueryWorkflowController {

    /** 查询工作流服务。 */
    private final QueryWorkflowService svc;
    /**
     * 创建查询工作流 HTTP 控制器并装配后续调用所需的依赖。
     *
     * 依赖由 Spring 容器统一管理；本构造器只保存引用或初始化轻量适配对象，不执行远程业务操作。
     *
     * @param svc 该控制器对应的领域业务服务，承载实际校验和数据处理
     */
    public QueryWorkflowController(QueryWorkflowService svc) {
        this.svc = svc;
    }

    // ---- 请求体:与 Go 的匿名结构体逐字段对应 ----

    /** 试跑入参{@code {config, run, limit}}(对应 Go 的 {@code Preview} 请求体)。 */
    public static class PreviewRequest {
        /** 向导配置;未保存的配置也能试跑。 */
        public QueryWorkflowConfig config;
        /** 是否顺带执行一次;缺省 false(只生成 SQL)。 */
        public Boolean run;
        /** 试跑条数;仅 run=true 时生效,服务端强制压到 50 以内。 */
        public Integer limit;
    }

    /** 可视化执行入参{@code {config, page, pageSize}}。 */
    public static class ExecuteRequest {
        /** 向导配置。 */
        public QueryWorkflowConfig config;
        /** 页码;仅 pageSize>0 时生效。 */
        public Integer page;
        /** 每页行数;大于 0 时走服务端分页。 */
        public Integer pageSize;
    }

    /** 可视化导出入参:在通用导出选项上,用 config 取代 sql。 */
    public static class ExportRequest {
        /** 向导配置。 */
        public QueryWorkflowConfig config;
        /** 导出哪些列;空 = 全部。 */
        public List<String> columns;
        /** 表头显示名(与 columns 按下标对应)。 */
        public List<String> headerNames;
        /** 是否输出表头行。 */
        public Boolean withHeader;
        /** 分隔符;空 = 逗号。 */
        public String delimiter;
        /** 最大导出行数;空 = 不限(服务端仍封顶)。 */
        public Integer limit;
    }

    /** 运行入参{@code {page, pageSize}}:允许空 body(沿用「截断到 1000 行」的老语义)。 */
    public static class RunRequest {
        /** 页码。 */
        public Integer page;
        /** 每页行数。 */
        public Integer pageSize;
    }

    // ---- 配置的增删改查 ----

    /** GET /api/query-workflows 列表。 */
    @GetMapping
    public ApiResponse list() {
        return ApiResponse.ok(svc.list());
    }

    /** GET /api/query-workflows/{id} 详情。 */
    @GetMapping("/{id}")
    public ApiResponse get(@PathVariable("id") String id) {
        return ApiResponse.ok(svc.get(parseId(id)));
    }

    /** POST /api/query-workflows 新建。 */
    @PostMapping
    public ApiResponse create(@RequestBody(required = false) QueryWorkflowInput body) {
        return ApiResponse.ok(svc.create(body));
    }

    /** PUT /api/query-workflows/{id} 更新。 */
    @PutMapping("/{id}")
    public ApiResponse update(@PathVariable("id") String id,
                              @RequestBody(required = false) QueryWorkflowInput body) {
        return ApiResponse.ok(svc.update(parseId(id), body));
    }

    /** DELETE /api/query-workflows/{id} 删除。 */
    @DeleteMapping("/{id}")
    public ApiResponse delete(@PathVariable("id") String id) {
        long workflowId = parseId(id);
        svc.delete(workflowId);
        // 与 Go 一致:回显被删除的 ID
        return ApiResponse.ok(Collections.singletonMap("id", workflowId));
    }

    // ---- 生成 / 试跑 / 执行 / 导出 ----

    /** POST /api/query-workflows/{id}/preview-sql 只生成 SQL,不执行。 */
    @PostMapping("/{id}/preview-sql")
    public ApiResponse previewSql(@PathVariable("id") String id) {
        return ApiResponse.ok(svc.previewSql(parseId(id)));
    }

    /** POST /api/query-workflows/preview 生成 SQL(配置不必先保存；run=true 暂不允许)。 */
    @PostMapping("/preview")
    public ApiResponse preview(@RequestBody(required = false) PreviewRequest body) {
        requireBody(body);
        return ApiResponse.ok(svc.preview(body.config,
                Boolean.TRUE.equals(body.run),
                body.limit == null ? 0 : body.limit));
    }

    /** POST /api/query-workflows/execute 按配置生成 SQL 并执行。 */
    @PostMapping("/execute")
    public ApiResponse execute(@RequestBody(required = false) ExecuteRequest body) {
        requireBody(body);
        return ApiResponse.ok(svc.execute(body.config, intOf(body.page), intOf(body.pageSize)));
    }

    /**
     * POST /api/query-workflows/export 按配置导出 CSV。
     *
     * <p>⚠️ 响应体是<b>裸 CSV 流</b>,不是 JSON 信封 —— 前端 {@code postBlob} 拿到的就是文件本身。
     * 准备阶段(挑语句、探列、校验列、组装投影)先跑完,那时还能返回 JSON 错误对象;
     * 一旦写出第一个字节,状态码就改不了了。
     */
    @PostMapping("/export")
    public void export(@RequestBody(required = false) ExportRequest body,
                       HttpServletResponse resp) throws IOException {
        requireBody(body);
        QueryService.QueryExportPlan plan = svc.exportPlan(body.config, body.columns,
                body.headerNames, Boolean.TRUE.equals(body.withHeader), body.delimiter, body.limit);
        try {
            CsvResponse.write(resp, "", w -> plan.writeTo(w));
        } finally {
            // 写成功/写失败/客户端中断,这条专用连接都要还回去
            plan.close();
        }
    }

    // ---- 运行 / 取消 / 历史 ----

    /** POST /api/query-workflows/{id}/run 执行 SELECT 并记一次运行历史。 */
    @PostMapping("/{id}/run")
    public ApiResponse run(@PathVariable("id") String id,
                           @RequestBody(required = false) RunRequest body) {
        // 允许空 body:不传分页时沿用「截断到 1000 行 + truncated 标记」的老语义
        return ApiResponse.ok(svc.run(parseId(id), intOf(body == null ? null : body.page),
                intOf(body == null ? null : body.pageSize)));
    }

    /** POST /api/query-workflows/runs/{runId}/cancel 取消一次进行中的运行。 */
    @PostMapping("/runs/{runId}/cancel")
    public ApiResponse cancel(@PathVariable("runId") String runId) {
        svc.cancel(parseId(runId));
        // 与 Go 一致:回 {cancelled:true};不可取消时由服务层抛 400
        return ApiResponse.ok(Collections.singletonMap("cancelled", true));
    }

    /** GET /api/query-workflows/{id}/runs 运行历史。 */
    @GetMapping("/{id}/runs")
    public ApiResponse runs(@PathVariable("id") String id,
                            @RequestParam(value = "limit", required = false) String limit) {
        // limit 的宽松解析与 Go 同口径:非法值/越界值一律回落到默认条数(20),不报 400
        return ApiResponse.ok(svc.runs(parseId(id), parseLimit(limit)));
    }

    // ---- 参数解析 ----

    /**
     * 解析路径里的 ID,并要求为正。
     *
     * <p>与 Go 的 {@code parseQwid} 一致:{@code id <= 0} 也判定为「ID 非法」,
     * 而不是让它落进仓储层变成一次查不到(那会得到 404,与 Go 的 400 不是一回事)。
     */
    private static long parseId(String raw) {
        long id = ApiSupport.parseId(raw);
        if (id <= 0) {
            throw ApiException.validation("ID 非法");
        }
        return id;
    }

    /** 宽松解析 limit:只有 1..200 才生效,其余返回 0(服务层回落到默认条数)。 */
    private static int parseLimit(String raw) {
        if (raw == null || raw.isEmpty()) {
            return 0;
        }
        try {
            int n = Integer.parseInt(raw.trim());
            return (n > 0 && n <= 200) ? n : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** null 安全的 Integer → int。 */
    private static int intOf(Integer value) {
        return value == null ? 0 : value;
    }

    /** 请求体缺失时的统一报错(与 Go 的 {@code ShouldBindJSON} 失败同为 400)。 */
    private static void requireBody(Object body) {
        if (body == null) {
            throw ApiException.validation("请求体非法: 请求体不能为空");
        }
    }
}
