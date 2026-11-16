/**
 * 【文件职责】本文件定义数据源管理模块的展示或状态视图模型 DataSourceStatus，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.datasource;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 数据源健康快照的一条记录：数据源列表页与首页的状态列据此渲染。
 * 远端连接只展示持久化的最近访问结果，不主动探测；本地文件仅检查存在性。
 */
@Data
@AllArgsConstructor
public class DataSourceStatus {
    /** 对应数据源 ID，也是探测结果的排序键(服务层按它排序后返回稳定顺序)。 */
    private long id;
    /** 检查对象类别：file=本地文件(看文件在不在)/ conn=连接类(看能不能连上)/ none=无需检查。 */
    private String kind;
    /** 结论色：ok=正常 / bad=异常 / none=未检查。与 kind 组合出「已上传/已删除/在线/离线」等文案。 */
    private String state;
    /** 展示文案(如「已上传」「离线」)，由服务层直接给出，前端不做二次翻译。 */
    private String text;
    /** 补充说明，装失败原因或路径等诊断信息；正常时为 null。NON_EMPTY 让正常记录不返回该字段。 */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String detail;
    private String checkedAt;
    private boolean stale;
    private String observationSource;
    /**
     * 创建数据源状态视图对象。
     *
     * @param id 数据源 ID
     * @param kind 数据源类别
     * @param state 机器可读状态
     * @param text 面向界面的状态摘要
     * @param detail 补充诊断信息
     */
    public DataSourceStatus(long id,String kind,String state,String text,String detail) {
        this.id=id;this.kind=kind;this.state=state;this.text=text;this.detail=detail;
    }
}
