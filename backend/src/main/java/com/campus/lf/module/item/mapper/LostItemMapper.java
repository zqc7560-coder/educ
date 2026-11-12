package com.campus.lf.module.item.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.lf.module.item.entity.LostItem;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 物品 Mapper。回收站三兄弟随 M10 追加（自定义 SQL 通道，理由同博客 M10）：
 * 逻辑删除行对 MP 内置 API 不可见（拦截器恒拼 deleted=0），"查已删/复活/物理删"
 * 语义上必须走不拼接的自定义 SQL。
 */
@Mapper
public interface LostItemMapper extends BaseMapper<LostItem> {

    /** 回收站分页（deleted=1；keyword 打标题；最新进站的排前——updated_at 随逻辑删刷新） */
    @Select("""
            <script>
            SELECT id, type, user_id, title, description, category_id, campus_id, building_id,
                   location_text, happened_at, contact, verify_question, status, offline_reason,
                   view_count, claim_count, like_count, top_flag, published_at,
                   version, created_at, updated_at
            FROM lost_item
            WHERE deleted = 1
            <if test="keyword != null and keyword != ''">
              AND title LIKE CONCAT('%', #{keyword}, '%')
            </if>
            ORDER BY updated_at DESC, id DESC
            </script>
            """)
    IPage<LostItem> selectTrashPage(IPage<LostItem> page, @Param("keyword") String keyword);

    /** 读回收站里的那一行（restore/purge 前置态判定用） */
    @Select("""
            SELECT id, type, user_id, title, description, category_id, campus_id, building_id,
                   location_text, happened_at, contact, verify_question, status, offline_reason,
                   view_count, claim_count, like_count, top_flag, published_at,
                   version, created_at, updated_at
            FROM lost_item WHERE id = #{id} AND deleted = 1
            """)
    LostItem selectTrashedById(@Param("id") Long id);

    /** 复活：deleted 1→0；AND deleted=1 幂等护栏（重复恢复影响 0 行） */
    @Update("UPDATE lost_item SET deleted = 0, updated_at = CURRENT_TIMESTAMP WHERE id = #{id} AND deleted = 1")
    int reviveById(@Param("id") Long id);

    /** 彻底删除：物理 DELETE。WHERE 自带 deleted=1 安全锁——对未删行执行误操作在 SQL 层不可能命中 */
    @Delete("DELETE FROM lost_item WHERE id = #{id} AND deleted = 1")
    int purgeById(@Param("id") Long id);
}
