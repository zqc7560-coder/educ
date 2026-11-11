package com.campus.lf.config;

import com.campus.lf.common.BizCode;
import com.campus.lf.common.Result;
import com.campus.lf.security.JwtAuthFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.nio.charset.StandardCharsets;

/**
 * Spring Security 过滤器链装配（M2 起为真实链，替换 M0 的"全放行"占位）。
 *
 * <p>四条策略（《开发方案.md》§3.4）：
 * <ul>
 *   <li>无状态：STATELESS Session，凭据只有 JWT（不存在 Cookie/Session 通道）；</li>
 *   <li>CSRF 关闭：无 Cookie 自动携带会话即无 CSRF 攻击面，REST + Bearer 标准处置；</li>
 *   <li>默认拒绝：白名单放行而非黑名单拦截——新接口忘记登记时漏的是 401 而不是裸奔；</li>
 *   <li>@PreAuthorize 方法级鉴权启用（越权三防的第二层，第三层在 Service 的
 *       WHERE id=登录者）。</li>
 * </ul>
 *
 * <p>未认证/无权限的响应同样走统一信封：AuthenticationEntryPoint→1001/401，
 * AccessDeniedHandler→1005/403。若用 Spring 默认的 WWW-Authenticate 空 401，
 * 前端 Axios 拦截器拿不到 code 字段，"1001/1004 跳登录"的契约就会断。
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final ObjectMapper objectMapper;

    /** 公开路径白名单：认证入口、健康检查、接口文档、上传直出 */
    private static final String[] PUBLIC_PATHS = {
            "/api/auth/login",
            "/api/auth/register",
            "/api/health",
            "/uploads/**",
            "/doc.html",
            "/webjars/**",
            "/v3/api-docs/**",
            "/favicon.ico",
            "/error"
    };

    /**
     * 只读公开 GET（游客可浏览，写操作全部走登录+权限点）。
     * M2 阶段还没有业务只读接口，清单先声明公告一项（M6 落地后即生效）——
     * 白名单与模块进度同步追加，是"忘记登记=401"纪律的反向保险。
     */
    private static final String[] PUBLIC_GETS = {
            // 物品域只读公开（写操作与 mine=1 在 Controller 显式拦匿名——博客 M3 同款）
            "/api/items",
            "/api/items/*",
            // 论坛读口公开（游客可逛帖；写口全部 authenticated）
            "/api/forum/posts",
            "/api/forum/posts/*",
            "/api/forum/posts/*/replies",
            "/api/announcements",
            "/api/announcements/active",
            "/api/announcements/*",
            "/api/categories",
            "/api/areas"
    };

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> { /* 放行给 WebMvcConfig.addCorsMappings（同源开发代理下几乎不触发） */ })
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .requestMatchers(HttpMethod.GET, PUBLIC_GETS).permitAll()
                        // OPTIONS 预检不带 Authorization 头，必须放行否则跨域调试全挂
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((req, res, e) ->
                                writeJson(res, HttpStatus.UNAUTHORIZED, BizCode.UNAUTHORIZED))
                        .accessDeniedHandler((req, res, e) ->
                                writeJson(res, HttpStatus.FORBIDDEN, BizCode.FORBIDDEN)))
                // JWT 过滤器插在用户名密码认证器之前：有 Bearer 就在此完成认证
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /** 与 JwtAuthFilter.writeError 同款：Security 层异常不经过 MVC，信封手工序列化 */
    private void writeJson(HttpServletResponse response, HttpStatus status, BizCode biz)
            throws java.io.IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(Result.fail(biz)));
    }
}
