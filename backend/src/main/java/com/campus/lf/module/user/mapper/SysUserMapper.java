package com.campus.lf.module.user.mapper;

import com.campus.lf.module.user.entity.SysUser;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 用户 Mapper —— 额外承担 RBAC 聚合两查询（登录签发令牌时一次取全角色码与权限点码）。
 *
 * <p>为什么裸 @Select 而不是 XML/封装查询构造器：两条都是三表 JOIN，
 * MP 的 Wrapper 表达不了 JOIN；聚合语义（DISTINCT、deleted=0 双侧过滤）用 SQL
 * 直白可审。列名 student_no/perm_code 与本仓库 schema.sql 逐字对齐，
 * DatabaseInitM1Test 的列比对改动时会在这里显式失败（select 列不存在=容器启动即抛）。
 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {

    /** 用户的角色码集合（ROLE_STUDENT/ROLE_MANAGER/ROLE_ADMIN），登录时进 JWT claims */
    @Select("SELECT DISTINCT r.role_code FROM sys_role r "
            + "JOIN sys_user_role ur ON ur.role_id = r.id "
            + "WHERE ur.user_id = #{userId} AND r.deleted = 0")
    List<String> selectRoleCodesByUserId(@Param("userId") Long userId);

    /** 用户经角色传递闭包得到的权限点集合（item:audit...），登录时进 JWT claims */
    @Select("SELECT DISTINCT p.perm_code FROM sys_permission p "
            + "JOIN sys_role_permission rp ON rp.perm_id = p.id "
            + "JOIN sys_user_role ur ON ur.role_id = rp.role_id "
            + "WHERE ur.user_id = #{userId} AND p.deleted = 0")
    List<String> selectPermCodesByUserId(@Param("userId") Long userId);
}
