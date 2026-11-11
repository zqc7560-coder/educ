package com.campus.lf.module.item.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.lf.module.item.entity.LostItem;
import org.apache.ibatis.annotations.Mapper;

/**
 * 物品 Mapper。自定义 SQL（回收站三兄弟/相似候选圈定）随 M3 追加，
 * 骨架期只有 BaseMapper 能力。
 */
@Mapper
public interface LostItemMapper extends BaseMapper<LostItem> {
}
