package com.el2ck.mapper.sync;

/**
 * 【文件职责】定义同步任务定义及状态字段的 MyBatis 持久化操作。
 */

import com.el2ck.repository.metadata.MetadataRepository;

import com.el2ck.entity.sync.SyncTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 同步任务表(sync_tasks)的 MyBatis Mapper。
 *
 * 列名(下划线)→ 实体属性(驼峰)由
 * `mybatis.configuration.map-underscore-to-camel-case: true` 自动完成;
 * mapping_json 列映射到 {@link SyncTask#getMappingJson()}(@JsonIgnore, 不参与 HTTP 序列化),
 * 门面 MetadataRepository 负责它在 List<SyncFieldMapping> 与 JSON 文本之间的转换。
 */
@Mapper
public interface SyncTaskMapper {
    // SQL: src/main/resources/com/el2ck/mapper/sync/SyncTaskMapper.xml
    void setEnabled(@Param("id") long id, @Param("enabled") boolean enabled);

    /** 全部任务(最新创建在前)。 */
    // SELECT 全部同步任务,按主键倒序返回
    List<SyncTask> list();

    /** 在 SQL 内限定当前所有者，一次读取任务，避免逐任务查库。 */
    List<SyncTask> listOwned(@Param("ownerId") long ownerId);

    /** 同一所有权范围内批量加载列表展示字段和断点。 */
    List<com.el2ck.entity.sync.SyncTaskListDetails> listOwnedDetails(@Param("ownerId") long ownerId,
                                                                  @Param("taskIds") List<Long> taskIds);

    /** 总数和分页复用所有权及关键词条件；不加载其他页的映射或断点。 */
    long countOwnedPage(@Param("ownerId") long ownerId, @Param("words") List<String> words);
    List<com.el2ck.entity.sync.SyncTaskListDetails> listOwnedRuntime(@Param("ownerId") long ownerId,
                                                                  @Param("taskIds") List<Long> taskIds);
    List<SyncTask> pageOwned(@Param("ownerId") long ownerId, @Param("words") List<String> words,
                            @Param("offset") long offset, @Param("limit") int limit);

    /** 持源锁且在 READ_COMMITTED 事务内读取引用，不锁定无关任务。 */
    Long findSourceReference(@Param("sourceId") long sourceId);

    /** 按主键取单个任务;不存在返回 null。 */
    // SELECT 按主键 id 精确查单条任务
    SyncTask getById(@Param("id") long id);

    /** 新增任务;自增主键回填到 t.id。 */
    // INSERT 新增同步任务;主键回填 t.id
    int insert(SyncTask t);

    /** 更新任务(不含 created_at);updated_at 由库侧置为当前 UTC 时间。 */
    // UPDATE 按主键更新任务全部业务列,updated_at 由库侧刷新
    int update(SyncTask t);

    /** 删除任务;返回受影响行数(0 表示不存在)。 */
    // DELETE 按主键 id 删除同步任务
    int deleteById(@Param("id") long id);
}
