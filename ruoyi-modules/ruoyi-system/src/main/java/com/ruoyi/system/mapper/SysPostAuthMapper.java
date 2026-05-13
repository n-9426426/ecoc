package com.ruoyi.system.mapper;

import com.ruoyi.system.domain.SysPostAuth;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface SysPostAuthMapper {

    /**
     * 根据岗位ID查询权限列表
     */
    List<SysPostAuth> selectByPostId(Long postId);

    /**
     * 根据多个岗位ID批量查询
     */
    List<SysPostAuth> selectByPostIds(@Param("postIds") List<Long> postIds);

    /**
     * 批量插入
     */
    int insertBatch(@Param("list") List<SysPostAuth> list);

    /**
     * 根据岗位ID删除
     */
    int deleteByPostId(Long postId);
}