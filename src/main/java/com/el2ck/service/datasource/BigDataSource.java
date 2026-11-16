
package com.el2ck.service.datasource;

import com.el2ck.entity.common.Column;
import com.el2ck.entity.datasource.DataSource;
import com.el2ck.exception.ApiException;
import org.apache.kafka.clients.admin.Admin;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 【文件职责】本文件定义数据源管理模块的 Kafka 与 HiveServer2 连接适配组件。
 * 负责读取连接配置、查询元数据或数据，并将驱动异常转换为业务可处理的异常。
 *
 * 边界：探测 Kafka 时不消费消息、不提交消费位点；所有连接操作通过静态方法完成，工具类禁止实例化。
 */
public final class BigDataSource {
    /** 工具类禁止实例化；所有连接操作通过静态方法完成。 */
    private BigDataSource() {}
    /**
     * 读取并去除配置字符串两端空白。
     *
     * @param cfg 数据源配置
     * @param key 配置键
     * @param fallback 键缺失或值为 null 时的回退值
     * @return 非空配置值或回退值
     */
    private static String value(Map<String,Object> cfg,String key,String fallback) {
        Object v=cfg.get(key);return v==null ? fallback : String.valueOf(v).trim();
    }
    /**
     * 读取密码配置，不进行 trim，避免改变有意保留的空白字符。
     *
     * @param cfg 数据源配置
     * @return 密码字符串；缺失时为空串
     */
    private static String password(Map<String,Object> cfg) {
        Object v=cfg.get("password");return v==null?"":String.valueOf(v);
    }
    /**
     * 判断类型是否由本适配器处理。
     *
     * @param type 数据源类型
     * @return Kafka 或 Hive 类型时为 true
     */
    public static boolean supports(String type) { return DataSource.KAFKA.equals(type)||DataSource.HIVE.equals(type); }
    /**
     * 校验 Kafka 或 Hive 数据源配置的地址、端口、认证协议和标识符。
     *
     * @param type 数据源类型
     * @param cfg 已解密配置
     * @throws com.el2ck.exception.ApiException 配置缺失或格式非法时抛出 400
     */
    public static void validate(String type,Map<String,Object> cfg) {
        if(DataSource.KAFKA.equals(type)) {
            String brokers=value(cfg,"bootstrapServers","");
            if(brokers.isEmpty()) throw ApiException.validation("请填写 Kafka Bootstrap Servers");
            for(String broker:brokers.split(",",-1)) {
                if(!broker.trim().matches("(?:[A-Za-z0-9_.-]+|\\[[0-9a-fA-F:]+\\]):[0-9]{1,5}"))
                    throw ApiException.validation("Kafka 地址格式应为 host:port，多个地址用逗号分隔");
                int port=Integer.parseInt(broker.substring(broker.lastIndexOf(':')+1).trim());
                if(port<1||port>65535) throw ApiException.validation("Kafka 端口应为 1–65535");
            }
            String protocol=value(cfg,"securityProtocol","PLAINTEXT");
            if(!Arrays.asList("PLAINTEXT","SSL","SASL_PLAINTEXT","SASL_SSL").contains(protocol))
                throw ApiException.validation("不支持的 Kafka 安全协议");
            if(protocol.startsWith("SASL_")) {
                if(value(cfg,"username","").isEmpty()||value(cfg,"password","").isEmpty()) throw ApiException.validation("SASL 需要用户名和密码");
                if(!Arrays.asList("PLAIN","SCRAM-SHA-256","SCRAM-SHA-512").contains(value(cfg,"saslMechanism","PLAIN")))
                    throw ApiException.validation("不支持的 SASL 机制");
            }
        } else {
            String authMode=value(cfg,"authMode","NONE");
            if(!Arrays.asList("NONE","PASSWORD").contains(authMode)) throw ApiException.validation("不支持的 Hive 认证方式");
            if("PASSWORD".equals(authMode) && (value(cfg,"username","").isEmpty() || password(cfg).isEmpty()))
                throw ApiException.validation("Hive 密码认证需要用户名和密码");
            if(!value(cfg,"host","").matches("[A-Za-z0-9_.-]+")) throw ApiException.validation("请填写 HiveServer2 主机名或 IPv4 地址");
            int port;
            try {port=Integer.parseInt(value(cfg,"port","10000"));} catch(NumberFormatException e){port=0;}
            if(port<1||port>65535)throw ApiException.validation("Hive 端口应为 1–65535");
            if(!value(cfg,"database","").matches("[A-Za-z_][A-Za-z0-9_]*"))throw ApiException.validation("请填写有效的 Hive 数据库名");
        }
    }
    /**
     * 转义 JAAS 配置中的反斜杠和双引号。
     *
     * @param s 原始用户名或密码
     * @return 可嵌入 JAAS 字符串的值
     */
    private static String jaas(String s) { return s.replace("\\","\\\\").replace("\"","\\\""); }
    /**
     * 按数据源配置创建 Kafka Admin 客户端。
     *
     * @param cfg 已解密配置
     * @return 调用方负责关闭的 Admin 客户端
     */
    private static Admin kafka(Map<String,Object> cfg) {
        return Admin.create(kafkaProperties(cfg));
    }
    /**
     * 构造带有限超时和可选 SASL 参数的 Kafka 客户端属性。
     *
     * @param cfg 已解密 Kafka 配置
     * @return Kafka Properties
     * @throws com.el2ck.exception.ApiException 配置非法时抛出 400
     */
    public static Properties kafkaProperties(Map<String,Object> cfg) {
        validate(DataSource.KAFKA,cfg);
        Properties p=new Properties();
        p.setProperty("bootstrap.servers",value(cfg,"bootstrapServers",""));
        p.setProperty("client.id","el2ck-datasource-probe");
        p.setProperty("request.timeout.ms","5000");p.setProperty("default.api.timeout.ms","10000");
        p.setProperty("socket.connection.setup.timeout.ms","5000");
        String protocol=value(cfg,"securityProtocol","PLAINTEXT");p.setProperty("security.protocol",protocol);
        if(protocol.startsWith("SASL_")) {
            String mechanism=value(cfg,"saslMechanism","PLAIN");p.setProperty("sasl.mechanism",mechanism);
            String module="PLAIN".equals(mechanism)?"org.apache.kafka.common.security.plain.PlainLoginModule":"org.apache.kafka.common.security.scram.ScramLoginModule";
            p.setProperty("sasl.jaas.config",module+" required username=\""+jaas(value(cfg,"username",""))+"\" password=\""+jaas(password(cfg))+"\";");
        }
        return p;
    }
    /**
     * 创建 HiveServer2 JDBC 连接。
     *
     * @param cfg 已解密 Hive 配置
     * @return 调用方负责关闭的 JDBC 连接
     * @throws SQLException 驱动缺失或连接失败时抛出
     */
    public static Connection hive(Map<String,Object> cfg) throws SQLException {
        return hive(cfg, 10000);
    }

    /** 快照读取使用调用方的有限 socket 超时，连接探测仍使用默认十秒。 */
    public static Connection hive(Map<String,Object> cfg, int timeoutMillis) throws SQLException {
        validate(DataSource.HIVE,cfg);
        try {Class.forName("org.apache.hive.jdbc.HiveDriver");} catch(ClassNotFoundException e){throw new SQLException("Hive JDBC 驱动不可用",e);}
        String url="jdbc:hive2://"+value(cfg,"host","")+":"+value(cfg,"port","10000")+"/"+value(cfg,"database","default")+";socketTimeout="+Math.max(1000,timeoutMillis);
        return DriverManager.getConnection(url,value(cfg,"username","hive"),password(cfg));
    }
    /**
     * 执行有界连通性探测。
     *
     * <p>Kafka 只描述集群节点，Hive 只执行 SELECT 1；二者都不读取业务数据。
     *
     * @param type 数据源类型
     * @param cfg 已解密配置
     * @throws com.el2ck.exception.ApiException 校验或远端连接失败时抛出
     */
    public static void test(String type,Map<String,Object> cfg) {
        validate(type,cfg);
        if(DataSource.KAFKA.equals(type)) {
            Admin admin=kafka(cfg);
            try {admin.describeCluster().nodes().get(10,TimeUnit.SECONDS);}
            catch(Exception e){throw failure("Kafka 连接失败",e);}
            finally {admin.close(Duration.ofSeconds(1));}
        } else {
            try(Connection c=hive(cfg);Statement s=c.createStatement()) {
                s.setQueryTimeout(10);
                try(ResultSet r=s.executeQuery("SELECT 1")){if(!r.next())throw new SQLException("Empty response");}
            } catch(Exception e){throw failure("Hive 连接失败",e);}
        }
    }
    /**
     * 枚举 Kafka Topic 或 Hive 表。
     *
     * @param type 数据源类型
     * @param cfg 已解密配置
     * @return 按名称排序的 name/kind 结构；Hive 使用服务端返回顺序
     */
    public static List<Map<String,Object>> objects(String type,Map<String,Object> cfg) {
        List<Map<String,Object>> out=new ArrayList<>();
        if(DataSource.KAFKA.equals(type)) {
            Admin admin=kafka(cfg);
            try {
                for(String name:new TreeSet<>(admin.listTopics().names().get(10,TimeUnit.SECONDS)))out.add(object(name,"topic"));
            }catch(Exception e){throw failure("读取 Kafka Topic 失败",e);}finally{admin.close(Duration.ofSeconds(1));}
        } else {
            try(Connection c=hive(cfg);Statement s=c.createStatement()) {
                s.setQueryTimeout(10);
                try(ResultSet r=s.executeQuery("SHOW TABLES")){while(r.next())out.add(object(r.getString(1),"table"));}
            }catch(Exception e){throw failure("读取 Hive 表失败",e);}
        }
        return out;
    }
    /**
     * 通过对象枚举判断 Kafka Topic 或 Hive 表是否存在。
     *
     * @param type 数据源类型
     * @param cfg 已解密配置
     * @param object 对象名
     * @return 存在时为 true
     */
    public static boolean exists(String type,Map<String,Object> cfg,String object) {
        for(Map<String,Object> item:objects(type,cfg))if(Objects.equals(item.get("name"),object))return true;
        return false;
    }
    /**
     * 读取 Hive 表字段定义。
     *
     * @param cfg 已解密 Hive 配置
     * @param table 合法 Hive 表名
     * @return 字段名与类型列表
     * @throws com.el2ck.exception.ApiException 表名非法或远端读取失败时抛出
     */
    public static List<Column> columns(Map<String,Object> cfg,String table) {
        if(table==null||!table.matches("[A-Za-z_][A-Za-z0-9_]*"))throw ApiException.validation("Hive 表名非法");
        List<Column> out=new ArrayList<>();
        try(Connection c=hive(cfg);Statement s=c.createStatement()) {
            s.setQueryTimeout(10);
            try(ResultSet r=s.executeQuery("DESCRIBE `"+table+"`")) {
                while(r.next()) {
                    String name=r.getString(1);
                    if(name==null||name.trim().isEmpty()||name.startsWith("#"))break;
                    Column col=new Column();col.setName(name.trim());col.setType(r.getString(2));out.add(col);
                }
            }
        }catch(Exception e){throw failure("读取 Hive 字段失败",e);}
        return out;
    }
    /**
     * 构造数据源对象描述。
     *
     * @param name 对象名
     * @param kind topic 或 table
     * @return 保持插入顺序的 name/kind 映射
     */
    private static Map<String,Object> object(String name,String kind) {
        Map<String,Object> m=new LinkedHashMap<>();m.put("name",name);m.put("kind",kind);return m;
    }
    /**
     * 将远端连接异常转换为统一 502，并保留线程中断标记。
     *
     * @param message 面向用户的操作说明
     * @param e 原始异常
     * @return 带异常类型提示的 ApiException
     */
    private static ApiException failure(String message,Exception e) {
        if(e instanceof InterruptedException)Thread.currentThread().interrupt();
        return ApiException.badGateway(message+"，请检查地址、服务状态、认证和访问权限（"+e.getClass().getSimpleName()+"）");
    }
}
