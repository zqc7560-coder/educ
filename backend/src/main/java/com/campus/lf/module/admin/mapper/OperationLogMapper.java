package com.campus.lf.module.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.lf.module.admin.entity.OperationLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 操作审计日志 —— 自定义 SQL（回收站/圈定候选/矩阵聚合）随对应模块追加，
 * 骨架期只有 BaseMapper 能力。
 */
@Mapper
public interface OperationLogMapper extends BaseMapper<OperationLog> {
}
