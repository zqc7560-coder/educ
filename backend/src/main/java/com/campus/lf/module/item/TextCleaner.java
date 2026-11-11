package com.campus.lf.module.item;

import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;

/**
 * 文本清洗纯函数（服务端 XSS 第一道闸；第二道=前端转义渲染）。
 *
 * <p>物品描述/帖子正文/公告内容全部按"纯文本"定位：jsoup Safelist.none()
 * 剥掉一切标签与事件属性，实体解码后再剥（防 &lt;script&gt; 混淆绕过——
 * jsoup clean 先解码后剥离的性质，博客 M3 已验证）；换行折叠规则：
 * 连续 3+ 换行压成 2（保留段落感），行尾空白清除，制表符转四空格。
 *
 * <p>清洗在 Service 写库前执行——库里存的永远是干净文本，读侧免二次处理；
 * 与敏感词闸门的顺序：先清洗后查词（词表匹配的是用户"实际会被看到"的内容）。
 */
public final class TextCleaner {

    private TextCleaner() {
    }

    /**
     * HTML → 纯文本（剥标签、解码实体），再做换行治理。
     *
     * <p>双遍清洗收口实体混淆：Jsoup.clean 的输出侧会把解码后的尖括号
     * 重新转义（&lt;b&gt; 输入 → 转义形态输出，单遍清洗"没剥掉"是假象），
     * 因此第一遍 clean → Parser.unescapeEntities 解码 → 第二遍 clean，
     * 二级标签在库里彻底消失（博客 M3 MarkdownProcessor 的同款结构性收口）。
     */
    public static String clean(String raw) {
        if (raw == null) {
            return "";
        }
        String stripped = Jsoup.clean(raw, Safelist.none());
        stripped = Jsoup.clean(
                org.jsoup.parser.Parser.unescapeEntities(stripped, false), Safelist.none());
        // \u3000 全角空格不进 Java \s（博客 M11 已知边界，此处显式保留原样：
        // 中文正文的全角空格是合法排版，不该被当控制字符吃掉）
        return stripped
                .replace("\r\n", "\n")
                .replace("\t", "    ")
                .replaceAll("[ \\t]+\\n", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    /** 字符数口径：去空白后的码点数（计数与长度钳制共用，防"空格灌水"） */
    public static int visibleLength(String cleaned) {
        return (int) cleaned.codePoints()
                .filter(cp -> !Character.isWhitespace(cp))
                .count();
    }
}
