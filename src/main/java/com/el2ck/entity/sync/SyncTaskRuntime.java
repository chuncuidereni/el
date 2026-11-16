package com.el2ck.entity.sync;

import lombok.Data;

/** 当前页的轻量状态快照，不携带任务映射、源结构或原始断点 JSON。 */
@Data
public class SyncTaskRuntime {
    private long id;
    private boolean queued;
    private SyncTaskDTO.LastRun lastRun;
}
