/**
 * 【文件职责】本文件定义密码与哈希工具模块的请求/写入参数模型 HashToolRequest，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.tool;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 哈希工具:一次批量计算的入参,对应 Go {@code api.hashToolIn}。
 */
@Data
public class HashToolRequest {
    /** 算法:md5 / sha256 / sm3。空 = md5(与前端下拉的默认值一致)。 */
    private String algo;
    /** 要计算的值,每行一条。 */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<String> values = new ArrayList<>();
    /**
     * 是否输出大写。
     *
     * <p>⚠️ 用包装类型区分「没传」与「传了 false」:默认口径是【大写】(与平台一致 ——
     * ClickHouse 的 {@code hex()} 与平台的 SM3 算子都输出大写,混用会让入库后的比对静默不中)。
     */
    private Boolean upper;
}
