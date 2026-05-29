package com.ruoyi.system.service.impl;

import com.ruoyi.common.security.utils.SecurityUtils;
import com.ruoyi.system.api.domain.SysNotice;
import com.ruoyi.system.api.model.LoginUser;
import com.ruoyi.system.controller.SysNoticeController;
import com.ruoyi.system.mapper.SysNoticeMapper;
import com.ruoyi.system.mapper.SysNoticePostMapper;
import com.ruoyi.system.mapper.SysNoticeRoleMapper;
import com.ruoyi.system.service.ISysNoticeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 公告 服务层实现
 *
 * @author ruoyi
 */
@Service
public class SysNoticeServiceImpl implements ISysNoticeService
{
    private static final Logger log = LoggerFactory.getLogger(SysNoticeServiceImpl.class);

    @Autowired
    private SysNoticeMapper noticeMapper;

    @Autowired
    private SysNoticePostMapper sysNoticePostMapper;

    @Autowired
    private SysNoticeRoleMapper sysNoticeRoleMapper;

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
     * 新增公告（插入成功后推送通知）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int insertNotice(SysNotice notice)
    {
        int rows = noticeMapper.insertNotice(notice);
        insertNoticeAuth(notice);
        if (rows > 0) {
            pushNotice(notice.getNoticeId(), notice.getNoticeTitle(), "INSERT");
        }
        return rows;
    }

    /**
     * 修改公告（先清空关联表再重新写入，并记录日志）
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
     * 删除公告（删除前查出标题用于日志，同步删除关联表）
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

    /**
     * 推送公告通知（对应原 Canal handleNoticeChange 里 INSERT 的逻辑）
     * 有岗位/角色限制则定向推送，否则全员广播
     */
    private void pushNotice(Long noticeId, String noticeTitle, String eventType)
    {
        List<Long> postIds = sysNoticePostMapper.selectPostIdsByNoticeId(noticeId);
        List<Long> roleIds = sysNoticeRoleMapper.selectRoleIdsByNoticeId(noticeId);

        Map<String, Object> data = new HashMap<>();
        data.put("database", "ry-cloud");   // 原来从 Canal entry.getHeader().getSchemaName() 取
        data.put("table", "sys_notice");     // 原来从 Canal entry.getHeader().getTableName() 取
        data.put("eventType", eventType);
        data.put("timestamp", System.currentTimeMillis());
        // rows 字段原来是从 Canal RowData 解析的列值，这里直接查库替代
        SysNotice notice = noticeMapper.selectNoticeById(noticeId);
        data.put("rows", notice);

        if (postIds.isEmpty() && roleIds.isEmpty()) {
            SysNoticeController.broadcast(data);
        } else {
            Set<Long> targetUserIds = new HashSet<>();
            if (!postIds.isEmpty()) {
                targetUserIds.addAll(sysNoticePostMapper.selectUserIdsByPostIds(postIds));
            }
            if (!roleIds.isEmpty()) {
                targetUserIds.addAll(sysNoticeRoleMapper.selectUserIdsByRoleIds(roleIds));
            }
            SysNoticeController.broadcastToUsers(targetUserIds, data);
        }
        log.info("新增公告: {}, 目标岗位: {}, 目标角色: {}", noticeTitle, postIds, roleIds);
    }
}