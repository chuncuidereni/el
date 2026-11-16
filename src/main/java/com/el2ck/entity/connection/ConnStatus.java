/**
 * 【文件职责】本文件定义数据库连接管理模块的展示或状态视图模型 ConnStatus，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.connection;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/** 连接连通性状态。 */
@Data
public class ConnStatus {
    /** 被测试的连接 ID。 */
    private Long id;
    /** 是否连通成功。 */
    private Boolean ok;
    /** 连通测试往返延迟(毫秒)。 */
    private Long latencyMs;
    /** 失败原因(仅 ok=false 时非空,否则省略)。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String error;
}
