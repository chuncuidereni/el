/**
 * 【文件职责】本文件定义数据源管理模块的业务服务 SourceObservationService。承载该模块的核心业务规则，协调仓储、连接器或外部服务，并统一转换业务异常和操作状态。
 */

package com.el2ck.service.datasource;

import com.el2ck.config.AppConfig;
import com.el2ck.config.AuthContext;
import com.el2ck.entity.datasource.DataSource;
import com.el2ck.exception.ApiException;
import com.el2ck.util.Crypto;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** 被动记录实际访问结果；查询快照永远不会访问远端数据源。 */
@Service
public class SourceObservationService {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(SourceObservationService.class);
    private static final ObjectMapper JSON=new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private static final long TEST_COOLDOWN_MS=30_000;
    private final JdbcTemplate jdbc;
    private final String secret;
    private final Semaphore testSlots=new Semaphore(2);
    private final ConcurrentMap<String,TestAttempt> tests=new ConcurrentHashMap<>();
    private final AtomicLong clock=new AtomicLong();

    /**
     * 注入平台元数据库和配置加密密钥。
     *
     * @param ds 平台元数据库
     * @param config 应用配置
     */
    public SourceObservationService(javax.sql.DataSource ds,AppConfig config) {
        jdbc=new JdbcTemplate(ds);secret=config.getSecretKey();
    }
    /**
     * 解密并反序列化数据源配置。
     *
     * @param source 数据源
     * @return 配置键值映射
     * @throws IllegalStateException 密文或 JSON 无法解析时抛出
     */
    public Map<String,Object> config(DataSource source) {
        try {
            return JSON.readValue(Crypto.decrypt(secret,source.getConfigEnc()),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>() {});
        } catch(java.io.IOException e) {throw new IllegalStateException("数据源配置不可用",e);}
    }
    /** HMAC 不泄露凭据；同配置重新加密、修改名称/分组不会使状态失效。 */
    public String token(String type,Map<String,Object> config) {
        try {
            Mac mac=Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            byte[] digest=mac.doFinal((type+"\n"+JSON.writeValueAsString(config)).getBytes(StandardCharsets.UTF_8));
            StringBuilder out=new StringBuilder();
            for(byte b:digest) out.append(String.format(Locale.ROOT,"%02x",b&255));
            return out.toString();
        } catch(Exception e) {throw new IllegalStateException("无法计算数据源配置版本",e);}
    }
    /**
     * 计算数据源当前类型与配置的稳定版本令牌。
     *
     * @param source 数据源
     * @return HMAC 配置版本
     */
    public String token(DataSource source) {return token(source.getType(),config(source));}
    /**
     * 为一次元数据读取创建观察票据。
     *
     * @param source 数据源
     * @param origin 观察来源，例如 metadata 或 manual
     * @return 带用户、配置版本和单调开始时间的票据
     */
    public Ticket begin(DataSource source,String origin) {
        return new Ticket(AuthContext.userId(),source.getId(),token(source),
                clock.updateAndGet(last->Math.max(System.currentTimeMillis(),last+1)),origin);
    }
    /** 保存原配置版本；迟到请求不覆盖更新的验证，查询时不会展示其它配置的结果。 */
    public void record(Ticket ticket,boolean success) {
        record(ticket,success,System.currentTimeMillis());
    }
    /**
     * 按观察开始时间写入或更新状态，迟到结果不会覆盖较新的配置和结果。
     *
     * @param ticket 观察票据
     * @param success 本次访问是否成功
     * @param checkedAt 完成时间戳
     */
    private void record(Ticket ticket,boolean success,long checkedAt) {
        if(ticket.id<=0)return;
        try {
            jdbc.update("INSERT INTO el_datasource_observations(owner_user_id,datasource_id,config_token,"
                    +"observation_started_at,checked_at,state,observation_source) VALUES(?,?,?,?,?,?,?)"
                    +" ON DUPLICATE KEY UPDATE"
                    +" config_token=IF(observation_started_at<=VALUES(observation_started_at),VALUES(config_token),config_token),"
                    +" checked_at=IF(observation_started_at<=VALUES(observation_started_at),VALUES(checked_at),checked_at),"
                    +" state=IF(observation_started_at<=VALUES(observation_started_at),VALUES(state),state),"
                    +" observation_source=IF(observation_started_at<=VALUES(observation_started_at),VALUES(observation_source),observation_source),"
                    +" observation_started_at=GREATEST(observation_started_at,VALUES(observation_started_at))",
                    ticket.userId,ticket.id,ticket.token,ticket.startedAt,checkedAt,success?"ok":"bad",ticket.origin);
        } catch(org.springframework.dao.DataAccessException e) {
            LOG.warn("数据源状态快照保存失败: {}",e.getClass().getSimpleName());
        }
    }
    /**
     * 读取当前用户的全部数据源状态快照。
     *
     * @return 以数据源 ID 为键的状态行
     */
    public Map<Long,Map<String,Object>> snapshots() {
        Map<Long,Map<String,Object>> out=new HashMap<>();
        for(Map<String,Object> row:jdbc.queryForList("SELECT * FROM el_datasource_observations WHERE owner_user_id=?",AuthContext.userId()))
            out.put(((Number)row.get("datasource_id")).longValue(),row);
        return out;
    }
    /** 仅明确的连接/认证失败改变连接快照，SQL、结构与业务校验错误不视为离线。 */
    public void connectionFailure(Ticket ticket,Throwable failure) {
        for(Throwable cause=failure;cause!=null;cause=cause.getCause()) {
            if(cause instanceof java.sql.SQLException) {
                String state=((java.sql.SQLException)cause).getSQLState();
                if(state!=null&&(state.startsWith("08")||state.startsWith("28"))) {record(ticket,false);return;}
            }
            if(cause instanceof java.net.ConnectException||cause instanceof java.net.UnknownHostException
                    ||cause instanceof org.apache.kafka.common.errors.AuthenticationException
                    ||cause instanceof com.mongodb.MongoSecurityException
                    ||cause instanceof com.mongodb.MongoSocketOpenException) {record(ticket,false);return;}
            String message=cause.getMessage()==null?"":cause.getMessage().toLowerCase(Locale.ROOT);
            if(message.contains("connection refused")||message.contains("communications link failure")
                    ||message.contains("access denied for user")||message.contains("authentication failed")
                    ||message.contains("password authentication failed")||message.contains("auth fail")
                    ||message.contains("connection is closed by foreign host")) {record(ticket,false);return;}
        }
    }
    /** 同用户/来源/配置 30 秒内合并手动测试（含失败）；最多并发两个，不排队积压。 */
    public void test(long id,String type,Map<String,Object> config,Runnable probe,Ticket savedTicket) {
        long now=System.currentTimeMillis();
        tests.entrySet().removeIf(e->e.getValue().result.isDone()&&now-e.getValue().completedAt>=TEST_COOLDOWN_MS);
        String key=AuthContext.userId()+":"+id+":"+token(type,config);
        TestAttempt attempt=new TestAttempt(clock.updateAndGet(last->Math.max(System.currentTimeMillis(),last+1)));
        TestAttempt existing=tests.putIfAbsent(key,attempt);
        if(existing!=null) {
            try {await(existing);}
            catch(RuntimeException error) {
                if(existing.result.isCompletedExceptionally()) persistReusedTest(existing,savedTicket,false);
                throw error;
            }
            persistReusedTest(existing,savedTicket,true);
            return;
        }
        if(tests.size()>1024||!testSlots.tryAcquire()) {
            ApiException error=new ApiException(429,"通信测试正在进行，请稍后重试");
            attempt.result.completeExceptionally(error);
            tests.remove(key,attempt);throw error;
        }
        try {
            probe.run();
            attempt.completedAt=System.currentTimeMillis();
            if(savedTicket!=null) {record(savedTicket,true,attempt.completedAt);attempt.persisted=true;}
            attempt.result.complete(null);
        } catch(RuntimeException|Error e) {
            attempt.completedAt=System.currentTimeMillis();
            if(savedTicket!=null) {record(savedTicket,false,attempt.completedAt);attempt.persisted=true;}
            attempt.result.completeExceptionally(e);throw e;
        } finally {testSlots.release();}
    }
    /** 编辑草稿测试后保存同配置，再测试可沿用旧结果，但保留真实测试时间。 */
    private void persistReusedTest(TestAttempt attempt,Ticket savedTicket,boolean success) {
        if(savedTicket==null||attempt.persisted||attempt.completedAt==Long.MAX_VALUE)return;
        synchronized(attempt) {
            if(attempt.persisted)return;
            record(new Ticket(savedTicket.userId,savedTicket.id,savedTicket.token,attempt.startedAt,"manual"),success,attempt.completedAt);
            attempt.persisted=true;
        }
    }
    /**
     * 等待同配置的并发通信测试结果。
     *
     * @param attempt 已登记的测试
     * @throws com.el2ck.exception.ApiException 等待中断、超时或原测试失败时抛出对应业务异常
     */
    private static void await(TestAttempt attempt) {
        try {attempt.result.get(30,TimeUnit.SECONDS);}
        catch(InterruptedException e) {Thread.currentThread().interrupt();throw new ApiException(409,"通信测试等待已取消");}
        catch(TimeoutException e) {throw new ApiException(429,"该数据源仍在测试，请稍后查看结果");}
        catch(ExecutionException e) {
            if(e.getCause() instanceof RuntimeException)throw (RuntimeException)e.getCause();
            throw new IllegalStateException("通信测试失败");
        }
    }
    /**
     * 删除当前用户对指定数据源的历史观察并清理内存中的测试登记。
     *
     * @param id 数据源 ID；数据库清理失败只记录告警
     */
    public void remove(long id) {
        try {
            jdbc.update("DELETE FROM el_datasource_observations WHERE owner_user_id=? AND datasource_id=?",AuthContext.userId(),id);
        } catch(org.springframework.dao.DataAccessException e) {
            LOG.warn("数据源历史状态清理失败: {}",e.getClass().getSimpleName());
        }
        String prefix=AuthContext.userId()+":"+id+":";
        tests.keySet().removeIf(k->k.startsWith(prefix));
    }
    public static final class Ticket {
        private final long userId,id,startedAt;
        private final String token,origin;
        /**
         * 创建不可变观察票据。
         *
         * @param userId 发起用户
         * @param id 数据源 ID
         * @param token 配置版本
         * @param startedAt 单调递增的开始时间
         * @param origin 观察来源
         */
        private Ticket(long userId,long id,String token,long startedAt,String origin) {
            this.userId=userId;this.id=id;this.token=token;this.startedAt=startedAt;this.origin=origin;
        }
    }
    private static final class TestAttempt {
        final long startedAt;
        volatile boolean persisted;
        volatile long completedAt=Long.MAX_VALUE;
        final CompletableFuture<Void> result=new CompletableFuture<>();
        TestAttempt(long startedAt) {this.startedAt=startedAt;}
    }
}
