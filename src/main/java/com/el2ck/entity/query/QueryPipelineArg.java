/**
 * 【文件职责】本文件定义查询执行模块的领域数据模型 QueryPipelineArg，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.query;

import lombok.Data;

/**
 * 处理链算子的一个参数(对应 Go {@code model.QueryPipelineArg})。
 *
 * <p>{@code kind} 决定渲染方式(防注入):
 * <ul>
 *   <li>{@code number} 数字 —— 校验后原样输出</li>
 *   <li>{@code string} 字符串 —— 自动加单引号并转义(默认)</li>
 *   <li>{@code ident} 标识符(如 cast 的类型名)—— 校验 {@code ^[A-Za-z_][A-Za-z0-9_]*$} 后输出</li>
 * </ul>
 *
 * <p>注意 {@code kind} 本身只影响渲染方式,不参与白名单判断:真正的白名单是
 * {@link #value} 通过 {@code QueryPlanBuilder.renderPipelineArg} 时的校验。
 */
@Data
public class QueryPipelineArg {
    /** 参数类型:number | string(默认)| ident。 */
    private String kind;
    /** 参数值,按 kind 解释;渲染前一律 TrimSpace。 */
    private String value;
}
