package com.el2ck.entity.sync;

import lombok.Data;

/**
 * 任务列表的批量展示数据，只在仓储和服务之间传递。
 * 查询只读取名称、类型和断点，不加载数据源配置或连接密码；原始断点 JSON 不进入 API DTO。
 */
@Data
public class SyncTaskListDetails {
    private long taskId;
    private String dataSourceName;
    private String dataSourceType;
    private String connectionName;
    private String checkpointJson;
    private boolean queued;
}
