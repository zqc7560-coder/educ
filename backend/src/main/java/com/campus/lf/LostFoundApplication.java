package com.campus.lf;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 校园失物招领系统后端启动类（M0 工程骨架）。
 *
 * <p>包结构约定（见《系统设计.md》§1 分层职责）：
 * <pre>
 * com.campus.lf
 * ├── common    统一响应体 Result、错误码 BizCode、全局异常、工具
 * ├── config    MyBatis-Plus / Redis / OpenAPI / Web 等横切配置
 * ├── security  （M2 填充）JwtAuthFilter、JwtTokenProvider
 * ├── module    user / item / claim / forum / announce / notice / admin / storage / system
 * └── job       （M4/M6 填充）认领超时提醒、公告过期下架定时任务
 * </pre>
 *
 * <p>@MapperScan 扫描 module.*.mapper：每个业务模块自带 mapper 子包，
 * 模块间只能通过 Service 互相调用，不允许跨模块直接用别人的 Mapper——
 * 这条规则是为了毕业答辩时模块边界清晰，日后拆分微服务成本低。
 */
@SpringBootApplication
@MapperScan("com.campus.lf.module.**.mapper")
@EnableScheduling   // M4 认领超时提醒 / M6 公告过期下架 的调度开关
public class LostFoundApplication {

    public static void main(String[] args) {
        SpringApplication.run(LostFoundApplication.class, args);
    }
}
