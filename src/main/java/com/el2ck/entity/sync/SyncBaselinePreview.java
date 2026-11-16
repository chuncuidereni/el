package com.el2ck.entity.sync;

import lombok.Data;

/** 新任务接管已有全量结果前的预览；水位用字符串保留大整数及小数秒精度。 */
@Data
public class SyncBaselinePreview {
    private long taskId;
    private String target;
    private String sourceCode;
    private String incrementalColumn;
    private String watermark;
    private long targetRows;
    /** 绑定当前用户、任务配置、断点版本及目标状态的短期确认凭证。 */
    private String confirmationToken;
}
