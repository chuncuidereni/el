/**
 * 【文件职责】本文件定义数据源管理模块的HTTP 控制器 SqlSourceCatalogController。接收 Web 请求并完成路由与参数适配，统一封装响应；核心校验、权限和持久化逻辑委托给服务层。
 */

package com.el2ck.controller.datasource;

import com.el2ck.controller.common.ApiResponse;
import com.el2ck.controller.common.ApiSupport;

import com.el2ck.service.datasource.DataSourceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;

/**
 * SQL 数据源的表/列选择器接口 —— 供 SQL 查询页挑选源表与字段使用。
 *
 * <p>单独成一个 Controller(而非并入 {@code DataSourceController}),是因为这两个接口的
 * 响应体形状被**刻意做成与 Go 版前端兼容**:外层是单键包装 {@code {tables:[...]}} /
 * {@code {columns:[...]}},而不是本项目的通用数组响应。前端按旧键名取值,故此处不做统一。
 */
@RestController
public class SqlSourceCatalogController {
    // 依赖的数据源服务:走 information_schema 读取源库的表与列
    private final DataSourceService service;

    // 构造器注入数据源服务
    /**
     * 创建SQL 数据源目录 HTTP 控制器并装配后续调用所需的依赖。
     *
     * 依赖由 Spring 容器统一管理；本构造器只保存引用或初始化轻量适配对象，不执行远程业务操作。
     *
     * @param service 该控制器对应的领域业务服务，承载实际校验和数据处理
     */
    public SqlSourceCatalogController(DataSourceService service) {
        // 保存服务引用供接口调用
        this.service = service;
    }

    /** 列出该数据源下的表;仅 MySQL / PostgreSQL 支持,其余类型由服务层判 400。 */
    @GetMapping("/api/datasources/{id}/sql-tables")
    public ApiResponse tables(@PathVariable String id,
                              @RequestParam(value = "refresh", defaultValue = "false") boolean refresh) {
        // 仅 MySQL / PostgreSQL 数据源支持(其余类型服务层判 400);结果包成 {tables:[...]} 以对齐 Go 前端键名
        return ApiResponse.ok(Collections.singletonMap("tables", service.sqlTables(ApiSupport.parseId(id),refresh)));
    }

    /** 列出指定表的列;{@code table} 可缺省,缺省时回退到数据源配置里保存的源表名。 */
    @GetMapping("/api/datasources/{id}/sql-columns")
    public ApiResponse columns(@PathVariable String id, @RequestParam(value = "table", required = false) String table,
                               @RequestParam(value = "refresh", defaultValue = "false") boolean refresh) {
        // table 可缺省:未传(或空白)时回退数据源配置里保存的源表名,仍为空则 400「请先选择源表」
        return ApiResponse.ok(Collections.singletonMap("columns", service.sqlColumns(ApiSupport.parseId(id), table,refresh)));
    }
}
