/**
 * 【文件职责】本文件定义工作流编排模块的HTTP 控制器 WorkflowController。接收 Web 请求并完成路由与参数适配，统一封装响应；核心校验、权限和持久化逻辑委托给服务层。
 */

package com.el2ck.controller.workflow;

import com.el2ck.controller.common.ApiResponse;
import com.el2ck.controller.common.ApiSupport;

import com.el2ck.entity.workflow.WorkflowInput;
import com.el2ck.service.workflow.WorkflowService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工作流(DAG 编排)HTTP 接口(对应 Go internal/api/workflow_api.go)。
 *
 * 节点 datasource/filter/map/dedup/code/ck_write;Run 为异步触发,
 * 返回 {runId, status:"running"},执行过程与各节点日志落 wf_runs / wf_run_logs。
 *
 * 供前端「工作流」列表页(/workflows)与画布编排页(/workflows/canvas/:id)调用;
 * Basic Auth 保护(配置了 user 时)。
 */
@RestController
@RequestMapping("/api/workflows")
public class WorkflowController {

    // 依赖的工作流服务:负责 DAG 编排的 CRUD、运行历史与异步触发
    private final WorkflowService svc;

    // 构造器注入工作流服务
    /**
     * 创建同步工作流 HTTP 控制器并装配后续调用所需的依赖。
     *
     * 依赖由 Spring 容器统一管理；本构造器只保存引用或初始化轻量适配对象，不执行远程业务操作。
     *
     * @param svc 该控制器对应的领域业务服务，承载实际校验和数据处理
     */
    public WorkflowController(WorkflowService svc) {
        // 保存服务引用供接口调用
        this.svc = svc;
    }

    /** GET /api/workflows 工作流列表。 */
    @GetMapping
    public ApiResponse list() {
        // 查询全部工作流列表并装入成功信封
        return ApiResponse.ok(svc.list());
    }

    /** GET /api/workflows/{id} 单个工作流。 */
    @GetMapping("/{id}")
    public ApiResponse get(@PathVariable("id") String id) {
        // 解析 ID 后返回单个工作流的完整定义
        return ApiResponse.ok(svc.get(ApiSupport.parseId(id)));
    }

    /** POST /api/workflows 新增工作流。 */
    @PostMapping
    public ApiResponse create(@RequestBody(required = false) WorkflowInput in) {
        // 交由服务创建 DAG 编排并返回生成后的工作流
        return ApiResponse.ok(svc.create(in));
    }

    /** PUT /api/workflows/{id} 更新工作流。 */
    @PutMapping("/{id}")
    public ApiResponse update(@PathVariable("id") String id, @RequestBody(required = false) WorkflowInput in) {
        // 解析工作流 ID 后执行更新并返回更新结果
        return ApiResponse.ok(svc.update(ApiSupport.parseId(id), in));
    }

    /** DELETE /api/workflows/{id} 删除工作流。 */
    @DeleteMapping("/{id}")
    public ApiResponse delete(@PathVariable("id") String id) {
        // 解析路径中的数字 ID
        long wid = ApiSupport.parseId(id);
        // 执行删除:有正在运行的实例时服务层抛 400 拒绝;其历史运行记录与节点日志随工作流级联清理
        svc.delete(wid);
        // 返回被删除工作流的 id 供前端确认
        return ApiResponse.ok(Collections.singletonMap("id", wid));
    }

    /** POST /api/workflows/{id}/run 手动触发(异步)。 */
    @PostMapping("/{id}/run")
    public ApiResponse run(@PathVariable("id") String id) {
        // 启动后台执行并拿到本次运行号
        long runId = svc.startRun(ApiSupport.parseId(id));
        // 有序组装返回体:运行号 + 运行中状态
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", runId);
        out.put("status", "running");
        // 立即返回,实际执行在后台线程继续
        return ApiResponse.ok(out);
    }

    /** GET /api/workflows/{id}/runs 运行历史(含节点日志)。 */
    @GetMapping("/{id}/runs")
    public ApiResponse runs(@PathVariable("id") String id) {
        // 查询该工作流的历次运行记录及节点日志
        return ApiResponse.ok(svc.runs(ApiSupport.parseId(id)));
    }
}
