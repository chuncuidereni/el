package com.el2ck.mapper.user;

/**
 * 【文件职责】定义用户备注的 MyBatis 持久化操作。
 */

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 用户备注表(user_remarks)的 MyBatis Mapper。
 * 备注以 (连接ID, 用户名) 为复合主键,用于表/用户浏览时为 CH 用户加说明。
 */
@Mapper
public interface UserRemarkMapper {
    // SQL: src/main/resources/com/el2ck/mapper/user/UserRemarkMapper.xml

    /** 某连接下全部 用户名→备注。列名直接作为返回 Map 的 key。 */
    // SELECT 按 conn_id 过滤,返回 user_name/remark 两列,列名即 Map 的 key
    List<Map<String, Object>> listByConn(@Param("connId") long connId);

    /** 新增或覆盖备注(SQLite UPSERT)。 */
    // INSERT 以 (conn_id,user_name) 复合主键写入;已存在则覆盖 remark(ON CONFLICT 更新)
    int upsert(@Param("connId") long connId, @Param("userName") String userName, @Param("remark") String remark);

    /** 删除备注。 */
    // DELETE 按 (conn_id,user_name) 复合主键删除单条备注
    int delete(@Param("connId") long connId, @Param("userName") String userName);

    /** 删除某连接下全部备注(删除连接时级联清理)。 */
    // DELETE 按 conn_id 清空该连接下全部备注
    int deleteByConn(@Param("connId") long connId);
}
