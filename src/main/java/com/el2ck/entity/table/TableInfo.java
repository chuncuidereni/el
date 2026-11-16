/**
 * 【文件职责】本文件定义表管理与数据编辑模块的展示或状态视图模型 TableInfo，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.table;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/** ClickHouse 表信息(list tables 结果)。 */
@Data
public class TableInfo {
    /** 表名。 */
    private String name;
    /** 表引擎(如 MergeTree)。 */
    private String engine;
    /** 表注释(COMMENT);为空时省略。 */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String remark;
    /** 平台表资产 ID;前端操作按资产定位,不按库表名猜测。 */
    private Long assetId;
    /** 当前所有者用户 ID。 */
    private Long ownerUserId;
    /** 原始创建者用户 ID;历史未知为空。 */
    private Long creatorUserId;
    /** 创建者显示名;历史未知返回「历史数据」。 */
    private String creatorName;
    /** owner(本人拥有) / read(共享只读)。 */
    private String accessMode;
    /** 服务端计算的能力,前端只用于展示,实际操作仍重新鉴权。 */
    private Boolean canRead;
    private Boolean canWrite;
    private Boolean canManage;
    /** 删除能力独立于资产管理;管理员可按 CK 权限删除未登记的物理表。 */
    private Boolean canDrop;
    private Boolean canShare;
    /** active / provisioning / changing / missing 等。 */
    private String permissionStatus;
}
