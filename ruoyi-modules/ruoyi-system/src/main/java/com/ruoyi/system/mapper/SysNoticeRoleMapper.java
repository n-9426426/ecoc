package com.ruoyi.system.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface SysNoticeRoleMapper {

    /**
     * 查询通知关联的角色ID列表
     */
    List<Long> selectRoleIdsByNoticeId(@Param("noticeId") Long noticeId);

    /**
     * 通过角色ID列表查询用户ID列表
     */
    List<Long> selectUserIdsByRoleIds(@Param("roleIds") List<Long> roleIds);
}
