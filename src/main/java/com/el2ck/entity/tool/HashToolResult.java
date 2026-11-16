/**
 * 【文件职责】本文件定义密码与哈希工具模块的查询或操作结果模型 HashToolResult，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.tool;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 哈希工具:一次批量计算的结果,对应 Go {@code api.hashToolResp}。
 */
@Data
public class HashToolResult {
    /** 逐行结果(与入参 values 同序同长)。 */
    private List<HashToolItem> items = new ArrayList<>();
    /** 实际使用的算法(已归一化)。 */
    private String algo;
    /** 实际的大小写口径。 */
    private boolean upper;
    /** 条数。 */
    private int count;
}
