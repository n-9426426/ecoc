package com.ruoyi.system.service.impl;

import com.ruoyi.common.security.utils.SecurityUtils;
import com.ruoyi.system.api.domain.SysNotice;
import com.ruoyi.system.api.model.LoginUser;
import com.ruoyi.system.mapper.SysNoticeMapper;
import com.ruoyi.system.service.ISysNoticeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 公告 服务层实现
 *
 * @author ruoyi
 */
@Service
public class SysNoticeServiceImpl implements ISysNoticeService
{
    @Autowired
    private SysNoticeMapper noticeMapper;

    /**
     * 查询公告信息（含岗位/角色关联，由 resultMap collection 自动填充）
     */
    @Override
    public SysNotice selectNoticeById(Long noticeId)
    {
        return noticeMapper.selectNoticeById(noticeId);
    }

    /**
     * 查询公告列表（含岗位/角色关联，由 resultMap collection 自动填充）
     */
    @Override
    public List<SysNotice> selectNoticeList(SysNotice notice)
    {
        LoginUser loginUser = SecurityUtils.getLoginUser();
        Long userId = loginUser.getUserid();
        if (SecurityUtils.isAdmin(userId)) {
            userId = null;
        }
        return noticeMapper.selectNoticeList(notice, userId);
    }

    /**
     * 新增公告（同步写入岗位/角色关联表）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int insertNotice(SysNotice notice)
    {
        int rows = noticeMapper.insertNotice(notice);
        insertNoticeAuth(notice);
        return rows;
    }

    /**
     * 修改公告（先清空关联表再重新写入）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateNotice(SysNotice notice)
    {
        noticeMapper.deleteNoticePostByNoticeId(notice.getNoticeId());
        noticeMapper.deleteNoticeRoleByNoticeId(notice.getNoticeId());
        insertNoticeAuth(notice);
        return noticeMapper.updateNotice(notice);
    }

    /**
     * 删除公告（同步删除关联表）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteNoticeById(Long noticeId)
    {
        noticeMapper.deleteNoticePostByNoticeId(noticeId);
        noticeMapper.deleteNoticeRoleByNoticeId(noticeId);
        return noticeMapper.deleteNoticeById(noticeId);
    }

    /**
     * 批量删除公告（同步删除关联表）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteNoticeByIds(Long[] noticeIds)
    {
        noticeMapper.deleteNoticePostByNoticeIds(noticeIds);
        noticeMapper.deleteNoticeRoleByNoticeIds(noticeIds);
        return noticeMapper.deleteNoticeByIds(noticeIds);
    }

    /**
     * 写入通知的岗位/角色关联数据（为空则跳过）
     */
    private void insertNoticeAuth(SysNotice notice)
    {
        List<Long> postIds = notice.getPostIds();
        if (postIds != null && !postIds.isEmpty())
        {
            noticeMapper.insertNoticePost(notice.getNoticeId(), postIds);
        }
        List<Long> roleIds = notice.getRoleIds();
        if (roleIds != null && !roleIds.isEmpty())
        {
            noticeMapper.insertNoticeRole(notice.getNoticeId(), roleIds);
        }
    }
}