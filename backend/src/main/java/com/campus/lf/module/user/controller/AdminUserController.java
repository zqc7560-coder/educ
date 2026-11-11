package com.campus.lf.module.user.controller;

import com.campus.lf.common.PageResult;
import com.campus.lf.common.Result;
import com.campus.lf.module.user.dto.AssignRolesDTO;
import com.campus.lf.module.user.service.AdminUserService;
import com.campus.lf.module.user.vo.UserVO;
import com.campus.lf.security.LoginUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端用户治理接口（M2）—— 全部挂 @PreAuthorize('user:manage')：
 * 越权三防的第二层（方法级鉴权），前端菜单隐藏只是体验优化。
 */
@Tag(name = "管理-用户治理", description = "用户查询/状态处置/角色分配")
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminUserService adminUserService;

    /** GET /api/admin/users：分页查询（keyword 模糊学号/昵称/邮箱，status 精确）。 */
    @Operation(summary = "用户分页")
    @GetMapping
    @PreAuthorize("hasAuthority('user:manage')")
    public Result<PageResult<UserVO>> page(@RequestParam(required = false) String keyword,
                                           @RequestParam(required = false) Integer status,
                                           @RequestParam(defaultValue = "1") long pageNum,
                                           @RequestParam(defaultValue = "20") long pageSize) {
        return Result.ok(adminUserService.pageUsers(keyword, status, pageNum, pageSize));
    }

    /** PATCH /api/admin/users/{id}/status?status=0|1|2：封禁即踢线；禁言不禁读。 */
    @Operation(summary = "启用/封禁/禁言（禁自己=403）")
    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAuthority('user:manage')")
    public Result<Void> changeStatus(@AuthenticationPrincipal LoginUser me,
                                     @PathVariable Long id,
                                     @RequestParam Integer status) {
        adminUserService.changeStatus(me.getUserId(), id, status);
        return Result.ok();
    }

    /** POST /api/admin/users/{id}/roles：整组覆盖角色分配（改后强制重登生效）。 */
    @Operation(summary = "分配角色（移除自己ADMIN=403）")
    @PostMapping("/{id}/roles")
    @PreAuthorize("hasAuthority('user:manage')")
    public Result<Void> assignRoles(@AuthenticationPrincipal LoginUser me,
                                    @PathVariable Long id,
                                    @Valid @RequestBody AssignRolesDTO dto) {
        adminUserService.assignRoles(me.getUserId(), id, dto.getRoleIds());
        return Result.ok();
    }
}
