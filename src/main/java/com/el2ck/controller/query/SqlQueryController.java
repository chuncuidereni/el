/**
 * 【文件职责】本文件定义查询执行模块的HTTP 控制器 SqlQueryController。接收 Web 请求并完成路由与参数适配，统一封装响应；核心校验、权限和持久化逻辑委托给服务层。
 */

package com.el2ck.controller.query;

import com.el2ck.controller.arb.ArbWorkflowController;
import com.el2ck.controller.common.ApiResponse;
import com.el2ck.controller.common.ApiSupport;
import com.el2ck.controller.queryworkflow.QueryWorkflowController;
import com.el2ck.controller.system.SystemController;

import com.el2ck.entity.query.SqlQueryInput;
import com.el2ck.exception.ApiException;
import com.el2ck.service.query.SqlQueryService;
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
 * 已保存 SQL 查询与查询分组 HTTP 接口(对应 Go internal/api/sql_query_api.go)。
 *
 * 两组路径前缀不同(/api/sql-queries 与 /api/sql-query-groups),故类上只声明 /api;
 * 名称合法性、记录存在性等校验与错误码判定在 SqlQueryService。
 *
 * 供前端「SQL 查询」页保存常用语句并按分组管理；会话认证，仓储按所有者隔离。
 */
@RestController
@RequestMapping("/api")
public class SqlQueryController {
    // 依赖的查询服务:查询与分组的增删改查
    private final SqlQueryService service;

    // 构造器注入查询服务
    /**
     * 创建SQL 查询定义 HTTP 控制器并装配后续调用所需的依赖。
     *
     * 依赖由 Spring 容器统一管理；本构造器只保存引用或初始化轻量适配对象，不执行远程业务操作。
     *
     * @param service 该控制器对应的领域业务服务，承载实际校验和数据处理
     */
    public SqlQueryController(SqlQueryService service) {
        // 保存服务引用供各接口调用
        this.service = service;
    }

    // 分组新增/改名共用的请求体
    public static class GroupRequest {
        // 分组名称:必填,空白由服务层判 400
        public String name;
        // 分组备注:可空
        public String remark;
    }

    // "把查询移入分组"的请求体
    public static class MoveRequest {
        // 目标分组 ID:0 表示默认分组,负数由服务层判 400
        public Long groupId;
    }

    /** GET /api/sql-queries 列表;groupId 省略表示不过滤,0 表示只看默认分组。 */
    @GetMapping("/sql-queries")
    public ApiResponse list(@RequestParam(value = "groupId", required = false) String groupId) {
        // 刻意收成 String 再宽松解析:与 Go 一致 —— 非法/负数一律当作"不过滤",而不是回一个 400
        return ApiResponse.ok(service.list(parseGroupFilter(groupId)));
    }

    /** GET /api/sql-queries/{id} 详情。 */
    @GetMapping("/sql-queries/{id}")
    public ApiResponse get(@PathVariable String id) {
        // 不存在由服务层抛 404
        return ApiResponse.ok(service.get(parseId(id)));
    }

    /** POST /api/sql-queries 新建。 */
    @PostMapping("/sql-queries")
    public ApiResponse create(@RequestBody(required = false) SqlQueryInput body) {
        // 请求体可缺省:整段缺失等同于名称为空 → 服务层 400
        return ApiResponse.ok(service.create(body));
    }

    /** PUT /api/sql-queries/{id} 更新。 */
    @PutMapping("/sql-queries/{id}")
    public ApiResponse update(@PathVariable String id, @RequestBody(required = false) SqlQueryInput body) {
        return ApiResponse.ok(service.update(parseId(id), body));
    }

    /** DELETE /api/sql-queries/{id} 删除。 */
    @DeleteMapping("/sql-queries/{id}")
    public ApiResponse delete(@PathVariable String id) {
        long queryId = parseId(id);
        service.delete(queryId);
        // 与 Go 一致:回显被删除的查询 ID
        return ApiResponse.ok(Collections.singletonMap("id", queryId));
    }

    /**
     * PUT /api/sql-queries/{id}/group 移入分组。
     *
     * <p>⚠️ 请求体里【没带 groupId】要当成 0(默认分组)而不是报错:Go 的入参是值类型
     * {@code GroupID int64},JSON 里缺字段或显式 null 都解析成 0,于是 {@code {}} 的语义是
     * 「移入默认分组」并返回 200。整段 body 缺失才是 400(Go 那边是 JSON 解析失败)。
     */
    @PutMapping("/sql-queries/{id}/group")
    public ApiResponse setGroup(@PathVariable String id, @RequestBody(required = false) MoveRequest body) {
        if (body == null) throw ApiException.validation("请求体非法: 请求体不能为空");
        long queryId = parseId(id);
        long groupId = body.groupId == null ? 0L : body.groupId;
        service.setQueryGroup(queryId, groupId);
        // 与 Go 一致:回显查询 ID 与目标分组(缺省时就是 0)
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", queryId);
        out.put("groupId", groupId);
        return ApiResponse.ok(out);
    }

    // ---------------- 分组 ----------------

    /** GET /api/sql-query-groups 分组列表。 */
    @GetMapping("/sql-query-groups")
    public ApiResponse listGroups() {
        return ApiResponse.ok(service.listGroups());
    }

    /** POST /api/sql-query-groups 新建分组。 */
    @PostMapping("/sql-query-groups")
    public ApiResponse createGroup(@RequestBody(required = false) GroupRequest body) {
        return ApiResponse.ok(service.createGroup(body == null ? null : body.name,
                body == null ? null : body.remark));
    }

    /** PUT /api/sql-query-groups/{id} 更新分组。 */
    @PutMapping("/sql-query-groups/{id}")
    public ApiResponse updateGroup(@PathVariable String id, @RequestBody(required = false) GroupRequest body) {
        return ApiResponse.ok(service.updateGroup(parseId(id),
                body == null ? null : body.name, body == null ? null : body.remark));
    }

    /** DELETE /api/sql-query-groups/{id} 删除分组(组内查询退回默认分组)。 */
    @DeleteMapping("/sql-query-groups/{id}")
    public ApiResponse deleteGroup(@PathVariable String id) {
        long groupId = parseId(id);
        service.deleteGroup(groupId);
        return ApiResponse.ok(Collections.singletonMap("id", groupId));
    }

    /**
     * 路径 ID:非数字与 &lt;= 0 都按 400「ID 非法」(对应 Go 的 parseIDParam)。
     *
     * <p>⚠️ 不能直接落给服务层:那样 {@code /sql-queries/0} 会走「查无此行」变成 404,
     * 而 Go 在这几个端点上判的是 400。本仓库其它控制器({@code QueryWorkflowController}、
     * {@code ArbWorkflowController}、{@code SystemController})都带了这一句,只有这里漏了。
     */
    private static long parseId(String id) {
        long value = ApiSupport.parseId(id);
        if (value <= 0) {
            throw ApiException.validation("ID 非法");
        }
        return value;
    }

    /**
     * 解析 groupId 过滤条件:能解析成非负整数才过滤,否则返回 null(不过滤)。
     * 与 Go 的 {@code if n, err := strconv.ParseInt(v, 10, 64); err == nil && n >= 0} 逐条对应 ——
     * 注意 Go **不做 trim**,所以 {@code " 3"} 在那边也是「不过滤」,这里保持一致。
     */
    private static Long parseGroupFilter(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        try {
            long value = Long.parseLong(raw);
            return value >= 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
