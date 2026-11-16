/**
 * 【文件职责】本文件定义表管理与数据编辑模块的领域结构模型 SortSpec，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.table;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * 一列排序。对应 Go service.SortSpec。
 * <p>数据页与导出共用:两者都按同一份排序渲染 ORDER BY,所以「看到的顺序」=「导出的顺序」。
 */
@Data
public class SortSpec {
    /** 排序列名(必须在表结构里存在,否则整条被跳过)。 */
    private String column;

    /** 是否降序(默认升序)。 */
    private boolean desc;

    /**
     * 数值排序标记:把 String 列转成数值再排。
     *
     * <p>与过滤条件的同类标记是同一个道理:目标表里仲裁列一律是 String,
     * 于是 age 按字符串排会得到 {@code '9' > '80'} 这种结果(位数不同时尤其明显)。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Boolean numeric;
}
