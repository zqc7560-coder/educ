package com.campus.lf.module.forum.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.lf.module.forum.entity.PostReport;
import org.apache.ibatis.annotations.Mapper;

/** 帖子举报工单（uk 幂等，读侧走 BaseMapper 条件查询） */
@Mapper
public interface PostReportMapper extends BaseMapper<PostReport> {
}
