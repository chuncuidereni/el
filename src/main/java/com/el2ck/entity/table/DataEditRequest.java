/**
 * 【文件职责】本文件定义表管理与数据编辑模块的请求/写入参数模型 DataEditRequest，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.table;

import lombok.Data;

import java.util.List;
import java.util.Map;

/** 数据编辑请求(单元格级):按主键定位做增删改。 */
@Data
public class DataEditRequest {
    /** 主键列名(用于定位行,至少一个)。 */
    private List<String> keyColumns;
    /** 待新增行列表(每行为列名 -> 值映射)。 */
    private List<Map<String, Object>> inserts;
    /** 待更新行列表(按 keys 定位,写入 values)。 */
    private List<RowUpdate> updates;
    /** 待删除行列表(每行为列名 -> 主键值映射)。 */
    private List<Map<String, Object>> deletes;
}
