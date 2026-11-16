/**
 * 【文件职责】本文件定义ARB 查询编排模块的领域结构模型 ArbWorkflowConfig，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.arb;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 工作流落库时的配置形状(config_json 列),对应 Go {@code store.arbConfig}。
 *
 * <p>sources/fields/columns 三块是【一个整体】,拆成多表反而要处理「半保存」的中间状态,不值得。
 *
 * <p>{@link #wide} 也放在这里而不是单独一列:它是配置而不是需要查询/排序的元数据,
 * 而 config_json 本来就是整体序列化的,加字段不用迁移(工作流数量很少,列表里扫一遍即可)。
 */
@Data
public class ArbWorkflowConfig {
    /** 「②源」。 */
    private List<ArbSource> sources = new ArrayList<>();
    /** 「③字段」。 */
    private List<ArbField> fields = new ArrayList<>();
    /** 「④仲裁方案」。 */
    private List<ArbColumn> columns = new ArrayList<>();
    /**
     * 「作为宽表」标记;false 不序列化(对齐 Go 的 {@code json:"isWide,omitempty"})。
     *
     * <p>⚠️ {@link JsonProperty} 不能省:Go 往 config_json 里写的键就是 {@code isWide},
     * 靠 Lombok 由字段名反推会得到 {@code wide} —— 写出去 Go 不认,读进来(关闭了
     * FAIL_ON_UNKNOWN_PROPERTIES)会被静默丢掉,表现为「宽表标记存了又没了」。
     */
    @JsonProperty("isWide")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private boolean wide;
}
