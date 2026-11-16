package com.el2ck.config;

/**
 * 【文件职责】在请求进入业务层前校验资源归属边界，防止用户访问不属于自己的数据源、任务或工作流。
 */

import com.el2ck.exception.ApiException;
import com.el2ck.service.auth.ResourceAccessService;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import javax.servlet.http.*;
import java.util.*;

/**
 * API 入口边界。真实 CK 身份与表级授权接入后,按控制器逐一放开:
 * 普通业务入口在服务层按「所有者/有效共享 + 当前用户 CK 绑定」重新鉴权;
 * 平台管理入口(连接、系统、工具、全局设置、CK 账号管理、历史归属)仍只对管理员开放。
 */
@Component
public class ResourceBoundaryInterceptor implements HandlerInterceptor {
    private final ResourceAccessService access;
/**
 * 注入资源访问服务；拦截器只负责识别入口边界，具体所有者、共享和 CK 身份校验由服务层完成。
 *
 * @param access 资源访问授权服务
 */
    public ResourceBoundaryInterceptor(ResourceAccessService access) {this.access=access;}
/**
 * 根据控制器和请求方法执行入口分类：公开入口直接放行，普通业务入口交由服务层校验归属，
 * 平台管理入口只允许管理员，尚未接入资源边界的入口直接拒绝。
 *
 * @param request 当前 HTTP 请求
 * @param response 当前 HTTP 响应，由后续处理器写入
 * @param handler Spring MVC 匹配到的处理器
 * @return 已放行入口返回 true；拒绝场景通过 {@link ApiException} 终止请求
 */
    @Override
    @SuppressWarnings("unchecked")
    public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler) {
        if(!(handler instanceof HandlerMethod))return true;
        String controller=((HandlerMethod)handler).getBeanType().getSimpleName();
        String method=((HandlerMethod)handler).getMethod().getName();
        if(Arrays.asList("AuthController","HealthController").contains(controller))return true;
        AuthContext.userId();
        // 平台账号/实例绑定/历史归属:控制器内部再做管理员判断。
        if(Arrays.asList("AppUserController","AccessMigrationController").contains(controller))return true;
        // 表资产预览/共享接口:ResourceAccessService 校验归属与 active 共享状态。
        if("DataAssetController".equals(controller))return true;
        // 业务入口:服务层用当前用户 CK 身份执行,并在访问前校验所有者/共享。
        if(Arrays.asList("TableController","QueryController","SyncTaskController","SqlQueryController",
                "WorkflowController","ArbWorkflowController","QueryWorkflowController","DataSourceController",
                "DataSourceStatusController","SqlSourceCatalogController","GroupController","TableGroupController")
                .contains(controller)) return true;
        if("DatabaseController".equals(controller)
                && Arrays.asList("list","tableStats").contains(method)) return true;
        if("ConnectionController".equals(controller)
                && Arrays.asList("list","status").contains(method)) return true;
        if("SettingsController".equals(controller) && "GET".equals(request.getMethod())) return true;
        // 平台操作:显式管理员能力,不构成对他人业务表的隐式权限。
        if(Arrays.asList("ConnectionController","DatabaseController","SystemController","SettingsController",
                "ToolController","UserController","WideTableController").contains(controller)
                && "admin".equals(AuthContext.get().getRole()))return true;
        return blocked();
    }
/**
 * 拒绝未出现在显式放行清单中的入口，避免新增控制器因遗漏接入权限边界而默认获得访问能力。
 *
 * @return 本方法不会正常返回，而是抛出 403 业务异常
 */
    private boolean blocked() {throw new ApiException(403,"该入口尚未接入用户数据隔离，请使用标准源表或目标库表页面");}
}
