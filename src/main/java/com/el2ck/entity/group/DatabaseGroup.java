/**
 * 【文件职责】本文件定义分组管理模块的领域数据模型 DatabaseGroup，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.group;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

/**
 * 数据库分组(对应 MySQL 的 db_groups 表)，把某个连接下的库名归类展示。
 * 分组成员不在本表：成员关系存在 group_dbs 表，按需回填到 dbs 字段，故非列表查询(如新增/改名)返回的对象 dbs 为空。
 */
@Data
public class DatabaseGroup {
    /** 分组主键，也是分组列表的排序键(按 id 升序)。 */
    private long id;
    /** 所属连接 ID。分组是「连接内」的概念，保存成员时以它为准，避免把库挂到别的连接名下。 */
    private long connectionId;
    /** 分组名称。服务层保证去空白后非空且不超过 50 个字(按码点计)。 */
    private String name;
    /** 分组备注，可为空串。 */
    private String remark;
    /** 创建时间，由 MySQL 元数据层写入的时间文本。 */
    private String createdAt;
    /** 更新时间，重命名/改备注时刷新。 */
    private String updatedAt;
    /** 组内数据库名列表，由 group_dbs 逐组查询回填并按库名排序；初始化空列表以免调用方拿到 null。 */
    private List<String> dbs = new ArrayList<>();
}
