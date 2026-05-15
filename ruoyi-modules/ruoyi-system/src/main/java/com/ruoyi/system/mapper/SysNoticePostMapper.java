package com.ruoyi.system.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface SysNoticePostMapper {

    /**
     * 查询通知关联的岗位ID列表
     */
    List<Long> selectPostIdsByNoticeId(@Param("noticeId") Long noticeId);

    /**
     * 通过岗位ID列表查询用户ID列表
     */
    List<Long> selectUserIdsByPostIds(@Param("postIds") List<Long> postIds);
}
