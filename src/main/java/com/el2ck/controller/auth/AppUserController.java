/**
 * 【文件职责】本文件定义认证与资源授权模块的HTTP 控制器 AppUserController。接收 Web 请求并完成路由与参数适配，统一封装响应；核心校验、权限和持久化逻辑委托给服务层。
 */

package com.el2ck.controller.auth;
import com.el2ck.config.AuthContext;
import com.el2ck.controller.common.ApiResponse;
import com.el2ck.exception.ApiException;
import com.el2ck.service.auth.*;
import com.el2ck.service.ck.CkIdentityService;
import com.el2ck.service.ck.CkInstanceService;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import java.util.stream.Collectors;
/**
 * AppUserController 提供应用用户的查询、创建、更新、启停和删除 HTTP 入口，具体权限与一致性规则由用户服务处理。
 *
 * <p>参数绑定、身份校验、异常转换和响应封装遵循项目统一的 Web 层约定；业务状态变更应委托给服务层完成。
 */
@RestController
@RequestMapping("/api/app-users")
public class AppUserController {
    private final AppUserService users;
    private final AccountSecurityService accounts;
    private final CkIdentityService ckIdentities;
    private final CkInstanceService ckInstances;
    /**
     * 创建平台账号管理 HTTP 控制器并装配后续调用所需的依赖。
     *
     * 依赖由 Spring 容器统一管理；本构造器只保存引用或初始化轻量适配对象，不执行远程业务操作。
     *
     * @param users 平台用户服务，负责账号持久化、角色和密码校验
     * @param accounts 账号安全服务，用于校验启用状态、首次改密和会话版本
     * @param ckIdentities ClickHouse 用户身份服务，用于解析用户绑定与执行凭据
     * @param ckInstances ClickHouse 实例登记服务，用于校验实例和允许的数据库范围
     */
    public AppUserController(AppUserService users, AccountSecurityService accounts,
                             CkIdentityService ckIdentities, CkInstanceService ckInstances) {
        this.users=users;this.accounts=accounts;this.ckIdentities=ckIdentities;this.ckInstances=ckInstances;
    }
    /**
     * 校验平台账号管理入口的管理员权限，失败时抛出 403。
     */
    private void admin() {
        if (AuthContext.get()==null || !"admin".equals(AuthContext.get().getRole())) throw new ApiException(403,"仅管理员可管理平台账号");
    }
    /**
     * 处理 {@code GET /api/app-users} 请求：列出平台账号及其安全状态，仅管理员可调用。
     *
     * 返回用户基本身份、启用状态和首次改密要求，不返回密码或盐值。
     *
     * @return 统一 {@link com.el2ck.controller.common.ApiResponse}；成功时封装业务结果，失败由全局异常处理器转换
     */
    @GetMapping
    public ApiResponse list() { admin(); return ApiResponse.ok(users.list().stream().map(accounts::identity).collect(Collectors.toList())); }
    /**
     * 处理 {@code POST /api/app-users} 请求：创建平台账号，仅管理员可调用。
     *
     * 请求体中的用户名、角色和初始密码由账号服务校验；成功后返回新账号的安全视图。
     *
     * @param input input 参数；其具体约束由该端点调用链校验
     * @return 统一 {@link com.el2ck.controller.common.ApiResponse}；成功时封装业务结果，失败由全局异常处理器转换
     */
    @PostMapping
    public ApiResponse create(@RequestBody Map<String,Object> input) {
        admin();
        String username=text(input.get("username"));
        String password=text(input.get("password"));
        String role=text(input.get("role"));
        com.el2ck.entity.auth.AppUser created=accounts.create(username,password,role);
        // 平台账号先落库;CK 账号初始化失败不阻断建号,用户在列表中看到 failed 并可重试。
        List<Long> requested=instanceIds(input.get("ckInstanceIds"));
        if(requested.isEmpty()) ckIdentities.initializeAll(created.getId());
        else for(long instanceId:requested) ckIdentities.initialize(created.getId(),instanceId);
        return ApiResponse.ok(accounts.identity(users.get(created.getId())));
    }
    /**
     * 处理 {@code PUT /api/app-users/{id}} 请求：更新指定账号的角色和启用状态，仅管理员可调用。
     *
     * 账号安全服务会阻止禁用或降级最后一个有效管理员，并递增认证版本使旧会话失效。
     *
     * @param id 用户 ID
     * @param input input 参数；其具体约束由该端点调用链校验
     * @return 统一 {@link com.el2ck.controller.common.ApiResponse}；成功时封装业务结果，失败由全局异常处理器转换
     */
    @PutMapping("/{id}")
    public ApiResponse update(@PathVariable long id,@RequestBody UpdateInput input) {
        admin();
        boolean wasEnabled=accounts.enabled(id);
        accounts.update(id,input.role,input.enabled);
        if(!input.enabled) ckIdentities.disableAll(id);
        else if(!wasEnabled) ckIdentities.enableAll(id);
        return ApiResponse.ok(Collections.singletonMap("ok",true));
    }
    /**
     * 处理 {@code PUT /api/app-users/{id}/password} 请求：由管理员重置指定账号密码。
     *
     * 重置后账号被标记为必须首次改密，并递增认证版本；旧会话随后失效。
     *
     * @param id 用户 ID
     * @param input input 参数；其具体约束由该端点调用链校验
     * @return 统一 {@link com.el2ck.controller.common.ApiResponse}；成功时封装业务结果，失败由全局异常处理器转换
     */
    @PutMapping("/{id}/password")
    public ApiResponse reset(@PathVariable long id,@RequestBody Map<String,String> input) {
        admin();accounts.resetPassword(id,input.get("password"));return ApiResponse.ok(Collections.singletonMap("ok",true));
    }
    /** 可选实例列表;不传则初始化全部已登记实例。 */
    @GetMapping("/ck-instances")
    public ApiResponse ckInstances() { admin(); return ApiResponse.ok(ckInstances.list()); }
    /** 为已有用户初始化/补齐全部 CK 绑定。 */
    @PostMapping("/{id}/ck-bindings/initialize")
    public ApiResponse initializeBindings(@PathVariable long id) {
        admin(); users.get(id);
        return ApiResponse.ok(ckIdentities.initializeAll(id));
    }
    /** 按绑定重试初始化,不接受任意 SQL。 */
    @PostMapping("/{id}/ck-bindings/{bindingId}/retry")
    public ApiResponse retryBinding(@PathVariable long id,@PathVariable long bindingId) {
        admin(); users.get(id);
        if(ckIdentities.bindings(id).stream().noneMatch(b -> ((Number)b.get("bindingId")).longValue()==bindingId))
            throw ApiException.notFound("该用户的绑定不存在");
        return ApiResponse.ok(ckIdentities.retry(bindingId));
    }
    /**
     * 将请求体中的可选值转成字符串，null 保持为 null。
     *
     * @param v 原始值
     * @return 文本值或 null
     */
    private static String text(Object v) { return v==null ? null : String.valueOf(v); }
    /**
     * 把请求体中的集合转换为 CK 实例 ID 列表。
     *
     * <p>非集合输入返回空列表；集合内无法解析为 long 的元素会被忽略，避免单个坏值
     * 阻断其余实例绑定操作。
     *
     * @param v 请求体原始值
     * @return 可解析的实例 ID 列表
     */
    @SuppressWarnings("unchecked")
    private static List<Long> instanceIds(Object v) {
        List<Long> out=new ArrayList<>();
        if(v instanceof Collection) for(Object o:(Collection<Object>)v) { try { out.add(Long.parseLong(String.valueOf(o))); } catch(NumberFormatException ignored) { } }
        return out;
    }
    public static class UpdateInput { public String role; public boolean enabled; }
}
