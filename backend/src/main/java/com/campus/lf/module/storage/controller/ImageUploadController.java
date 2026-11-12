package com.campus.lf.module.storage.controller;

import com.campus.lf.common.BizCode;
import com.campus.lf.common.BizException;
import com.campus.lf.common.Result;
import com.campus.lf.module.storage.service.ImageStorageService;
import com.campus.lf.security.LoginUser;
import lombok.RequiredArgsConstructor;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 图片上传通用口（M10 补齐后端缺口）：论坛/公告/物品发布页"先传后贴"的通道。
 *
 * <p>历史：M2 建 ImageStorageService 时业务页未落地，Controller 一直没挂；
 * M3/M5/M6 的 imageUrls 都只收"已上传的 /uploads 相对路径"（外链 400），
 * 本口是那些路径的唯一合法产地。scene 参数只做目录归档语义（items/forum/
 * announcements 子目录），不做白名单硬校验——校验在魔数层，路径层无风险。
 */
@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class ImageUploadController {

    private static final int MAX_COUNT = 9;

    private final ImageStorageService storage;

    /** POST /api/files/images（登录，multipart）：1–9 张，返回 {urls:[...]} */
    @Operation(summary = "上传图片（业务图 0–9 张，≤5MB/张）")
    @PostMapping("/images")
    public Result<Map<String, List<String>>> upload(@AuthenticationPrincipal LoginUser me,
                                                    @RequestPart("files") MultipartFile[] files,
                                                    @RequestParam(defaultValue = "misc") String scene) {
        if (files == null || files.length == 0) {
            throw new BizException(BizCode.PARAM_ERROR, "未选择文件");
        }
        if (files.length > MAX_COUNT) {
            throw new BizException(BizCode.PARAM_ERROR, "一次最多 " + MAX_COUNT + " 张");
        }
        List<String> urls = new java.util.ArrayList<>();
        for (MultipartFile f : files) {
            urls.add(storage.store(f, scene, me.getUserId()));
        }
        return Result.ok(Map.of("urls", urls));
    }
}
