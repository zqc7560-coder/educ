package com.campus.lf.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spring Security 装配（M0 占位版）。
 *
 * <p>为什么骨架期就要碰它：pom 引了 spring-boot-starter-security，默认策略是
 * "全站 401 + 内存随机密码"——不显式配置任何 /api 接口都进不来（M0 健康检查
 * 测试就是这么红的）。本类是<b>故意的临时放行</b>：M2 替换为真实无状态认证链
 * （JwtAuthFilter + 白名单默认拒 + CSRF 关闭），届时本注释即被推翻重写。
 *
 * <p>CSRF 关闭的口径提前声明：系统全程无状态 JWT（Authorization 头），不存在
 * Cookie 自动携带凭据的 CSRF 面，这是 REST API 的标准处置而非妥协。
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())   // M2 维持同口径（无状态 API 无 CSRF 面）
                .authorizeHttpRequests(auth -> auth
                        // M0 占位：全放行，让健康检查与骨架测试先跑通
                        .anyRequest().permitAll());
        return http.build();
    }
}
