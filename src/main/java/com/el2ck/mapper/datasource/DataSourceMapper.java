package com.el2ck.mapper.datasource;

/**
 * 【文件职责】定义数据源元数据的 MyBatis 增删改查操作。
 */

import com.el2ck.entity.datasource.DataSource;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 数据源表(data_sources)的 MyBatis Mapper(对应 Go internal/store/datasource_store.go)。
 *
 * 类型相关配置(含密码)以整段加密 JSON 存入 config_enc;解密与敏感字段脱敏在服务层完成。
 */
@Mapper
public interface DataSourceMapper {
    // SQL: src/main/resources/com/el2ck/mapper/datasource/DataSourceMapper.xml

    /** 全部数据源(最新创建的排在最前)。 */
    // SELECT 全部数据源,按主键倒序返回，创建时间相同也保持顺序稳定
    List<DataSource> list();

    /** 按主键取单个数据源;不存在返回 null。 */
    // SELECT 按主键 id 精确查单条数据源
    DataSource getById(@Param("id") long id);

    /** 当前读并锁住源及所有权行；仅在引用写入/删除事务内调用，查不到统一视为无权或不存在。 */
    Long lockOwnedById(@Param("id") long id, @Param("ownerId") long ownerId);

    /** 按唯一标识取数据源(查重用);不存在返回 null。 */
    // SELECT 按唯一业务编码 code 精确查单条(code 有 UNIQUE 约束,用于查重)
    DataSource getByCode(@Param("code") String code);

    // 注意查的是 ds_groups(分组表)而非 data_sources:用于写入 group_id 前校验分组确实存在
    int countGroup(@Param("id") long id);

    /** 新增数据源;自增主键回填到 d.id。 */
    // INSERT 新增数据源(config_enc 写入加密后的配置);主键回填 d.id
    int insert(DataSource d);

    /** 更新数据源;updated_at 由库侧置为当前 UTC 时间。 */
    // UPDATE 按主键更新数据源各基础列,updated_at 由库侧刷新
    int update(DataSource d);

    /** 删除数据源;返回受影响行数(0 表示不存在)。 */
    // DELETE 按主键 id 删除数据源
    int deleteById(@Param("id") long id);
}
