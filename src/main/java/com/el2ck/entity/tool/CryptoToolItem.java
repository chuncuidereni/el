/**
 * 【文件职责】本文件定义密码与哈希工具模块的领域结构模型 CryptoToolItem，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.tool;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * 加密解密工具:一行结果,对应 Go {@code api.cryptoItem}。
 *
 * <p>一行失败不影响别的行 —— 一批密文里有两行密钥不对是常态,不能让它们把另外 998 行也毁掉。
 */
@Data
public class CryptoToolItem {
    /** 原始值(回传是为了界面上能对照与导出)。 */
    private String value;
    /**
     * 结果。
     *
     * <p>⚠️【绝不能】加 {@code @JsonInclude(NON_EMPTY)}:解密结果本来就可能是空串
     * (空明文是合法的),带上它这个字段会整个消失 → 前端 {@code row.result} 变 undefined →
     * 「解出来是空的」和「没结果」就分不清了。真机 E2E 抓到过一次:
     * 内核单测测过空串,但坏在接口层的序列化,那一层没人测。
     *
     * <p>失败时是空串(不是 null),与 Go 逐字节同形。
     */
    private String result = "";
    /** 失败原因;成功时为空(不出现)。 */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String error;
}
