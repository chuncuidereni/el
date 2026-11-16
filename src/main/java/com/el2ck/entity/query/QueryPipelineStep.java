/**
 * 【文件职责】本文件定义查询执行模块的领域数据模型 QueryPipelineStep，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.query;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 处理链的单步(算子 + 额外参数),对应 Go {@code model.QueryPipelineStep}。
 *
 * <p>输入值(上一级结果或源列)始终是第一个参数,{@link #args} 提供其余参数。
 * 例如 {@code substring} 的模板是 {@code substring(%s, %s, %s)},故需要 2 个额外参数。
 */
@Data
public class QueryPipelineStep {
    /** 算子名,必须在白名单(处理链)或程序算子注册表(prog)里存在。 */
    private String fn;
    /** 其余参数;不传则不序列化(对齐 Go 的 {@code json:"args,omitempty"})。 */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<QueryPipelineArg> args = new ArrayList<>();
}
