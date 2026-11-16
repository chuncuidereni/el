package com.el2ck.config;

/**
 * 【文件职责】启用并配置定时任务执行器，为同步和 ARB 定时调度提供线程池。
 */

import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 调度装配:{@code @EnableScheduling} 的定时任务支持,以及可选的 XXL-JOB 执行器。
 *
 * <p>装配职责原属已删除的 {@code WebBeans},按职责拆成四类后的其中一类。
 *
 * <p>XXL-JOB 执行器默认关闭 —— 它是外部调度中心(Admin)驱动任务的接入点,
 * 只有显式配置 {@code xxl.job.enabled=true} 才向 Admin 注册;不配置时平台仍可正常
 * 使用内置的定时同步,不受影响。
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class SchedulingConfiguration {

    /** XXL-JOB 执行器；默认关闭，配置 xxl.job.enabled=true 后向 Admin 注册。 */
    @Bean(initMethod = "start", destroyMethod = "destroy")
    @ConditionalOnProperty(prefix = "xxl.job", name = "enabled", havingValue = "true")
    public XxlJobSpringExecutor xxlJobExecutor(Environment env) {
        XxlJobSpringExecutor executor = new XxlJobSpringExecutor();
        // 各配置项均带缺省回退值：Admin 地址缺省为空串、执行器名缺省 el-to-ck、端口缺省 9999、日志目录缺省 ./data/xxl-job
        executor.setAdminAddresses(env.getProperty("xxl.job.admin-addresses", ""));
        executor.setAppname(env.getProperty("xxl.job.executor.appname", "el-to-ck"));
        executor.setPort(Integer.parseInt(env.getProperty("xxl.job.executor.port", "9999")));
        executor.setLogPath(env.getProperty("xxl.job.executor.log-path", "./data/xxl-job"));
        return executor;
    }

}
