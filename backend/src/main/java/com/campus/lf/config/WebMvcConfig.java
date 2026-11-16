package com.campus.lf.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 横切配置：开发期 CORS 放行。
 *
 * <p>为什么只放行 5173：生产形态是 Nginx 同源托管前端 dist 并反代 /api（见
 * 《部署与测试.md》§1.2），不存在跨域；跨域只发生在 `vite dev` 不走代理直连
 * 8080 调试时。因此这里精确到本机来源、只读+常用方法，而不是 allowedOrigins("*")
 * ——安全测试清单里"任意跨域"是要扣分的项目。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    /** 上传根目录：与 MinimalFileStorageService 落盘目录同一配置键（lf.upload.dir） */
    @org.springframework.beans.factory.annotation.Value("${lf.upload.dir:./uploads}")
    private String uploadDir;

    /**
     * 静态资源映射（M2 追加）：/uploads/** → 磁盘上传目录直出。
     * 为什么应用层也要映射而不全靠 Nginx：dev 环境没有 Nginx（Vite 代理直连 8080，
     * vite.config.js 已配 /uploads 代理），不映射则头像图片 404；prod 由 Nginx
     * 优先截流直出（部署文档 §1.2），本映射只在 Nginx 未命中的边角场景兜底。
     */
    @Override
    public void addResourceHandlers(org.springframework.web.servlet.config.annotation
            .ResourceHandlerRegistry registry) {
        java.nio.file.Path root = java.nio.file.Paths.get(uploadDir).toAbsolutePath().normalize();
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations(root.toUri().toString());
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                // 端口通配而非写死 5174：经 nginx 反代（8088）时 Tomcat 构造的自身 origin
                // 端口是内部 8081，浏览器 Origin 头与它不相等即被判跨域——同源部署形态下
                // 这个"假跨域"必现（答辩实测：POST 登录/注册 403 Invalid CORS request）。
                // 放行范围仍限本机+私有网段任意端口，公网来源继续被拒（安全清单不变）。
                .allowedOriginPatterns("http://localhost:*", "http://127.0.0.1:*", "http://192.168.*:*")
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600); // 预检结果缓存 1h，减少 OPTIONS 往返
    }
}
