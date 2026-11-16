/**
 * 【文件职责】本文件定义密码与哈希工具模块的领域结构模型 HashToolItem，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.tool;

import lombok.Data;

/**
 * 哈希工具:一行结果,对应 Go {@code api.hashToolItem}。
 *
 * <p>值回传是为了界面上能对照与导出。
 */
@Data
public class HashToolItem {
    /** 原始值。 */
    private String value;
    /** 十六进制摘要。 */
    private String hash;
    /**
     * 创建 HashToolItem 实例并保存构造注入的协作者。
     */
    public HashToolItem() {
    }
    /**
     * 创建哈希工具结果项。
     *
     * @param value 计算摘要前的原始值
     * @param hash 原始值对应的摘要文本
     */
    public HashToolItem(String value, String hash) {
        this.value = value;
        this.hash = hash;
    }
}
