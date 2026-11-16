/**
 * 【文件职责】本文件定义数据同步模块的HTTP 控制器 SyncTaskController。接收 Web 请求并完成路由与参数适配，统一封装响应；核心校验、权限和持久化逻辑委托给服务层。
 */

package com.el2ck.controller.sync;

import com.el2ck.controller.common.ApiResponse;
import com.el2ck.controller.common.ApiSupport;

import com.el2ck.entity.sync.SyncTaskInput;
import com.el2ck.exception.ApiException;
import com.el2ck.service.sync.SyncService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 同步任务 HTTP 接口(对应 Go internal/api/sync_api.go)。
 *
 * 同步任务把本地文件(CSV/JSON)数据源按映射与策略写入目标 ClickHouse。
 * Run 为异步触发:立即返回 {runId, status:"running"},实际执行/结果落在后台线程。
 *
 * 供前端「同步任务」页(/sync-tasks)调用;Basic Auth 保护(配置了 user 时)。
 */
@RestController
@RequestMapping("/api/sync-tasks")
public class SyncTaskController {

    // 依赖的同步服务:负责任务 CRUD、运行历史与异步触发
    private final SyncService svc;

    // 构造器注入同步服务
    /**
     * 创建同步任务 HTTP 控制器并装配后续调用所需的依赖。
     *
     * 依赖由 Spring 容器统一管理；本构造器只保存引用或初始化轻量适配对象，不执行远程业务操作。
     *
     * @param svc 该控制器对应的领域业务服务，承载实际校验和数据处理
     */
    public SyncTaskController(SyncService svc) {
        // 保存服务引用供接口调用
        this.svc = svc;
    }

    /** GET /api/sync-tasks 任务列表。 */
    @GetMapping
    public ApiResponse list() {
        // 查询全部同步任务列表并装入成功信封
        return ApiResponse.ok(svc.list());
    }

    /** 任务页只查询当前所有者当前页；原无参数列表接口保留给旧调用方。 */
    @GetMapping("/page")
    public ApiResponse page(@RequestParam(value="page", defaultValue="1") int page,
                            @RequestParam(value="size", defaultValue="10") int size,
                            @RequestParam(value="keyword", defaultValue="") String keyword) {
        return ApiResponse.ok(svc.page(page, size, keyword));
    }

    @GetMapping("/{id}")
    public ApiResponse detail(@PathVariable("id") String id) {
        return ApiResponse.ok(svc.detail(ApiSupport.parseId(id)));
    }

    @GetMapping("/statuses")
    public ApiResponse statuses(@RequestParam("ids") java.util.List<Long> ids) {
        return ApiResponse.ok(svc.runtime(ids));
    }

    /** 预览 MySQL 日期分表，实际手动/定时执行仍会重新发现表。 */
    @PostMapping("/mysql-date-tables/preview")
    public ApiResponse previewMysqlDateTables(@RequestBody(required = false) SyncTaskInput in) {
        return ApiResponse.ok(svc.previewMysqlDateTables(in));
    }

    /** POST /api/sync-tasks 新增任务。 */
    @PostMapping
    public ApiResponse create(@RequestBody(required = false) SyncTaskInput in) {
        // 交由服务创建任务并返回生成后的任务对象
        return ApiResponse.ok(svc.create(in));
    }

    /** PUT /api/sync-tasks/{id} 更新任务。 */
    @PutMapping("/{id}")
    public ApiResponse update(@PathVariable("id") String id, @RequestBody(required = false) SyncTaskInput in) {
        // 解析任务 ID 后执行更新并返回更新结果
        return ApiResponse.ok(svc.update(ApiSupport.parseId(id), in));
    }

    /** 只更新启停状态，避免覆盖字段映射、号码列及建表策略。 */
    @PutMapping("/{id}/enabled")
    public ApiResponse enabled(@PathVariable("id") String id, @RequestBody Map<String, Boolean> body) {
        if (body == null || body.get("enabled") == null) {
            throw ApiException.validation("请指定启停状态");
        }
        return ApiResponse.ok(svc.setEnabled(ApiSupport.parseId(id), body.get("enabled")));
    }

    /** DELETE /api/sync-tasks/{id} 删除任务。 */
    @DeleteMapping("/{id}")
    public ApiResponse delete(@PathVariable("id") String id) {
        // 解析路径中的数字 ID
        long tid = ApiSupport.parseId(id);
        // 执行删除:任务正在运行(存在 running 记录)时服务层抛 400 拒绝;历史运行记录随任务级联清理
        svc.delete(tid);
        // 返回被删除任务的 id 供前端确认
        return ApiResponse.ok(Collections.singletonMap("id", tid));
    }

    /** 读取可接管的目标水位；预览不修改目标内容和断点。 */
    @GetMapping("/{id}/baseline-preview")
    public ApiResponse baselinePreview(@PathVariable("id") String id) {
        return ApiResponse.ok(svc.adoptBaseline(ApiSupport.parseId(id),null,false));
    }

    /** 显式确认历史数据完整后，仅保存水位，保留目标内容。普通运行绝不自动接管。 */
    @PostMapping("/{id}/baseline-adoption")
    public ApiResponse adoptBaseline(@PathVariable("id") String id,@RequestBody(required=false) Map<String,Object> body) {
        if(body==null || !Boolean.TRUE.equals(body.get("confirmComplete")) || !(body.get("confirmationToken") instanceof String)
                || ((String)body.get("confirmationToken")).trim().isEmpty())
            throw ApiException.validation("请先预览，并确认该水位及之前的历史数据已完整导入");
        return ApiResponse.ok(svc.adoptBaseline(ApiSupport.parseId(id),(String)body.get("confirmationToken"),true));
    }

    /** POST /api/sync-tasks/{id}/run 手动触发同步；默认从已确认断点继续。 */
    @PostMapping("/{id}/run")
    public ApiResponse run(@PathVariable("id") String id,
                           @RequestBody(required = false) Map<String, Object> body) {
        if (body != null && Boolean.TRUE.equals(body.get("force"))) {
            throw ApiException.validation("普通运行不支持全量重建，请使用重新上传接口");
        }
        // 服务层在同一任务锁内创建运行记录，并恢复未完成的检查点。
        return startResponse(svc.startRun(ApiSupport.parseId(id)));
    }

    /**
     * POST /api/sync-tasks/{id}/restart 重新上传并全量重建。
     *
     * <p>该入口使用服务层已有的 force 路径：在任务锁和目标表锁内创建新运行并原子替换
     * 旧检查点，然后重新读取全部源数据。普通 {@code /run} 永远不会触发这条路径。
     */
    @PostMapping("/{id}/restart")
    public ApiResponse restart(@PathVariable("id") String id) {
        return startResponse(svc.startRun(ApiSupport.parseId(id), true));
    }

    /** 统一启动接口响应，避免两个入口出现字段差异。 */
    private static ApiResponse startResponse(long runId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", runId);
        out.put("status", "running");
        return ApiResponse.ok(out);
    }

    /**
     * POST /api/sync-tasks/{id}/cancel 取消该任务正在运行的后台同步。
     *
     * <p>只提交中断请求并保留线程登记，执行线程真正退出后统一落库和清理;
     * 本实例无可取消线程或任务已进入收尾时返回 {@code cancelled=false}。
     */
    @PostMapping("/{id}/cancel")
    public ApiResponse cancel(@PathVariable("id") String id) {
        // false 只表示本次未提交取消，不代表其他实例没有执行；true 也不代表线程已结束。
        return ApiResponse.ok(Collections.singletonMap("cancelled",
                svc.cancel(ApiSupport.parseId(id))));
    }

    /** 取消尚未派发的定时请求，不中断已经运行的轮次。 */
    @PostMapping("/{id}/cancel-queued")
    public ApiResponse cancelQueued(@PathVariable("id") String id) {
        return ApiResponse.ok(Collections.singletonMap("cancelled",svc.cancelQueued(ApiSupport.parseId(id))));
    }

    /**
     * GET /api/sync-tasks/{id}/cron-next 推算定时任务未来若干次的执行时刻。
     *
     * <p>count 缺省 5、上限 20;非定时任务或未填 cron 返回空数组(而非报错),由前端提示未配置定时。
     */
    @GetMapping("/{id}/cron-next")
    public ApiResponse cronNext(@PathVariable("id") String id,
                                @RequestParam(value = "count", required = false, defaultValue = "5") int count) {
        // count 缺省 5,服务层规整为:<=0 视为 5、>20 截断为 20;非 scheduled 任务(或未填 cron)返回空数组而非报错
        return ApiResponse.ok(Collections.singletonMap("next",
                svc.cronNext(ApiSupport.parseId(id), count)));
    }

    /** GET /api/sync-tasks/{id}/runs 运行历史。 */
    @GetMapping("/{id}/runs")
    public ApiResponse runs(@PathVariable("id") String id) {
        // 查询该任务的历次运行记录并装入成功信封
        return ApiResponse.ok(svc.runs(ApiSupport.parseId(id)));
    }

    /**
     * GET /api/sync-tasks/{id}/run-logs 执行日志分页查询(前端执行日志页)。
     *
     * <p>status=success/failed/running(空=全部);resumed=true/false(空=全部);
     * days=最近 N 天(0=全部);keyword 匹配运行号/说明/错误/断点起始键;page 从 1 开始,size 上限 100。
     */
    @GetMapping("/{id}/run-logs")
    public ApiResponse runLogs(@PathVariable("id") String id,
                               @RequestParam(value = "page", defaultValue = "1") int page,
                               @RequestParam(value = "size", defaultValue = "20") int size,
                               @RequestParam(value = "status", required = false) String status,
                               @RequestParam(value = "resumed", required = false) Boolean resumed,
                               @RequestParam(value = "days", defaultValue = "0") int days,
                               @RequestParam(value = "keyword", required = false) String keyword) {
        return ApiResponse.ok(svc.runLogs(ApiSupport.parseId(id), page, size, status, resumed, days, keyword));
    }

    /**
     * GET /api/sync-tasks/{id}/runs/{runId} 查询某一次运行的执行详情。
     *
     * <p>供前端在任务「运行中」时轮询进度与结果;任务或运行记录不存在均判 404。
     * Go 版只暴露运行列表接口 {@code GET /sync-tasks/:id/runs},本端点为 Java 侧补充。
     */
    @GetMapping("/{id}/runs/{runId}")
    public ApiResponse runStatus(@PathVariable("id") String id, @PathVariable("runId") String runId) {
        return ApiResponse.ok(svc.runStatus(ApiSupport.parseId(id), ApiSupport.parseId(runId)));
    }

    /** DELETE 只做逻辑删除，运行状态、已传数据和断点均保留。 */
    @DeleteMapping("/{id}/runs/{runId}")
    public ApiResponse deleteRunLog(@PathVariable("id") String id, @PathVariable("runId") String runId) {
        return ApiResponse.ok(Collections.singletonMap("deleted", svc.deleteRunLog(ApiSupport.parseId(id), ApiSupport.parseId(runId))));
    }
}
