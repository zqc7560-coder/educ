package com.campus.lf.module.user.controller;

import com.campus.lf.common.Result;
import com.campus.lf.module.user.dto.ChangePasswordDTO;
import com.campus.lf.module.user.dto.ProfileUpdateDTO;
import com.campus.lf.module.user.service.UserService;
import com.campus.lf.module.user.vo.UserVO;
import com.campus.lf.security.LoginUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户自助接口（M2）：资料读写、改密、头像。全部走登录态（SecurityConfig
 * 默认拒），身份只取自令牌（@AuthenticationPrincipal），targetUserId 仅只读。
 */
@Tag(name = "用户", description = "个人资料/改密/头像")
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /** GET /api/users/profile?userId=（登录）：缺省查自己；查他人隐 PII。 */
    @Operation(summary = "查看资料（他人视角隐去学号/邮箱/电话等隐私字段）")
    @GetMapping("/profile")
    public Result<UserVO> profile(@AuthenticationPrincipal LoginUser me,
                                  @RequestParam(required = false) Long userId) {
        return Result.ok(userService.getProfile(me, userId));
    }

    /** PUT /api/users/profile（登录，仅本人）：昵称/邮箱/电话白名单。 */
    @Operation(summary = "修改资料（白名单字段，学号不可改）")
    @PutMapping("/profile")
    public Result<UserVO> updateProfile(@AuthenticationPrincipal LoginUser me,
                                        @Valid @RequestBody ProfileUpdateDTO dto) {
        return Result.ok(userService.updateProfile(me, dto));
    }

    /** PUT /api/users/password（登录，仅本人）：成功后全端下线（水位抬升）。 */
    @Operation(summary = "修改密码（成功即当前令牌作废、全端需重登）")
    @PutMapping("/password")
    public Result<Void> changePassword(@AuthenticationPrincipal LoginUser me,
                                       @Valid @RequestBody ChangePasswordDTO dto) {
        userService.changePassword(me, dto);
        return Result.ok();
    }

    /** POST /api/users/avatar（登录，multipart）：三卡校验（扩展名/MIME/魔数）。 */
    @Operation(summary = "上传头像（≤5MB 图片）")
    @PostMapping("/avatar")
    public Result<UserVO> avatar(@AuthenticationPrincipal LoginUser me,
                                 @RequestPart("file") MultipartFile file) {
        return Result.ok(userService.uploadAvatar(me, file));
    }
}
