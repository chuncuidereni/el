package com.el2ck.entity.sync;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

/** 同步任务服务端分页；配置及断点仅加载当前页，兼容原任务 DTO。 */
@Data
public class SyncTaskPage {
    private List<SyncTaskDTO> rows = new ArrayList<>();
    private long total;
    private int page;
    private int size;
}
