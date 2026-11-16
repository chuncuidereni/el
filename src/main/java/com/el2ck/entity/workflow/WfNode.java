/**
 * 【文件职责】本文件定义工作流编排模块的领域结构模型 WfNode，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.workflow;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.Map;

/** 工作流节点(nodes_json 数组元素)。 */
@Data
public class WfNode {
    /** 节点类型:数据源。 */
    public static final String TYPE_DATASOURCE = "datasource";
    /** 节点类型:过滤。 */
    public static final String TYPE_FILTER = "filter";
    /** 节点类型:列映射。 */
    public static final String TYPE_MAP = "map";
    /** 节点类型:去重。 */
    public static final String TYPE_DEDUP = "dedup";
    /** 节点类型:代码(脚本)处理。 */
    public static final String TYPE_CODE = "code";
    /** 节点类型:写入 ClickHouse。 */
    public static final String TYPE_CK_WRITE = "ck_write";

    /** 节点唯一 id(连线 from/to 引用它)。 */
    private String id;
    /** 节点类型(datasource/filter/map/dedup/code/ck_write)。 */
    private String type;
    /** 节点类型相关的配置对象(JSON 存储)。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Map<String, Object> config;
    /** 画布横坐标。 */
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private double x;
    /** 画布纵坐标。 */
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private double y;

    /** 校验节点类型是否为 datasource / filter / map / dedup / code / ck_write。 */
    public static boolean validType(String t) {
        return TYPE_DATASOURCE.equals(t) || TYPE_FILTER.equals(t) || TYPE_MAP.equals(t)
                || TYPE_DEDUP.equals(t) || TYPE_CODE.equals(t) || TYPE_CK_WRITE.equals(t);
    }
}
