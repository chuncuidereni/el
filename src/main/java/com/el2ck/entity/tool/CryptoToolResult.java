/**
 * 【文件职责】本文件定义密码与哈希工具模块的查询或操作结果模型 CryptoToolResult，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.tool;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 加密解密工具:一次批量执行的结果,对应 Go {@code api.cryptoRunResp}。
 *
 * <p>{@code okCount + failCount == count} 这条恒等式前端拿来做失败提示,别漏算。
 */
@Data
public class CryptoToolResult {
    /** 逐行结果(与入参 values 同序同长)。 */
    private List<CryptoToolItem> items = new ArrayList<>();
    /** 实际使用的算法 id。 */
    private String algo;
    /** 实际方向:encrypt / decrypt。 */
    private String direction;
    /** 加密时的输出编码。 */
    private String outFormat;
    /** 解密时的输入编码。 */
    private String inFormat;
    /** 总条数。 */
    private int count;
    /** 成功条数。 */
    private int okCount;
    /** 失败条数。 */
    private int failCount;
}
