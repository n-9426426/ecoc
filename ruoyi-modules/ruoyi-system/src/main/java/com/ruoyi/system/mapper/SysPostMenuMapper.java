package com.ruoyi.system.mapper;

import com.ruoyi.system.domain.SysPostMenu;

import java.util.List;

// SysPostMenuMapper.java
public interface SysPostMenuMapper {

    /** 根据岗位ID查询菜单ID列表 */
    List<Long> selectMenuIdsByPostId(Long postId);

    /** 根据多个岗位ID查询权限标识列表 */
    List<String> selectPermsByPostIds(List<Long> postIds);

    /** 批量插入岗位-菜单关联 */
    int batchInsertPostMenu(List<SysPostMenu> list);

    /** 删除岗位-菜单关联 */
    int deletePostMenuByPostId(Long postId);
}