package com.campus.lf.module.item.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.lf.module.item.entity.CampusArea;
import org.apache.ibatis.annotations.Mapper;

/**
 * 校区/楼栋两级自连接字典 —— 自定义 SQL（回收站/圈定候选/矩阵聚合）随对应模块追加，
 * 骨架期只有 BaseMapper 能力。
 */
@Mapper
public interface CampusAreaMapper extends BaseMapper<CampusArea> {
}
