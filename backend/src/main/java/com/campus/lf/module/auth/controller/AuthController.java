package com.campus.lf.module.auth.controller;

import com.campus.lf.common.Result;
import com.campus.lf.module.auth.dto.LoginDTO;
import com.campus.lf.module.auth.dto.RegisterDTO;
import com.campus.lf.module.auth.service.AuthService;
import com.campus.lf.module.auth.vo.LoginVO;
import com.campus.lf.module.user.vo.UserVO;
import com.campus.lf.security.LoginUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 认证接口（M2）。
 *
 * <p>@AuthenticationPrincipal LoginUser 是 JwtAuthFilter 写入上下文的当前用户：
 * 身份取自"已验签的令牌"，不接受任何请求参数里的 userId——这就是越权三防的
 * 第一层（JWT 身份不可伪造）在 Controller 的落点。
 *
 * <p>register/login 在 SecurityConfig 白名单里公开；logout/refresh/me 走
 * anyRequest().authenticated()（持有效令牌即可，无需特定权限点）。
 */
@Tag(name = "认证", description = "注册/登录/登出/续期/当前用户")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /** POST /api/auth/register（公开）。data={userId}——单字段载荷用 Map，YAGNI。 */
    @Operation(summary = "注册（学号+邮箱唯一）")
    @PostMapping("/register")
    public Result<Map<String, Long>> register(@Valid @RequestBody RegisterDTO dto) {
        Long userId = authService.register(dto);
        return Result.ok(Map.of("userId", userId));
    }

    /** POST /api/auth/login（公开）。防枚举统一 1002，封禁 1003。 */
    @Operation(summary = "登录，返回 JWT")
    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginDTO dto) {
        return Result.ok(authService.login(dto));
    }

    /**
     * POST /api/auth/logout（登录）。幂等语义由过滤器保证：能进到这里的请求
     * 必然持有有效且未拉黑的令牌；重复登出第二次会直接 401（jti 已黑），
     * 前端把 401 当"已登出"处理即可（本地清 token 是必做的）。
     */
    @Operation(summary = "登出（Token 拉黑）")
    @PostMapping("/logout")
    public Result<Void> logout(@AuthenticationPrincipal LoginUser loginUser) {
        authService.logout(loginUser);
        return Result.ok();
    }

    /**
     * POST /api/auth/refresh（登录）。滑动续期：剩余 ≥30min 时 data 回
     * {renewed:false} 不换发（防无限续命，见 AuthService.refresh）；
     * <30min 换发新令牌 {renewed:true, token, expiresIn, user}。
     */
    @Operation(summary = "滑动续期（剩余<30min 才换发新 Token）")
    @PostMapping("/refresh")
    public Result<Object> refresh(@AuthenticationPrincipal LoginUser loginUser) {
        LoginVO renewed = authService.refresh(loginUser);
        if (renewed == null) {
            return Result.ok(Map.of("renewed", false));
        }
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("renewed", true);
        body.put("token", renewed.getToken());
        body.put("expiresIn", renewed.getExpiresIn());
        body.put("user", renewed.getUser());
        return Result.ok(body);
    }

    /** GET /api/auth/me（登录）：当前登录用户信息 + 权限点 */
    @Operation(summary = "当前登录用户信息+权限点")
    @GetMapping("/me")
    public Result<UserVO> me(@AuthenticationPrincipal LoginUser loginUser) {
        return Result.ok(authService.me(loginUser));
    }
}
