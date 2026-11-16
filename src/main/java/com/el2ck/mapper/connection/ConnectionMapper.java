package com.el2ck.mapper.connection;

/**
 * 【文件职责】定义 ClickHouse 连接元数据的 MyBatis 查询、写入和删除操作。
 */

import com.el2ck.entity.connection.Connection;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 连接表(connections)的 MyBatis Mapper(对应 Go internal/store/connection_store.go)。
 *
 * 列名(下划线)→ 实体属性(驼峰)由 map-underscore-to-camel-case 自动完成;
 * password_enc 为敏感密文列:随 SELECT 读出(实体仅承载密文),明文只在服务层解密、永不回传;
 * 高级配置列(dial_timeout_ms 等)为 NULL-able,
 * NULL 表示「继承全局默认」,故写入时需显式携带 jdbcType 以便把 null 落为 NULL。
 *
 * 注:实体 {@link Connection#isDefault()} 为 boolean 且字段名以 is 开头,Lombok 生成的
 * setter 是 setDefault(),MyBatis 得到的属性名为 default,故 SELECT 里把 is_default 起别名
 * "default"、写库参数用 #{default},与 setter 对应。
 */
@Mapper
public interface ConnectionMapper {
    // SQL: src/main/resources/com/el2ck/mapper/connection/ConnectionMapper.xml

    /** 连接的结构化引用，停用的配置也必须保留引用保护。 */
    List<String> references(@Param("id") long id);

    List<java.util.Map<String, Object>> configuredReferences();

    /** 全部连接:默认连接优先,其次按创建顺序。 */
    // SELECT 全部连接:默认位(is_default=1)在前,其次按主键升序
    List<Connection> list();

    /** 按主键取单个连接;不存在返回 null。 */
    // SELECT 按主键 id 精确查单条连接
    Connection getById(@Param("id") long id);

    /** 按名称取连接(名称唯一);不存在返回 null。 */
    // SELECT 按连接名 name 精确查单条(name 列有 UNIQUE 约束)
    Connection getByName(@Param("name") String name);

    /** 新增连接;自增主键回填到 c.id。 */
    // INSERT 连接:可空的高级配置列显式携带 jdbcType,把 null 落为 NULL;主键回填 c.id
    int insert(Connection c);

    /** 更新连接基础字段与高级配置列:password_enc 密文一并回写(服务层未改密时沿用原密文);
     * created_at 保持不变,updated_at 由库侧置为当前 UTC 时间。 */
    // UPDATE 按主键更新连接各基础字段与高级配置列;password_enc 写回实体当前密文,updated_at 由库侧刷新
    int update(Connection c);

    /** 删除连接;返回受影响行数(0 表示不存在)。 */
    // DELETE 按主键 id 删除连接
    int deleteById(@Param("id") long id);

    /** 将某连接设为默认(同一条 UPDATE 原子清除其余全部默认位);调用前需先确认 id 存在。 */
    // UPDATE 一条语句原子完成:目标 id 的 is_default 置 1,其余全部置 0
    int setDefault(@Param("id") long id);
}
