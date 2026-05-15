package com.ruoyi.system.mapper;

import com.ruoyi.system.api.domain.SysNotice;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 公告 数据层
 *
 * @author ruoyi
 */
public interface SysNoticeMapper
{
    /**
     * 查询公告信息（含岗位/角色关联）
     */
    SysNotice selectNoticeById(Long noticeId);

    /**
     * 查询公告列表（含岗位/角色关联）
     */
    List<SysNotice> selectNoticeList(@Param("notice") SysNotice notice, @Param("userId") Long userId);

    /**
     * 新增公告
     */
    int insertNotice(SysNotice notice);

    /**
     * 批量插入通知岗位关联
     */
    int insertNoticePost(@Param("noticeId") Long noticeId, @Param("postIds") List<Long> postIds);

    /**
     * 批量插入通知角色关联
     */
    int insertNoticeRole(@Param("noticeId") Long noticeId, @Param("roleIds") List<Long> roleIds);

    /**
     * 修改公告
     */
    int updateNotice(SysNotice notice);

    /**
     * 删除公告
     */
    int deleteNoticeById(Long noticeId);

    /**
     * 删除单条通知的岗位关联
     */
    int deleteNoticePostByNoticeId(Long noticeId);

    /**
     * 删除单条通知的角色关联
     */
    int deleteNoticeRoleByNoticeId(Long noticeId);

    /**
     * 批量删除公告
     */
    int deleteNoticeByIds(Long[] noticeIds);

    /**
     * 批量删除通知岗位关联
     */
    int deleteNoticePostByNoticeIds(Long[] noticeIds);

    /**
     * 批量删除通知角色关联
     */
    int deleteNoticeRoleByNoticeIds(Long[] noticeIds);
}