package com.campus.lf.module.storage.service;

import com.campus.lf.common.BizCode;
import com.campus.lf.common.BizException;
import com.campus.lf.module.storage.FileTypeValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/**
 * 图片存储服务（M2 先行版 → M3/M5/M6 物品图/帖图/公告图通用底座）。
 *
 * <p>与博客毕设的存储差异（本系统 DDL 没有独立 file 表）：图片归属由各业务表
 * 承载（item_image/post_image/announcement_image 存 URL），本服务只负责
 * "校验 + 落盘 + 返回 URL"三件事，不再登记 file 表——避免多一张无人消费的表。
 *
 * <p>三卡校验顺序（便宜的在前、贵的在后，全过才碰磁盘）：
 * ① 扩展名白名单；② Content-Type 主类型 image/*（可被 curl 伪造，仅第一道噪音过滤）；
 * ③ 文件头魔数嗅探且须与扩展名同族——/uploads 静态直出，"改名成 png 的 HTML"
 * 若入库即成存储型 XSS 跳板，魔数是唯一不可伪造的自证。
 *
 * <p>重命名落盘（uploads/yyyy/MM/uuid.ext）而非用原始名：原始名可含 ../，
 * 直接拼接是路径穿越漏洞；uuid 名天然防同名覆盖与并发冲突。
 */
@Slf4j
@Service
public class ImageStorageService {

    /** 大小上限：4002 错误码语义 = 5MB */
    private static final long MAX_SIZE = 5L * 1024 * 1024;

    /** 魔数嗅探读取头部字节数：WEBP 需 12 字节（RIFF+size+WEBP），取最长需求 */
    private static final int MAGIC_BYTES = 12;

    private static final DateTimeFormatter DIR_FMT = DateTimeFormatter.ofPattern("yyyy/MM");

    /** 落盘根目录：与 application.yml lf.upload.dir 同源（WebMvcConfig 静态映射用同一键） */
    @Value("${lf.upload.dir:./uploads}")
    private String uploadDir;

    /** 存一张图片，返回可访问相对 URL（/uploads/...）。 */
    public String store(MultipartFile file, String bizType, Long userId) {
        if (file == null || file.isEmpty()) {
            throw new BizException(BizCode.PARAM_ERROR, "文件为空");
        }
        if (file.getSize() > MAX_SIZE) {
            throw new BizException(BizCode.FILE_TOO_LARGE);
        }

        String original = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String ext = FileTypeValidator.extensionOf(original);
        if (!FileTypeValidator.IMAGE_EXT_WHITELIST.contains(ext)) {
            throw new BizException(BizCode.FILE_TYPE_INVALID,
                    "仅支持图片格式: " + String.join("/", FileTypeValidator.IMAGE_EXT_WHITELIST));
        }
        String contentType = file.getContentType();
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("image/")) {
            throw new BizException(BizCode.FILE_TYPE_INVALID, "文件内容类型非图片");
        }
        String sniffed = sniffMagic(file);
        if (sniffed == null) {
            throw new BizException(BizCode.FILE_TYPE_INVALID, "文件内容与图片格式不符（魔数校验失败）");
        }
        if (!sameImageFamily(ext, sniffed)) {
            // 落盘后缀决定 Content-Type 响应头，跨族文件会让下游按错误类型解析（浏览器宽容解析是 XSS 温床）
            throw new BizException(BizCode.FILE_TYPE_INVALID, "扩展名与文件实际格式不符");
        }

        String relative = LocalDate.now().format(DIR_FMT) + "/"
                + UUID.randomUUID().toString().replace("-", "") + "." + ext;
        Path target = Paths.get(uploadDir).toAbsolutePath().normalize().resolve(relative);
        try {
            Files.createDirectories(target.getParent());
            file.transferTo(target);
        } catch (IOException e) {
            log.error("图片落盘失败 biz={} userId={}", bizType, userId, e);
            throw new BizException(BizCode.SYSTEM_ERROR, "文件保存失败");
        }
        String url = "/uploads/" + relative;
        log.info("图片上传成功 biz={} userId={} url={}", bizType, userId, url);
        return url;
    }

    /** 读文件头魔数（独立方法便于单测替身）；IO 失败按"非法文件"拒收而非放行 */
    private String sniffMagic(MultipartFile file) {
        byte[] head = new byte[MAGIC_BYTES];
        try (InputStream in = file.getInputStream()) {
            int read = 0;
            while (read < MAGIC_BYTES) {
                int n = in.read(head, read, MAGIC_BYTES - read);
                if (n < 0) {
                    break; // 短文件：读到 EOF，按已读部分比对
                }
                read += n;
            }
        } catch (IOException e) {
            log.warn("读取上传文件头失败，按非法文件处理: {}", e.getMessage());
            return null;
        }
        return FileTypeValidator.sniffExtension(head);
    }

    /** 扩展名与嗅探结果是否同族：jpg/jpeg 互为同义，其余要求严格相等 */
    private boolean sameImageFamily(String declaredExt, String sniffed) {
        String a = declaredExt.equals("jpeg") ? "jpg" : declaredExt;
        return a.equals(sniffed);
    }
}
