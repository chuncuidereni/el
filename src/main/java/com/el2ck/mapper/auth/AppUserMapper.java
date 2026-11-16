package com.el2ck.mapper.auth;

/**
 * 【文件职责】定义用户账号、凭据版本和状态相关的 MyBatis 持久化操作。
 */

import com.el2ck.entity.auth.AppUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 平台登录用户表(el_app_users)Mapper。username 唯一。
 *
 * <p>表结构见手工 SQL 归档 {@code docs/mysql/archive/V1__metadata.sql},表与字段的中文注释在
 * {@code V2__comments.sql}、{@code V3__comments_utf8.sql}。
 *
 * <p>密码列映射:{@code password_hash → passwordHash}、{@code created_at → createdAt} 等
 * 由驼峰下划线自动映射(mybatis map-underscore-to-camel-case,默认开启)。
 */
@Mapper
public interface AppUserMapper {
    // SQL: src/main/resources/com/el2ck/mapper/auth/AppUserMapper.xml

    /** 全部用户(按 id 倒序,最新创建在前)。 */
    List<AppUser> listAll();

    /** 按用户名取单个用户(登录校验用)。 */
    AppUser findByUsername(@Param("username") String username);

    /** 按 id 取单个用户。 */
    AppUser findById(@Param("id") long id);

    /** 用户总数(启动播种时判断表是否为空)。 */
    long count();

    /** 新增用户;回填自增主键。 */
    int insert(AppUser user);

    /** 改角色。 */
    int updateRole(@Param("id") long id, @Param("role") String role);

    /** 改密码(盐 + 摘要一并更新)。 */
    int updatePassword(@Param("id") long id, @Param("passwordHash") String passwordHash, @Param("salt") String salt);

    /** 删除用户。 */
    int delete(@Param("id") long id);
}
