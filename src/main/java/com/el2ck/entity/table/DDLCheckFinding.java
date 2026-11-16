/**
 * 【文件职责】本文件定义表管理与数据编辑模块的领域数据模型 DDLCheckFinding，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.table;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * 表体检的一条结论。对应 Go service.DDLCheckFinding。
 * <p>「只报本项目口径下确定有问题的项」:通用 linter 会说「高基数列别做排序键」,但本项目
 * ORDER BY 号码列是刻意的,报它等于制造假问题。
 */
@Data
public class DDLCheckFinding {
    /** error = 平台上这一列改不了;warn = 不符合平台规则(表可能不由本平台写入)。 */
    private String level;
    /** 结论标题(用户看到的第一行)。 */
    private String title;
    /** 涉及哪一列(列级结论);分区键这类表级结论不带列。 */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String column;
    /** 为什么(含校验器的原始文案)。 */
    private String detail;
    /** 怎么改;没有明确改法时为空。 */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String fix;
}
