package com.el2ck.mapper.system;

/**
 * 【文件职责】定义系统设置键值的 MyBatis 读写操作。
 */

import com.el2ck.repository.metadata.MetadataRepository;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 全局默认高级配置表(app_settings)的 MyBatis Mapper。
 * 简单 key-value 表;键的默认值语义由 MetadataRepository#getAppSettings 处理(缺失回填内置默认)。
 */
@Mapper
public interface AppSettingsMapper {
    // SQL: src/main/resources/com/el2ck/mapper/system/AppSettingsMapper.xml

    /** Approximate InnoDB data and index bytes for this platform schema. */
    long metadataSizeBytes();

    /** 全部键值对(供读取时与内置默认合并)。 */
    // 查询 app_settings 全表 key/value 键值对,供上层与内置默认合并
    List<Map<String, Object>> listAll();

    /** 新增或覆盖单个键(SQLite UPSERT)。 */
    // INSERT 以 key 为主键写入;若键已存在则用新 value 覆盖(ON CONFLICT 更新)
    int upsert(@Param("key") String key, @Param("value") String value);
}
