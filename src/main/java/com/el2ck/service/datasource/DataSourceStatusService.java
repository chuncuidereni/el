/**
 * 【文件职责】本文件定义数据源管理模块的业务服务 DataSourceStatusService。承载该模块的核心业务规则，协调仓储、连接器或外部服务，并统一转换业务异常和操作状态。
 */

package com.el2ck.service.datasource;

import com.el2ck.entity.datasource.DataSource;
import com.el2ck.entity.datasource.DataSourceStatus;
import com.el2ck.repository.metadata.MetadataRepository;
import org.springframework.stereotype.Service;
import java.io.File;
import java.util.*;

/** 列表只读取用户自己的历史快照；不发起远端通信测试。 */
@Service
public class DataSourceStatusService {
    private final MetadataRepository repository;
    private final SourceObservationService observations;
    /**
     * 注入元数据仓库和访问观察器。
     *
     * @param repository 数据源读取入口
     * @param observations 用户隔离的历史状态快照
     */
    public DataSourceStatusService(MetadataRepository repository,SourceObservationService observations) {
        this.repository=repository;this.observations=observations;
    }
    /**
     * 汇总当前用户可见数据源的历史访问状态。
     *
     * <p>本地文件检查磁盘存在性，CK 表标记为随任务连接；远端数据源只读取快照，
     * 配置版本不一致时返回待验证，超过 30 分钟的旧结果标记 stale。
     *
     * @return 按数据源 ID 排序的状态列表
     */
    public List<DataSourceStatus> list() {
        Map<Long,Map<String,Object>> snapshots=observations.snapshots();
        List<DataSourceStatus> out=new ArrayList<>();
        for(DataSource source:repository.listDataSources()) {
            if(DataSource.CK_TABLE.equals(source.getType())) {
                out.add(new DataSourceStatus(source.getId(),"none","none","随任务连接",null));continue;
            }
            if(DataSource.isLocalFile(source.getType())) {
                try {
                    String path=String.valueOf(observations.config(source).getOrDefault("path",""));
                    boolean exists=!path.isEmpty()&&new File(path).isFile();
                    out.add(new DataSourceStatus(source.getId(),"file",exists?"ok":"bad",exists?"已上传":"文件不可用",null));
                } catch(RuntimeException e) {
                    out.add(new DataSourceStatus(source.getId(),"file","bad","文件不可用",null));
                }
                continue;
            }
            Map<String,Object> row=snapshots.get(source.getId());
            DataSourceStatus status;
            if(row==null)status=new DataSourceStatus(source.getId(),"conn","none","未验证",null);
            else if(!observations.token(source).equals(row.get("config_token")))
                status=new DataSourceStatus(source.getId(),"conn","none","配置已变更，待验证",null);
            else {
                boolean ok="ok".equals(row.get("state"));
                long at=((Number)row.get("checked_at")).longValue();
                status=new DataSourceStatus(source.getId(),"conn",ok?"ok":"bad",ok?"最近连接成功":"最近连接失败",
                        ok?"历史访问结果，不代表当前实时在线":"上次连接或通信测试失败；可手动重试查看具体原因");
                status.setCheckedAt(java.time.Instant.ofEpochMilli(at).toString());
                status.setStale(System.currentTimeMillis()-at>30*60_000L);
                status.setObservationSource(String.valueOf(row.get("observation_source")));
            }
            out.add(status);
        }
        out.sort(Comparator.comparingLong(DataSourceStatus::getId));
        return out;
    }
}
