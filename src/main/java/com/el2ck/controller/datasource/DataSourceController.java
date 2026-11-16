/**
 * 【文件职责】本文件定义数据源管理模块的HTTP 控制器 DataSourceController。接收 Web 请求并完成路由与参数适配，统一封装响应；核心校验、权限和持久化逻辑委托给服务层。
 */

package com.el2ck.controller.datasource;

import com.el2ck.controller.common.ApiResponse;
import com.el2ck.controller.common.ApiSupport;

import com.el2ck.entity.datasource.CSVPreviewOptions;
import com.el2ck.entity.datasource.DataSourceInput;
import com.el2ck.entity.datasource.LocalFilePreviewRequest;
import com.el2ck.entity.sync.QuickSyncInput;
import com.el2ck.entity.datasource.SftpBrowseRequest;
import com.el2ck.entity.datasource.SftpDirPreviewRequest;
import com.el2ck.entity.datasource.SftpRemotePreviewRequest;
import com.el2ck.exception.ApiException;
import com.el2ck.service.datasource.DataSourceService;
import com.el2ck.service.sync.SyncService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Collections;
import java.util.Map;

/**
 * 数据源管理 HTTP 接口(对应 Go internal/api/datasource_api.go)。
 *
 * 维护同步输入侧数据源(本地文件 CSV/JSON/Excel 与远程库):CRUD、连通性测试、源对象/列结构探测、
 * 本地文件/CSV 预览、文件上传暂存与 quick-sync(建源后立即同步一次)。业务校验与文件处理统一交 Service。
 * 供前端「数据源管理」页(/datasources)调用;Basic Auth 保护(配置了 user 时)。
 */
@RestController
@RequestMapping("/api/datasources")
public class DataSourceController {

    // 依赖的数据源服务:负责数据源 CRUD、配置测试、结构探测与文件预览等
    private final DataSourceService service;
    // 依赖的同步服务:quick-sync 需要按新数据源配置立即发起一次同步
    private final SyncService syncService;

    // 构造器同时注入数据源服务与同步服务
    /**
     * 创建数据源管理 HTTP 控制器并装配后续调用所需的依赖。
     *
     * 依赖由 Spring 容器统一管理；本构造器只保存引用或初始化轻量适配对象，不执行远程业务操作。
     *
     * @param service 该控制器对应的领域业务服务，承载实际校验和数据处理
     * @param syncService 同步业务服务，用于创建、查询或推进同步任务
     */
    public DataSourceController(DataSourceService service, SyncService syncService) {
        // 保存数据源服务引用
        this.service = service;
        // 保存同步服务引用
        this.syncService = syncService;
    }

    // GET /api/datasources:数据源列表
    /**
     * 处理 {@code GET /api/datasources} 请求：列出当前用户可见的数据源。
     *
     * 返回脱敏后的配置视图；凭据不会出现在响应中。
     *
     * @return 统一 {@link com.el2ck.controller.common.ApiResponse}；成功时封装业务结果，失败由全局异常处理器转换
     */
    @GetMapping
    public ApiResponse list() {
        // 查询全部数据源并装入统一成功信封返回
        return ApiResponse.ok(service.list());
    }

    // POST /api/datasources:新增数据源
    /**
     * 处理 {@code POST /api/datasources} 请求：创建并保存新的数据源。
     *
     * 服务层校验类型和必填配置、加密敏感字段并登记所有者；请求体缺失或字段非法时返回业务错误。
     *
     * @param input input 参数；其具体约束由该端点调用链校验
     * @return 统一 {@link com.el2ck.controller.common.ApiResponse}；成功时封装业务结果，失败由全局异常处理器转换
     */
    @PostMapping
    public ApiResponse create(@RequestBody(required = false) DataSourceInput input) {
        // 交由服务创建数据源并返回生成后的数据源对象
        return ApiResponse.ok(service.create(input));
    }

    // PUT /api/datasources/{id}:更新数据源
    /**
     * 处理 {@code PUT /api/datasources/{id}} 请求：更新指定数据源的可编辑配置。
     *
     * 调用者必须拥有该数据源；配置变更由服务层完成校验、加密和必要的元数据清理。
     *
     * @param id 用户 ID
     * @param input input 参数；其具体约束由该端点调用链校验
     * @return 统一 {@link com.el2ck.controller.common.ApiResponse}；成功时封装业务结果，失败由全局异常处理器转换
     */
    @PutMapping("/{id}")
    public ApiResponse update(@PathVariable("id") String id, @RequestBody(required = false) DataSourceInput input) {
        // 解析数据源 ID 后执行更新并返回更新结果
        return ApiResponse.ok(service.update(ApiSupport.parseId(id), input));
    }

    // DELETE /api/datasources/{id}:删除数据源
    /**
     * 处理 {@code DELETE /api/datasources/{id}} 请求：删除指定数据源。
     *
     * 服务层先检查同步任务等引用，满足删除条件后清理元数据和关联缓存；存在引用时返回可读的冲突提示。
     *
     * @param id 用户 ID
     * @return 统一 {@link com.el2ck.controller.common.ApiResponse}；成功时封装业务结果，失败由全局异常处理器转换
     */
    @DeleteMapping("/{id}")
    public ApiResponse delete(@PathVariable("id") String id) {
        // 解析路径中的数字 ID
        long dataSourceId = ApiSupport.parseId(id);
        // 执行删除
        service.delete(dataSourceId);
        // 返回被删除数据源的 id 供前端确认
        return ApiResponse.ok(Collections.singletonMap("id", dataSourceId));
    }

    // POST /api/datasources/test:测试尚未保存的数据源配置(不入库)
    /**
     * 处理 {@code POST /api/datasources/test} 请求：测试请求体中尚未保存的数据源配置。
     *
     * 该方法只建立短期连接并执行探活，不写入数据源或凭据。
     *
     * @param input input 参数；其具体约束由该端点调用链校验
     * @return 统一 {@link com.el2ck.controller.common.ApiResponse}；成功时封装业务结果，失败由全局异常处理器转换
     */
    @PostMapping("/test")
    public ApiResponse test(@RequestBody(required = false) DataSourceInput input) {
        // 仅校验传入配置连通性,不写入数据库
        service.testConfig(input);
        // 测试通过返回 {ok:true}
        return ApiResponse.ok(okMap());
    }

    // POST /api/datasources/{id}/test:测试已保存的数据源
    /**
     * 处理 {@code POST /api/datasources/{id}/test} 请求：使用已保存的配置测试指定数据源连接。
     *
     * 调用前校验数据源归属；服务层解密配置后执行只读探活并返回测试结果。
     *
     * @param id 用户 ID
     * @return 统一 {@link com.el2ck.controller.common.ApiResponse}；成功时封装业务结果，失败由全局异常处理器转换
     */
    @PostMapping("/{id}/test")
    public ApiResponse testById(@PathVariable("id") String id) {
        // 按已保存 ID 执行连通性测试
        service.testById(ApiSupport.parseId(id));
        // 测试通过返回 {ok:true}
        return ApiResponse.ok(okMap());
    }

    /** POST /api/datasources/sftp/browse：Go 数据源页面的远程目录选择。 */
    @PostMapping("/sftp/browse")
    public ApiResponse browseSftp(@RequestBody(required = false) SftpBrowseRequest input) {
        // required=false 只是允许不传;真传 null 时服务层判 400。两种用法:带 id 复用已存数据源凭据,
        // 或现填 host/username(新增向导未落库场景)
        return ApiResponse.ok(service.browseSftp(input));
    }

    /**
     * POST /api/datasources/sftp/dir-preview 用向导里现填的 SFTP 连接参数直接分析远端目录(不落库)。
     *
     * <p>请求体须带全 host/username/password,缺一即 400;用于新增向导阶段,在数据源尚未保存时
     * 先摸清目录里有哪些文件以及表头是否一致。
     */
    @PostMapping("/sftp/dir-preview")
    public ApiResponse previewSftpDirectory(@RequestBody(required = false) SftpDirPreviewRequest input) {
        // 明文连接场景:请求体必须带全 host/username/password,缺一即 400(见 service 校验)
        return ApiResponse.ok(service.previewSftpDirectory(input));
    }

    /**
     * GET /api/datasources/{id}/sftp-preview 用已保存数据源的 SFTP 凭据预览远端目录。
     *
     * <p>remotePath/filePattern 缺省时回退数据源已存配置;hasHeader 只在显式传 false 时按无表头解析。
     * 数据源不存在由服务层判 404,未配置 SFTP 或参数非法判 400。
     */
    @GetMapping("/{id}/sftp-preview")
    public ApiResponse previewSavedSftpDirectory(@PathVariable("id") String id,
            @RequestParam(value = "remotePath", required = false) String remotePath,
            @RequestParam(value = "filePattern", required = false) String filePattern,
            @RequestParam(value = "hasHeader", required = false) Boolean hasHeader,
            @RequestParam(value = "ignoreExtraCsvColumns", defaultValue = "true") boolean ignoreExtraCsvColumns,
            @RequestParam(value = "csvEncoding", required = false) String csvEncoding) {
        // 三个查询参数均可缺省:remotePath/filePattern 为空时回退数据源已存配置;hasHeader 用包装类型,
        // 只有显式传 false 才按无表头解析,null 视为有表头(服务层 !Boolean.FALSE.equals 判定)
        return ApiResponse.ok(service.previewSavedSftpDirectory(
                ApiSupport.parseId(id), remotePath, filePattern, hasHeader, ignoreExtraCsvColumns, csvEncoding));
    }

    /** POST /api/datasources/sftp/preview：只下载并预览指定文件。 */
    @PostMapping("/sftp/preview")
    public ApiResponse previewSftp(@RequestBody(required = false) SftpRemotePreviewRequest input) {
        // 仅下载并解析远端单个文件做预览,不落库;path 缺失或类型不支持由服务层抛 400
        return ApiResponse.ok(service.previewSftpFile(input));
    }

    // POST /api/datasources/{id}/test-config:以新配置覆盖测试已存在的数据源
    /**
     * 处理 {@code POST /api/datasources/{id}/test-config} 请求：使用候选配置覆盖指定数据源的已保存配置并测试连接。
     *
     * 用于编辑页在保存前验证新配置；不会把候选配置持久化。
     *
     * @param id 用户 ID
     * @param input input 参数；其具体约束由该端点调用链校验
     * @return 统一 {@link com.el2ck.controller.common.ApiResponse}；成功时封装业务结果，失败由全局异常处理器转换
     */
    @PostMapping("/{id}/test-config")
    public ApiResponse testConfig(@PathVariable("id") String id,
                                  @RequestBody(required = false) DataSourceInput input) {
        // 用新配置替换旧配置做连通性测试(不改库中记录)
        service.testConfig(ApiSupport.parseId(id), input);
        // 测试通过返回 {ok:true}
        return ApiResponse.ok(okMap());
    }

    // POST /api/datasources/{id}/check-object:校验对象(表/视图)是否存在
    /**
     * 处理 {@code POST /api/datasources/{id}/check-object} 请求：检查指定数据源中的对象是否存在。
     *
     * 对象名来自请求体并经过服务层校验；该方法只读取元数据，不修改来源。
     *
     * @param id 用户 ID
     * @param body body 参数；其具体约束由该端点调用链校验
     * @return 统一 {@link com.el2ck.controller.common.ApiResponse}；成功时封装业务结果，失败由全局异常处理器转换
     */
    @PostMapping("/{id}/check-object")
    public ApiResponse checkObject(@PathVariable("id") String id,
                                   @RequestBody(required = false) Map<String, Object> body) {
        // 解析 body 中的对象名并探测存在性,结果以 {exists:布尔} 返回
        return ApiResponse.ok(Collections.singletonMap("exists",
                service.checkObject(ApiSupport.parseId(id), objectName(body))));
    }

    // POST /api/datasources/{id}/columns:读取对象(表)的列结构
    /**
     * 处理 {@code POST /api/datasources/{id}/columns} 请求：读取指定数据源对象的字段列表。
     *
     * refresh 为 true 时绕过缓存重新读取；返回字段名、类型及服务层补充的元数据。
     *
     * @param id 用户 ID
     * @param body body 参数；其具体约束由该端点调用链校验
     * @param refresh refresh 参数；其具体约束由该端点调用链校验
     * @return 统一 {@link com.el2ck.controller.common.ApiResponse}；成功时封装业务结果，失败由全局异常处理器转换
     */
    @PostMapping("/{id}/columns")
    public ApiResponse columns(@PathVariable("id") String id,
                               @RequestBody(required = false) Map<String, Object> body,
                               @RequestParam(value = "refresh", defaultValue = "false") boolean refresh) {
        // 解析对象名后返回其列结构列表
        return ApiResponse.ok(service.sourceColumns(ApiSupport.parseId(id), objectName(body),refresh));
    }

    // GET /api/datasources/{id}/objects:按数据源类型列出可选择的源对象
    /**
     * 处理 {@code GET /api/datasources/{id}/objects} 请求：列出指定数据源当前可见的对象。
     *
     * refresh 为 true 时刷新元数据缓存；数据库、Kafka 或文件来源使用各自的目录解析规则。
     *
     * @param id 用户 ID
     * @param refresh refresh 参数；其具体约束由该端点调用链校验
     * @return 统一 {@link com.el2ck.controller.common.ApiResponse}；成功时封装业务结果，失败由全局异常处理器转换
     */
    @GetMapping("/{id}/objects")
    public ApiResponse objects(@PathVariable("id") String id,
                              @RequestParam(value = "refresh", defaultValue = "false") boolean refresh) {
        return ApiResponse.ok(service.sourceObjects(ApiSupport.parseId(id),refresh));
    }

    // POST /api/datasources/{id}/csv-preview:预览本地 CSV 前若干行
    /**
     * 处理 {@code POST /api/datasources/{id}/csv-preview} 请求：解析并预览指定数据源关联的 CSV 文件。
     *
     * 解析选项由服务层校验，返回有限行数和列信息；不会把上传文件暴露为任意路径读取。
     *
     * @param id 用户 ID
     * @param options options 参数；其具体约束由该端点调用链校验
     * @return 统一 {@link com.el2ck.controller.common.ApiResponse}；成功时封装业务结果，失败由全局异常处理器转换
     */
    @PostMapping("/{id}/csv-preview")
    public ApiResponse csvPreview(@PathVariable("id") String id,
                                  @RequestBody(required = false) CSVPreviewOptions options) {
        // 请求体可缺省;为空时用默认解析选项预览
        return ApiResponse.ok(service.csvPreview(ApiSupport.parseId(id),
                options == null ? new CSVPreviewOptions() : options));
    }

    // POST /api/datasources/{id}/quick-sync:创建后立即同步一次
    /**
     * 处理 {@code POST /api/datasources/{id}/quick-sync} 请求：基于指定数据源快速创建同步任务。
     *
     * 服务层解析请求选项、校验来源和目标权限，并按是否自动建表登记目标资产。
     *
     * @param id 用户 ID
     * @param input input 参数；其具体约束由该端点调用链校验
     * @return 统一 {@link com.el2ck.controller.common.ApiResponse}；成功时封装业务结果，失败由全局异常处理器转换
     */
    @PostMapping("/{id}/quick-sync")
    public ApiResponse quickSync(@PathVariable("id") String id, @RequestBody(required = false) QuickSyncInput input) {
        // 解析 ID 后发起一次即时同步,返回同步任务信息
        return ApiResponse.ok(syncService.quickSync(ApiSupport.parseId(id), input));
    }

    // GET /api/datasources/{id}/detail:单个数据源详情
    /**
     * 处理 {@code GET /api/datasources/{id}/detail} 请求：读取指定数据源的脱敏详情。
     *
     * 响应包含配置摘要、状态和可用操作信息，不返回敏感凭据明文。
     *
     * @param id 用户 ID
     * @return 统一 {@link com.el2ck.controller.common.ApiResponse}；成功时封装业务结果，失败由全局异常处理器转换
     */
    @GetMapping("/{id}/detail")
    public ApiResponse detail(@PathVariable("id") String id) {
        // 返回该数据源的完整配置详情
        return ApiResponse.ok(service.detail(ApiSupport.parseId(id)));
    }

    // POST /api/datasources/preview-file:按请求体解析本地文件并预览
    /**
     * 处理 {@code POST /api/datasources/preview-file} 请求：预览用户指定的本地上传文件。
     *
     * 服务层把请求限制在受控上传目录和受支持文件类型内，不按调用方输入直接读取任意路径。
     *
     * @param input input 参数；其具体约束由该端点调用链校验
     * @return 统一 {@link com.el2ck.controller.common.ApiResponse}；成功时封装业务结果，失败由全局异常处理器转换
     */
    @PostMapping("/preview-file")
    public ApiResponse previewFile(@RequestBody(required = false) LocalFilePreviewRequest input) {
        // 交由服务解析本地文件并返回预览数据
        return ApiResponse.ok(service.previewLocalFile(input));
    }

    // POST /api/datasources/upload:上传文件(暂存)
    /**
     * 处理 {@code POST /api/datasources/upload} 请求：上传数据文件并创建对应的文件数据源。
     *
     * 上传大小、扩展名和内容由服务层校验；成功后返回可继续预览或同步的数据源信息。
     *
     * @param file file 参数；其具体约束由该端点调用链校验
     * @param type 资源类型或数据源类型
     * @return 统一 {@link com.el2ck.controller.common.ApiResponse}；成功时封装业务结果，失败由全局异常处理器转换
     */
    @PostMapping("/upload")
    public ApiResponse upload(@RequestParam(value = "file", required = false) MultipartFile file,
                              @RequestParam(value = "type", required = false) String type) {
        // 文件缺失或为空内容时直接判为参数错误
        if (file == null || file.isEmpty()) {
            throw ApiException.validation("请选择要上传的文件");
        }
        // 读取文件并交由服务暂存,返回生成的路径/对象
        try (java.io.InputStream input = file.getInputStream()) {
            return ApiResponse.ok(service.uploadFile(
                    type, file.getOriginalFilename(), file.getSize(), input));
        } catch (ApiException e) {
            // 业务性异常原样上抛,由全局处理器翻译为错误信封
            throw e;
        } catch (Exception e) {
            // 其余 IO 等异常包装为 500 服务器错误
            throw ApiException.server("读取上传文件失败: " + message(e));
        }
    }

    // 从请求体 Map 中取对象名(object 字段),缺省返回空串
    /**
     * 从请求体读取对象名；请求体缺失或字段为 null 时返回空串。
     *
     * @param body 请求体映射
     * @return 对象名或空串
     */
    private static String objectName(Map<String, Object> body) {
        Object value = body == null ? null : body.get("object");
        return value == null ? "" : String.valueOf(value);
    }

    // 便捷工具:组装单键 {ok:true} 的 Map
    /**
     * 构造控制器成功响应使用的单键映射。
     *
     * @return 仅包含 {@code ok=true} 的不可变映射
     */
    private static Map<String, Boolean> okMap() {
        return Collections.singletonMap("ok", Boolean.TRUE);
    }

    // 取异常可读文案;为空时退化为异常对象自身字符串
    /**
     * 读取异常可读消息；消息为空时退化为异常对象的字符串表示。
     *
     * @param error 异常
     * @return 用于响应文案的异常说明
     */
    private static String message(Throwable error) {
        String value = error.getMessage();
        return value == null || value.isEmpty() ? String.valueOf(error) : value;
    }
}
