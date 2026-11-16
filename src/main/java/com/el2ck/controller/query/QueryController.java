/**
 * 【文件职责】本文件定义查询执行模块的HTTP 控制器 QueryController。接收 Web 请求并完成路由与参数适配，统一封装响应；核心校验、权限和持久化逻辑委托给服务层。
 */

package com.el2ck.controller.query;

import com.el2ck.controller.common.ApiResponse;
import com.el2ck.controller.common.ApiSupport;
import com.el2ck.controller.common.CsvResponse;

import com.el2ck.entity.query.QueryExportRequest;
import com.el2ck.entity.query.QueryRequest;
import com.el2ck.service.query.QueryService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * SQL 执行 HTTP 接口(对应 Go internal/api/query_api.go)。
 *
 * POST /api/connections/{id}/query:按分句/只读判定在目标连接上顺序执行;
 * POST /api/connections/{id}/query/export:把 SQL 结果流式导出为 CSV。
 * 目标库不可达/语法错误等失败 → 502(服务层抛 badGateway)。
 *
 * 供前端「数据仓库工作台」的「SQL 查询」标签(/workspace 与 /sql-query)调用;
 * Basic Auth 保护(配置了 user 时)。
 */
@RestController
@RequestMapping("/api/connections/{id}/query")
public class QueryController {

    // 依赖的查询服务:负责在目标连接上分句执行 SQL 并汇总结果
    private final QueryService svc;

    // 构造器注入查询服务
    /**
     * 创建ClickHouse 查询 HTTP 控制器并装配后续调用所需的依赖。
     *
     * 依赖由 Spring 容器统一管理；本构造器只保存引用或初始化轻量适配对象，不执行远程业务操作。
     *
     * @param svc 该控制器对应的领域业务服务，承载实际校验和数据处理
     */
    public QueryController(QueryService svc) {
        // 保存服务引用供接口调用
        this.svc = svc;
    }

    /** POST /api/connections/{id}/query 执行 SQL。 */
    @PostMapping
    public ApiResponse execute(@PathVariable("id") String id, @RequestBody(required = false) QueryRequest req) {
        // 解析连接 ID 后执行查询,结果(列头+行数据等)装入成功信封返回
        return ApiResponse.ok(svc.execute(ApiSupport.parseId(id), req));
    }

    /**
     * POST /api/connections/{id}/query/export 把 SQL 结果导出为 CSV。
     *
     * <p>⚠️ 响应体是<b>裸 CSV 流</b>,不是 JSON 信封 —— 前端 {@code postBlob} 拿到的就是文件本身。
     *
     * <p>先 prepareExport(SQL 语法/列名在这阶段就能发现,那时还能返回 JSON 错误对象),
     * 再流式写出。
     *
     * <p>超时:Go 侧给这条路由单独放宽到 30 分钟;Java 没有全局请求超时中间件,
     * 所以服务端不会掐断长导出,30 分钟这个上限由前端 axios 自己把握。
     */
    @PostMapping("/export")
    public void export(@PathVariable("id") String id, @RequestBody(required = false) QueryExportRequest req,
                       HttpServletResponse resp) throws IOException {
        // 准备阶段:挑语句、探列、校验列(只探 LIMIT 0,不读数据行)。
        QueryService.QueryExportPlan plan = svc.prepareExport(ApiSupport.parseId(id), req);
        try {
            // 写出阶段:逐行流式写 CSV。
            CsvResponse.write(resp, "", w -> plan.writeTo(w));
        } finally {
            // 写成功/写失败/客户端中断,这条专用连接都要还回去。
            plan.close();
        }
    }
}
