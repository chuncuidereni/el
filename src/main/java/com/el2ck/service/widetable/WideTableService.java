/**
 * 【文件职责】本文件定义宽表任务模块的业务服务 WideTableService。承载该模块的核心业务规则，协调仓储、连接器或外部服务，并统一转换业务异常和操作状态。
 */

package com.el2ck.service.widetable;

import com.el2ck.entity.widetable.WideTableTask;
import com.el2ck.repository.widetable.WideTableRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * JOIN 型宽表任务的只读服务：Java 侧不负责建/改/跑任务(那些由 Go 程序做)，
 * 所以这里只有列表查询，没有写接口，也不做额外校验——纯转发给仓储层。
 */
@Service
public class WideTableService {
    /** 宽表任务仓储：通过共享 MySQL 元数据源读取 wide_table_tasks。 */
    private final WideTableRepository repository;

    /** 构造器注入仓储(单例服务，依赖不可变，故用 final + 构造器注入)。 */
    public WideTableService(WideTableRepository repository) {
        this.repository = repository;
    }

    /** 列出宽表任务，最新在前，最多 500 条；坏配置的行由仓储层跳过，不在此处补救。 */
    public List<WideTableTask> list() {
        return repository.list();
    }
}
