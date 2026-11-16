/**
 * 【文件职责】本文件定义认证与资源授权模块的HTTP 控制器 DataAssetController。接收 Web 请求并完成路由与参数适配，统一封装响应；核心校验、权限和持久化逻辑委托给服务层。
 */

package com.el2ck.controller.auth;
import com.el2ck.config.AuthContext;
import com.el2ck.controller.common.ApiResponse;
import com.el2ck.entity.common.Column;
import com.el2ck.entity.datasource.*;
import com.el2ck.exception.ApiException;
import com.el2ck.mapper.datasource.DataSourceMapper;
import com.el2ck.service.auth.*;
import com.el2ck.service.datasource.*;
import com.el2ck.service.table.TableService;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import java.util.stream.Collectors;
/**
 * DataAssetController 提供数据资产列表、所有权信息和共享关系等 HTTP 入口，响应内容会按当前用户权限过滤。
 *
 * <p>参数绑定、身份校验、异常转换和响应封装遵循项目统一的 Web 层约定；业务状态变更应委托给服务层完成。
 */
@RestController
@RequestMapping("/api/data-assets")
public class DataAssetController {
    private final ResourceAccessService access;
    private final DataSourceMapper sources;
    private final DataSourceService service;
    private final TableService tables;
    /**
     * 创建数据表资产与共享 HTTP 控制器并装配后续调用所需的依赖。
     *
     * 依赖由 Spring 容器统一管理；本构造器只保存引用或初始化轻量适配对象，不执行远程业务操作。
     *
     * @param access 资源访问服务，用于校验资源所有权、可见范围和共享关系
     * @param sources 数据源业务服务，用于解析来源配置、文件授权和对象元数据
     * @param service 该控制器对应的领域业务服务，承载实际校验和数据处理
     * @param tables tables 依赖；具体职责由类型和调用链确定
     */
    public DataAssetController(ResourceAccessService access,DataSourceMapper sources,DataSourceService service,TableService tables) {
        this.access=access;this.sources=sources;this.service=service;this.tables=tables;
    }
    /**
     * 处理 {@code GET /api/data-assets} 请求：列出当前身份可见的数据表资产。
     *
     * scope 可区分“我的”和其他受支持范围；返回值会按所有者、管理员或有效共享关系计算读写与管理权限。
     *
     * @param scope 资产可见范围；默认 mine 表示当前用户拥有的资产
     * @return 统一 {@link com.el2ck.controller.common.ApiResponse}；成功时封装业务结果，失败由全局异常处理器转换
     */
    @GetMapping
    public ApiResponse list(@RequestParam(defaultValue="mine") String scope) {return ApiResponse.ok(access.assets(scope));}
    /**
     * 处理 {@code GET /api/data-assets/recipients} 请求：搜索可作为共享接收人的有效账号。
     *
     * 仅返回启用账号并排除当前用户；关键字为空时返回有限候选列表。
     *
     * @param keyword 用户名过滤关键字
     * @return 统一 {@link com.el2ck.controller.common.ApiResponse}；成功时封装业务结果，失败由全局异常处理器转换
     */
    @GetMapping("/recipients")
    public ApiResponse recipients(@RequestParam(defaultValue="") String keyword) {return ApiResponse.ok(access.recipients(keyword));}
    /**
     * 处理 {@code POST /api/data-assets} 请求：把用户拥有的数据源表登记为可共享的数据表资产。
     *
     * 登记前校验数据源归属、表是否存在以及类型是否受支持；本地文件使用内部占位表名。
     *
     * @param in in 参数；其具体约束由该端点调用链校验
     * @return 统一 {@link com.el2ck.controller.common.ApiResponse}；成功时封装业务结果，失败由全局异常处理器转换
     */
    @PostMapping
    public ApiResponse register(@RequestBody RegisterInput in) {
        access.requireOwner("datasource",in.datasourceId);
        DataSource d=sources.getById(in.datasourceId);
        if(d==null)throw ApiException.notFound("数据源不存在");
        String table=in.table;
        if(DataSource.isLocalFile(d.getType())) table="__file__";
        else if("mysql".equals(d.getType()) || "pgsql".equals(d.getType())) {
            if(!service.sqlTables(d.getId()).contains(table))throw ApiException.validation("请选择数据源中的有效表");
        } else throw ApiException.validation("当前支持 MySQL、PostgreSQL 和已上传文件；其他数据源请共享同步后的目标表");
        long id=access.registerTable("datasource",d.getId(),"",table,AuthContext.userId());
        return ApiResponse.ok(access.asset(id));
    }
    /**
     * 处理 {@code GET /api/data-assets/{id}/shares} 请求：列出指定数据表资产当前的全部共享记录。
     *
     * 只有资产所有者可以查看；返回接收人、权限和真实 CK 授权状态。
     *
     * @param id 用户 ID
     * @return 统一 {@link com.el2ck.controller.common.ApiResponse}；成功时封装业务结果，失败由全局异常处理器转换
     */
    @GetMapping("/{id}/shares")
    public ApiResponse shares(@PathVariable long id) {return ApiResponse.ok(access.shares(id));}
    /**
     * 处理 {@code POST /api/data-assets/{id}/shares} 请求：把指定数据表资产共享给一个平台用户。
     *
     * 服务层校验所有者、接收人和权限后创建或更新授权，并在真实 CK 权限核对成功后置为可用。
     *
     * @param id 用户 ID
     * @param in in 参数；其具体约束由该端点调用链校验
     * @return 统一 {@link com.el2ck.controller.common.ApiResponse}；成功时封装业务结果，失败由全局异常处理器转换
     */
    @PostMapping("/{id}/shares")
    public ApiResponse share(@PathVariable long id,@RequestBody ShareInput in) {access.share(id,in.userId);return ApiResponse.ok();}
    /**
     * 处理 {@code DELETE /api/data-assets/{id}/shares/{userId}} 请求：撤销指定用户对数据表资产的共享权限。
     *
     * 只有资产所有者可执行；平台授权先被阻断，再撤销 CK 权限并记录版本变化。
     *
     * @param id 用户 ID
     * @param userId 平台用户 ID
     * @return 统一 {@link com.el2ck.controller.common.ApiResponse}；成功时封装业务结果，失败由全局异常处理器转换
     */
    @DeleteMapping("/{id}/shares/{userId}")
    public ApiResponse revoke(@PathVariable long id,@PathVariable long userId) {access.revoke(id,userId);return ApiResponse.ok();}
    /**
     * 处理 {@code POST /api/data-assets/{id}/preview} 请求：预览共享或自有数据表资产的前 100 行。
     *
     * 只读事务固定可重复读隔离级别；文件来源使用所有者身份窄化读取，数据库来源按偏移分页且调用方不能覆盖路径或上限。
     *
     * @param id 用户 ID
     * @param input input 参数；其具体约束由该端点调用链校验
     * @return 统一 {@link com.el2ck.controller.common.ApiResponse}；成功时封装业务结果，失败由全局异常处理器转换
     */
    @PostMapping("/{id}/preview")
    @org.springframework.transaction.annotation.Transactional(readOnly=true, isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public ApiResponse preview(@PathVariable long id,@RequestBody(required=false) PreviewInput input) {
        Map<String,Object>a=access.asset(id);
        long backendId=ResourceAccessService.number(a,"backend_id");
        int offset=input==null?0:Math.max(0,Math.min(10000,input.offset));
        String table=String.valueOf(a.get("table_name"));
        if("clickhouse".equals(a.get("backend_type"))) {
            return ApiResponse.ok(tables.data(backendId,String.valueOf(a.get("database_name")),table,offset,100,null,null,false,null));
        }
        DataSource d=sources.getById(backendId);
        if(d==null || access.owner("datasource",backendId)!=ResourceAccessService.number(a,"owner_user_id"))throw ApiException.notFound("来源已删除或归属已变更");
        if(DataSource.isLocalFile(d.getType())) {
            // A narrow delegated file read: exact saved resource, fixed preview limit, never caller paths/options.
            AuthContext.Principal previous=AuthContext.get();
            try {
                AuthContext.set(ResourceAccessService.number(a,"owner_user_id"),"asset-preview","user");
                CSVPreviewOptions options=new CSVPreviewOptions();options.setLimit(100);
                return ApiResponse.ok(service.csvPreview(d.getId(),options).getRows());
            } catch(RuntimeException e) {
                throw ApiException.badGateway("读取共享文件失败，请联系所有者检查数据源");
            } finally {AuthContext.set(previous.getUserId(),previous.getUsername(),previous.getRole());}
        }
        if(!"mysql".equals(d.getType()) && !"pgsql".equals(d.getType()))throw ApiException.validation("该来源暂不支持共享预览");
        try {
            Map<String,Object> cfg=service.decryptConfig(d);
            List<Column> columns=DbSource.describe(d.getType(),cfg,table);
            Map<String,Object> result=new LinkedHashMap<>();
            result.put("columns",columns.stream().map(Column::getName).collect(Collectors.toList()));
            result.put("types",columns.stream().map(Column::getType).collect(Collectors.toList()));
            result.put("rows",DbSource.page(d.getType(),cfg,table,null,100,offset,columns));
            return ApiResponse.ok(result);
        } catch(RuntimeException e) {throw ApiException.badGateway("读取共享表失败，请联系所有者检查数据源");}
    }
    public static class RegisterInput {public long datasourceId;public String table;}
    public static class ShareInput {public long userId;}
    public static class PreviewInput {public int offset;}
}
