/**
 * 【文件职责】本文件定义ARB 查询编排模块的领域结构模型 ArbProbe，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.arb;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 聚合层的现场信息:表在不在、视图齐不齐、多少行,对应 Go {@code sync.ArbProbe}。
 *
 * <p>⚠️ 四个集合全部初始化为空列表而不是 null:前端直接 {@code .length} / {@code .join},
 * 拿到 null 会抛异常(Go 侧专门为这件事写过注释)。
 */
@Data
public class ArbProbe {
    /** 聚合表是否存在。 */
    private boolean aggExists;
    /** 聚合表物理行数(未合并时会多于号码数,这是正常的)。 */
    private long rows;
    /** 聚合表去重号码数 = 收敛后的行数。 */
    private long mdns;
    /** 库里实际存在的物化视图名(= 当前配置里那些且已建好)。 */
    private List<String> mvs = new ArrayList<>();
    /** 配置要求、但库里还没建的视图(点「生成」会补上)。 */
    private List<String> missingMvs = new ArrayList<>();
    /**
     * 配置外的物化视图(删掉一个源之后它还在库里,继续往聚合层灌数据)。
     *
     * <p>后端只按配置里的名字比对,所以这些不报出来界面上就永远看不见。
     */
    private List<String> orphanMvs = new ArrayList<>();
    /**
     * 聚合表里「现有列类型 ≠ 配置要的类型」的列(改过某列的取值方式)。
     *
     * <p>建表只在【首次】按配置生成 DDL,之后只补新列,老列的状态类型不会跟着变 ——
     * 不报出来就永远是静默的错。
     */
    private List<String> drift = new ArrayList<>();
    /** 逐源的运行状态(拓扑图用)。 */
    private List<ArbSourceProbe> sources = new ArrayList<>();

    /** 服务表是否存在。 */
    private boolean svcExists;
    /** 服务表行数。 */
    private long svcRows;
    /** 聚合表最后一次写入时间。用来回答「服务表跟上了没」(聚合表被 MV 实时更新,服务表只能手动/定时摊平)。 */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String aggModified;
    /** 服务表最后一次写入时间。 */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String svcModified;
}
