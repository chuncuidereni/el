package com.el2ck.mapper.table;

/**
 * 【文件职责】定义表引用关系查询，供删除保护和资源归属校验使用。
 */

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import com.el2ck.entity.datasource.DataSource;
import java.util.List;
import java.util.Map;

/** 改名前扫描原始配置，不使用会截断列表或跳过损坏 JSON 的页面列表接口。 */
@Mapper
public interface TableReferenceMapper {
    // SQL: src/main/resources/com/el2ck/mapper/table/TableReferenceMapper.xml
    List<Map<String, Object>> configurations();

    List<Map<String, Object>> savedSql();

    /** 全部用户的执行中同步任务，仅供服务端运行保护使用。 */
    List<Map<String, Object>> runningSyncTasks();

    /** 执行中工作流配置，不受页面分页、当前用户列表过滤影响。 */
    List<Map<String, Object>> runningConfigurations();

    /** 运行保护使用的内部数据源读取，不能暴露给页面或跳过外部接口权限校验。 */
    DataSource referencedSource(@Param("id") long id);
}
