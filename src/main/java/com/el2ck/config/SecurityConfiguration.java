package com.el2ck.config;
/**
 * 【文件职责】定义 Spring Security 过滤链、会话策略和公开路径边界。
 */

import com.el2ck.service.auth.*;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
/**
 * SecurityConfiguration：定义 Spring Security 过滤链、会话策略和公开路径边界。
 */
@Configuration
public class SecurityConfiguration {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,AppUserService users,AccountSecurityService accounts) throws Exception {
        http.csrf().csrfTokenRepository(new HttpSessionCsrfTokenRepository());
        http.authorizeRequests().antMatchers("/api/login","/api/csrf","/api/health","/api/health/**").permitAll()
            .antMatchers("/api/**").authenticated().anyRequest().permitAll();
        http.exceptionHandling()
            .authenticationEntryPoint((req,res,e)->BasicAuthFilter.writeJson(res,401,"请先登录"))
            .accessDeniedHandler((req,res,e)->BasicAuthFilter.writeJson(res,403,"请求校验失败或无权访问"));
        http.formLogin().disable().httpBasic().disable().logout().disable().requestCache().disable();
        http.addFilterAfter(new SessionAuthFilter(users,accounts),AnonymousAuthenticationFilter.class);
        http.addFilterAfter(new RoleAuthFilter(),SessionAuthFilter.class);
        return http.build();
    }
}
