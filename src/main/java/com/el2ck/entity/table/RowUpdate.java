/**
 * 【文件职责】本文件定义表管理与数据编辑模块的领域数据模型 RowUpdate，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.table;

import lombok.Data;

import java.util.Map;

/** 单元格更新:按 keys(主键条件)定位,写入 values。 */
@Data
public class RowUpdate {
    /** 主键定位条件(列名 -> 值)。 */
    private Map<String, Object> keys;
    /** 待写入的新值(列名 -> 值)。 */
    private Map<String, Object> values;
}
