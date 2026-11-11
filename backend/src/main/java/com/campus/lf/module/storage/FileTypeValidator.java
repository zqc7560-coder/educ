package com.campus.lf.module.storage;

import java.util.Locale;
import java.util.Set;

/**
 * 图片文件类型校验器 —— 扩展名白名单 + 魔数（文件头签名）双维校验的纯函数集。
 *
 * <p>攻击面说明：/uploads 是静态直出目录，"把 HTML/SVG 伪装成 png 传上去再被
 * 浏览器直接打开"就是存储型 XSS 的经典路径。Content-Type 完全由客户端自报
 * （curl 一行即可伪造），文件头魔数是唯一不可伪造的自证——所以两卡之外必须有第三卡。
 *
 * <p>纯 static 函数、零 Spring 依赖：可被存储 Service 与单测直接调用，
 * 不需要拉起重型上下文就能锁死安全规则。
 */
public final class FileTypeValidator {

    /** 扩展名白名单：课设场景只收常见位图（gif 收，svg 拒——svg 可内嵌脚本） */
    public static final Set<String> IMAGE_EXT_WHITELIST = Set.of("png", "jpg", "jpeg", "webp", "gif");

    private FileTypeValidator() {
    }

    /** 取小写扩展名（无扩展名或 "."结尾 返回空串，交给白名单拒绝） */
    public static String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** 扩展名 → MIME；非白名单返回 null */
    public static String mimeOf(String ext) {
        return switch (ext) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "webp" -> "image/webp";
            case "gif" -> "image/gif";
            default -> null;
        };
    }

    /**
     * 魔数嗅探：比对文件头签名（调用方喂 12 字节即可覆盖最长签名 WEBP）。
     * 返回归一化扩展名（jpeg 并入 jpg 族），无法识别返回 null。
     */
    public static String sniffExtension(byte[] header) {
        if (header == null) {
            return null;
        }
        // PNG: 89 50 4E 47 0D 0A 1A 0A（八字节完整签名）
        if (startsWith(header, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
            return "png";
        }
        // JPEG: FF D8 FF（FF D8 单独用会误收残缺文件，FF D8 FF 才是 JFIF 常态）
        if (startsWith(header, 0xFF, 0xD8, 0xFF)) {
            return "jpg";
        }
        // GIF: 'GIF87a' / 'GIF89a'
        if (startsWith(header, 'G', 'I', 'F', '8', '7', 'a')
                || startsWith(header, 'G', 'I', 'F', '8', '9', 'a')) {
            return "gif";
        }
        // WEBP: RIFF????WEBP —— 4~8 字节是 LE 容器长度跳过；只查 RIFF 会把 wav/avi 放进图片白名单
        if (header.length >= 12
                && header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F'
                && header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P') {
            return "webp";
        }
        return null;
    }

    /** 变参 int 便于书写 0x89/'P' 混合字面量；按字节低 8 位掩码归一 */
    private static boolean startsWith(byte[] data, int... signature) {
        if (data.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if ((data[i] & 0xFF) != (signature[i] & 0xFF)) {
                return false;
            }
        }
        return true;
    }
}
