package com.el2ck.mapper.workflow;

/**
 * 【文件职责】定义 SQL 工作流定义与步骤配置的 MyBatis 持久化操作。
 */

import com.el2ck.entity.workflow.WfTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 工作流定义表(wf_tasks)的 MyBatis Mapper。
 * DAG 的节点/连线以 nodes_json/edges_json 两列 TEXT 存储(与 WfTask 实体字段一一对应)。
 */
@Mapper
public interface WorkflowMapper {
    // SQL: src/main/resources/com/el2ck/mapper/workflow/WorkflowMapper.xml

    /** 全部工作流(最新创建在前)。 */
    // SELECT 全部工作流定义,按主键倒序返回
    List<WfTask> list();

    /** 持源锁后在 READ_COMMITTED 事务读取引用快照，不锁整张工作流表。 */
    List<WfTask> listReferences();

    /** 按主键取单个工作流;不存在返回 null。 */
    // SELECT 按主键 id 精确查单条工作流
    WfTask getById(@Param("id") long id);

    /** 新增工作流;自增主键回填到 w.id。 */
    // INSERT 新增工作流(nodes_json/edges_json 写入 JSON 文本);主键回填 w.id
    int insert(WfTask w);

    /** 更新工作流(不含 created_at);updated_at 由库侧置为当前 UTC 时间。 */
    // UPDATE 按主键更新工作流定义,updated_at 由库侧刷新
    int update(WfTask w);

    /** 删除工作流;返回受影响行数(0 表示不存在)。 */
    // DELETE 按主键 id 删除工作流定义
    int deleteById(@Param("id") long id);
}
