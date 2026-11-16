/**
 * 【文件职责】本文件定义数据同步模块的领域数据模型 SyncFieldMapping，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.sync;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/** 单列映射:源列 → 目标列。 */
@Data
public class SyncFieldMapping {
    /** 入库前逐行将明文手机号归一化后转为 MD5；随 mapping_json 保存。
     * 关闭时不输出字段，保持旧任务断点指纹兼容。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Boolean autoMdnMd5;

    public void setAutoMdnMd5(Boolean enabled) {
        this.autoMdnMd5 = Boolean.TRUE.equals(enabled) ? Boolean.TRUE : null;
    }

    /** 源列名(数据源/上游列)。 */
    private String sourceColumn;
    /** 目标列名(ClickHouse 列)。 */
    private String targetColumn;
    /** 目标列类型(String/Int64/Float64/Date/DateTime/Bool) */
    private String type;
    /** 目标列是否允许 NULL；缺省映射按可为空处理。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Boolean nullable = Boolean.TRUE;
    /** 目标列默认值(为空时省略)。 */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String defaultValue;
    /** 目标列注释(为空时省略)。 */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String comment;

    /** 兼容旧数据：未保存 nullable 时按可为空处理。 */
    public boolean isNullable() {
        return nullable == null || nullable;
    }
}
