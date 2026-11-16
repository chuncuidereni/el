/**
 * 【文件职责】本文件定义ARB 查询编排模块的HTTP 控制器 ArbWorkflowController。接收 Web 请求并完成路由与参数适配，统一封装响应；核心校验、权限和持久化逻辑委托给服务层。
 */

package com.el2ck.controller.arb;

import com.el2ck.controller.common.ApiResponse;
import com.el2ck.controller.common.ApiSupport;
import com.el2ck.controller.query.QueryController;

import com.el2ck.entity.arb.ArbWorkflowInput;
import com.el2ck.entity.arb.ArbWorkflowRun;
import com.el2ck.exception.ApiException;
import com.el2ck.service.arb.ArbWorkflowService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 多源仲裁工作流 HTTP 接口,对应 Go {@code internal/api/arb_workflow_api.go} 的 12 个端点。
 *
 * <pre>
 * GET    /api/arb-workflows                  列表
 * POST   /api/arb-workflows                  新建
 * POST   /api/arb-workflows/preview          预览全部 SQL(不连库、不建表)
 * GET    /api/arb-workflows/{id}             详情
 * PUT    /api/arb-workflows/{id}             更新
 * DELETE /api/arb-workflows/{id}             删除配置(不动物化视图与聚合表)
 * POST   /api/arb-workflows/{id}/setup       生成:聚合表 + 每源 MV + 回填历史
 * POST   /api/arb-workflows/{id}/rebuild     重建聚合层(清空重算,修 sum 翻倍/幽灵行)
 * POST   /api/arb-workflows/{id}/flatten     只摊平服务表
 * GET    /api/arb-workflows/{id}/probe       现状:表在不在、视图齐不齐、多少行
 * POST   /api/arb-workflows/{id}/drop-objects 删除 ClickHouse 里的产物(不可逆)
 * GET    /api/arb-workflows/{id}/runs        执行历史(?limit=N,默认 20,上限 200)
 * </pre>
 *
 * <p>本层只做「取参数 → 调服务 → 装信封」。启动时会有后台循环自动摊平落后的服务表
 * (见 {@code ArbFlattenJob}),那是唯一不经 HTTP 的入口。
 *
 * <h3>与 Go 的一处差异:长任务超时</h3>
 *
 * Go 把 setup / rebuild / flatten / probe / drop-objects 挂在 longTaskGroup 下(30 分钟超时),
 * 而 Java 侧没有全局的长任务机制(见 {@code QueryController} 的说明):这几个端点同步执行,
 * 服务端不掐请求,超时上限由前端 axios 自己把握。
 */
@RestController
@RequestMapping("/api/arb-workflows")
public class ArbWorkflowController {

    /** 运行历史的默认条数(前端不传 limit 时)。 */
    private static final int RUNS_LIMIT_DEFAULT = 20;

    /** 运行历史的条数上限。上限必须卡住:记录里存着整段 SQL,放开等于让一个请求把大字段全捞出来。 */
    private static final int RUNS_LIMIT_MAX = 200;

    /** 工作流服务。 */
    private final ArbWorkflowService svc;
    /**
     * 创建ARB 工作流 HTTP 控制器并装配后续调用所需的依赖。
     *
     * 依赖由 Spring 容器统一管理；本构造器只保存引用或初始化轻量适配对象，不执行远程业务操作。
     *
     * @param svc 该控制器对应的领域业务服务，承载实际校验和数据处理
     */
    public ArbWorkflowController(ArbWorkflowService svc) {
        this.svc = svc;
    }

    /** GET /api/arb-workflows 列表。 */
    @GetMapping
    public ApiResponse list() {
        return ApiResponse.ok(svc.list());
    }

    /** GET /api/arb-workflows/{id} 详情。 */
    @GetMapping("/{id}")
    public ApiResponse get(@PathVariable("id") String id) {
        return ApiResponse.ok(svc.get(parseId(id)));
    }

    /** POST /api/arb-workflows 新建。 */
    @PostMapping
    public ApiResponse create(@RequestBody(required = false) ArbWorkflowInput body) {
        requireBody(body);
        return ApiResponse.ok(svc.create(body));
    }

    /** PUT /api/arb-workflows/{id} 更新。 */
    @PutMapping("/{id}")
    public ApiResponse update(@PathVariable("id") String id,
                              @RequestBody(required = false) ArbWorkflowInput body) {
        requireBody(body);
        return ApiResponse.ok(svc.update(parseId(id), body));
    }

    /** DELETE /api/arb-workflows/{id} 删除配置(不删 ClickHouse 产物)。 */
    @DeleteMapping("/{id}")
    public ApiResponse delete(@PathVariable("id") String id) {
        long workflowId = parseId(id);
        svc.delete(workflowId);
        // 与 Go 一致:回显被删除的工作流 ID
        return ApiResponse.ok(Collections.singletonMap("id", workflowId));
    }

    /**
     * POST /api/arb-workflows/preview 预览全部 SQL(不连库、不建表)。
     *
     * <p>配置不必先保存:向导里配到一半就要能核对「我配的到底是哪些 SQL」。
     */
    @PostMapping("/preview")
    public ApiResponse preview(@RequestBody(required = false) ArbWorkflowInput body) {
        requireBody(body);
        return ApiResponse.ok(svc.preview(body));
    }

    /** POST /api/arb-workflows/{id}/setup 生成:聚合表 + 每源 MV + 回填历史。 */
    @PostMapping("/{id}/setup")
    public ApiResponse setup(@PathVariable("id") String id) {
        return ApiResponse.ok(svc.apply(parseId(id), ArbWorkflowRun.ACTION_SETUP));
    }

    /** POST /api/arb-workflows/{id}/rebuild 重建聚合层(与 setup 是同一个操作,只是记录里叫法不同)。 */
    @PostMapping("/{id}/rebuild")
    public ApiResponse rebuild(@PathVariable("id") String id) {
        return ApiResponse.ok(svc.apply(parseId(id), ArbWorkflowRun.ACTION_REBUILD));
    }

    /** POST /api/arb-workflows/{id}/flatten 只摊平服务表。 */
    @PostMapping("/{id}/flatten")
    public ApiResponse flatten(@PathVariable("id") String id) {
        return ApiResponse.ok(svc.flatten(parseId(id)));
    }

    /** GET /api/arb-workflows/{id}/probe 现状:表在不在、视图齐不齐、多少行。 */
    @GetMapping("/{id}/probe")
    public ApiResponse probe(@PathVariable("id") String id) {
        return ApiResponse.ok(svc.probe(parseId(id)));
    }

    /**
     * POST /api/arb-workflows/{id}/drop-objects 删除产物(不可逆)。
     *
     * <p>删聚合表与所有物化视图。界面必须二次确认 —— 仲裁结果会一起没掉。
     */
    @PostMapping("/{id}/drop-objects")
    public ApiResponse dropObjects(@PathVariable("id") String id) {
        long workflowId = parseId(id);
        svc.dropObjects(workflowId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", workflowId);
        data.put("dropped", Boolean.TRUE);
        return ApiResponse.ok(data);
    }

    /** GET /api/arb-workflows/{id}/runs 执行历史(?limit=N)。 */
    @GetMapping("/{id}/runs")
    public ApiResponse runs(@PathVariable("id") String id, @RequestParam(value = "limit", required = false) String limit) {
        return ApiResponse.ok(svc.runs(parseId(id), runsLimit(limit)));
    }
    /**
     * 处理 {@code POST /api/arb-workflows/{id}/start} 请求：启动指定 ARB 工作流，并按 action 选择业务层支持的启动动作。
     *
     * 工作流 ID 和动作由服务层校验；成功时返回服务产生的结果，权限不足或状态不允许时通过统一异常处理返回错误。
     *
     * @param id 用户 ID
     * @param action 动作类型；普通审计中为动作名，工作流启动时由业务层解释
     * @return 统一 {@link com.el2ck.controller.common.ApiResponse}；成功时封装业务结果，失败由全局异常处理器转换
     */
    @PostMapping("/{id}/start")
    public ApiResponse start(@PathVariable("id") String id, @RequestParam("action") String action) {
        return ApiResponse.ok(Collections.singletonMap("runId", svc.startBackground(parseId(id), action)));
    }
    /**
     * 处理 {@code GET /api/arb-workflows/{id}/runs/{runId}} 请求：查询指定 ARB 工作流的单次运行状态。
     *
     * 返回运行状态、阶段和错误信息等业务层结果，用于页面轮询执行进度。
     *
     * @param id 用户 ID
     * @param runId 工作流运行记录 ID
     * @return 统一 {@link com.el2ck.controller.common.ApiResponse}；成功时封装业务结果，失败由全局异常处理器转换
     */
    @GetMapping("/{id}/runs/{runId}")
    public ApiResponse runStatus(@PathVariable("id") String id, @PathVariable("runId") String runId) {
        return ApiResponse.ok(svc.runStatus(parseId(id), parseId(runId)));
    }

    // =====================================================================
    // 参数解析
    // =====================================================================

    /**
     * 解析 ?limit=:默认 20,夹到 1..200。
     *
     * <p>空值/非数字落回默认;超范围则夹到边界(前端传 limit=1000 时给 200 条,
     * 比「整段忽略、只给 20 条」更符合调用方的预期)。
     */
    static int runsLimit(String raw) {
        if (raw == null) {
            return RUNS_LIMIT_DEFAULT;
        }
        int n;
        try {
            n = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return RUNS_LIMIT_DEFAULT;
        }
        if (n < 1) {
            return 1;
        }
        if (n > RUNS_LIMIT_MAX) {
            return RUNS_LIMIT_MAX;
        }
        return n;
    }

    /** 路径 ID:非数字与 &lt;= 0 都按 400(对应 Go parseIDParam,不落到仓储去换来一个 404)。 */
    private static long parseId(String id) {
        long value = ApiSupport.parseId(id);
        if (value <= 0) {
            throw ApiException.validation("ID 非法");
        }
        return value;
    }

    /** 请求体可以缺省(required=false),但业务上必需 —— 缺了按 400 而不是 500。 */
    private static void requireBody(Object body) {
        if (body == null) {
            throw ApiException.validation("请求体非法: 请求体不能为空");
        }
    }
}
