package com.ruoyi.system.service.impl;

import com.ruoyi.common.core.constant.UserConstants;
import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.common.core.utils.StringUtils;
import com.ruoyi.system.api.RemoteTranslateService;
import com.ruoyi.system.domain.SysPost;
import com.ruoyi.system.domain.SysPostAuth;
import com.ruoyi.system.mapper.SysPostAuthMapper;
import com.ruoyi.system.mapper.SysPostMapper;
import com.ruoyi.system.mapper.SysUserPostMapper;
import com.ruoyi.system.service.ISysPostService;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 岗位信息 服务层处理
 * 
 * @author ruoyi
 */
@Service
public class SysPostServiceImpl implements ISysPostService
{
    @Autowired
    private SysPostMapper postMapper;

    @Autowired
    private SysUserPostMapper userPostMapper;

    @Autowired
    private SysPostAuthMapper postAuthMapper;

    @Autowired
    private RemoteTranslateService remoteTranslateService;

    /**
     * 查询岗位信息集合
     * 
     * @param post 岗位信息
     * @return 岗位信息集合
     */
    @Override
    public List<SysPost> selectPostList(SysPost post)
    {
        List<SysPost> sysPosts = postMapper.selectPostList(post);

        if (CollectionUtils.isEmpty(sysPosts)) {
            return sysPosts;
        }

        // 2. 提取所有 postId
        List<Long> postIds = sysPosts.stream()
                .map(SysPost::getPostId)
                .collect(Collectors.toList());

        // 3. 根据 postIds 批量查询权限关联数据
        List<SysPostAuth> postAuths = postAuthMapper.selectByPostIds(postIds);

        // 4. 将 postAuths 按 postId 分组
        Map<Long, List<SysPostAuth>> postAuthMap = postAuths.stream().collect(Collectors.groupingBy(SysPostAuth::getPostId));

        // 5. 遍历岗位列表，设置工厂编码和国家
        for (SysPost sysPost : sysPosts) {
            List<SysPostAuth> authList = postAuthMap.getOrDefault(sysPost.getPostId(), Collections.emptyList());
            sysPostSetAuth(authList, sysPost);
        }

        return sysPosts;
    }

    /**
     * 查询所有岗位
     * 
     * @return 岗位列表
     */
    @Override
    public List<SysPost> selectPostAll()
    {
        List<SysPost> sysPostList = postMapper.selectPostAll();
        List<SysPostAuth> postAuths = postAuthMapper.selectByPostIds(sysPostList.stream().map(SysPost::getPostId).collect(Collectors.toList()));
        for (SysPost sysPost : sysPostList) {
            Map<Long, List<SysPostAuth>> postAuthMap = postAuths.stream().collect(Collectors.groupingBy(SysPostAuth::getPostId));
            List<SysPostAuth> authList = postAuthMap.getOrDefault(sysPost.getPostId(), Collections.emptyList());
            sysPostSetAuth(authList, sysPost);
        }
        return sysPostList;
    }

    /**
     * 通过岗位ID查询岗位信息
     * 
     * @param postId 岗位ID
     * @return 角色对象信息
     */
    @Override
    public SysPost selectPostById(Long postId)
    {
        SysPost sysPost = postMapper.selectPostById(postId);
        List<SysPostAuth> sysPostAuths = postAuthMapper.selectByPostId(postId);
        if (sysPostAuths.isEmpty()) {
            return sysPost;
        }
        sysPostSetAuth(sysPostAuths, sysPost);
        return sysPost;
    }

    /**
     * 根据用户ID获取岗位选择框列表
     * 
     * @param userId 用户ID
     * @return 选中岗位ID列表
     */
    @Override
    public List<Long> selectPostListByUserId(Long userId)
    {
        return postMapper.selectPostListByUserId(userId);
    }

    /**
     * 校验岗位名称是否唯一
     * 
     * @param post 岗位信息
     * @return 结果
     */
    @Override
    public boolean checkPostNameUnique(SysPost post)
    {
        Long postId = StringUtils.isNull(post.getPostId()) ? -1L : post.getPostId();
        SysPost info = postMapper.checkPostNameUnique(post.getPostName());
        if (StringUtils.isNotNull(info) && info.getPostId().longValue() != postId.longValue())
        {
            return UserConstants.NOT_UNIQUE;
        }
        return UserConstants.UNIQUE;
    }

    /**
     * 校验岗位编码是否唯一
     * 
     * @param post 岗位信息
     * @return 结果
     */
    @Override
    public boolean checkPostCodeUnique(SysPost post)
    {
        Long postId = StringUtils.isNull(post.getPostId()) ? -1L : post.getPostId();
        SysPost info = postMapper.checkPostCodeUnique(post.getPostCode());
        if (StringUtils.isNotNull(info) && info.getPostId().longValue() != postId.longValue())
        {
            return UserConstants.NOT_UNIQUE;
        }
        return UserConstants.UNIQUE;
    }

    /**
     * 通过岗位ID查询岗位使用数量
     * 
     * @param postId 岗位ID
     * @return 结果
     */
    @Override
    public int countUserPostById(Long postId)
    {
        return userPostMapper.countUserPostById(postId);
    }

    /**
     * 删除岗位信息
     * 
     * @param postId 岗位ID
     * @return 结果
     */
    @Override
    public int deletePostById(Long postId)
    {
        int row = postMapper.deletePostById(postId);
        postAuthMapper.deleteByPostId(postId);
        return row;
    }

    /**
     * 批量删除岗位信息
     * 
     * @param postIds 需要删除的岗位ID
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deletePostByIds(Long[] postIds)
    {
        for (Long postId : postIds) {
            SysPost post = selectPostById(postId);
            if (countUserPostById(postId) > 0) {
                throw new ServiceException(String.format(remoteTranslateService.translate("assigned.cannot.delete", null), post.getPostName()));
            }
        }
        int row = postMapper.deletePostByIds(postIds);
        for (Long postId : postIds) {
            postAuthMapper.deleteByPostId(postId);
        }
        return row;
    }

    /**
     * 新增保存岗位信息
     * 
     * @param post 岗位信息
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int insertPost(SysPost post)
    {
        int row = postMapper.insertPost(post);
        if (!post.getFactoryCodes().isEmpty() || !post.getCountries().isEmpty() || !post.getVehicleModels().isEmpty()) {
            SysPostAuth postAuth = new SysPostAuth();
            postAuth.setPostId(post.getPostId());
            postAuth.setFactoryCode(StringUtils.join(post.getFactoryCodes(), ","));
            postAuth.setCountry(StringUtils.join(post.getCountries(), ","));
            postAuth.setVehicleModel(StringUtils.join(post.getVehicleModels(), ","));
            postAuthMapper.insertBatch(Collections.singletonList(postAuth));
        }
        return row;
    }

    /**
     * 修改保存岗位信息
     * 
     * @param post 岗位信息
     * @return 结果
     */
    @Override
    public int updatePost(SysPost post)
    {
        int row = postMapper.updatePost(post);
        insertPostAuth(post.getPostId(), post.getFactoryCodes(), post.getCountries(), post.getVehicleModels());
        return row;
    }

    private void insertPostAuth(Long postId, List<String> factoryCodes, List<String> countries, List<String> vehicleModels) {
        postAuthMapper.deleteByPostId(postId);
        if (!factoryCodes.isEmpty() || !countries.isEmpty() || !vehicleModels.isEmpty()) {
            SysPostAuth postAuth = new SysPostAuth();
            postAuth.setPostId(postId);
            postAuth.setFactoryCode(StringUtils.join(factoryCodes, ","));
            postAuth.setCountry(StringUtils.join(countries, ","));
            postAuth.setVehicleModel(StringUtils.join(vehicleModels, ","));
            postAuthMapper.insertBatch(Collections.singletonList(postAuth));
        }
    }

    private void sysPostSetAuth(List<SysPostAuth> postAuths, SysPost sysPost) {
        List<String> factoryCodes = postAuths.stream()
                .map(SysPostAuth::getFactoryCode)          // 获取逗号拼接字符串 "1001,1002"
                .filter(StringUtils::isNotBlank)            // 过滤空字符串
                .flatMap(s -> Arrays.stream(s.split(","))) // 按逗号拆分展平
                .map(String::trim)                          // 去除空格
                .filter(StringUtils::isNotBlank)            // 过滤拆分后空值
                .distinct()                                 // 去重
                .collect(Collectors.toList());
        sysPost.setFactoryCodes(factoryCodes);

        // 设置国家列表：将逗号拼接的字符串转为 List<Long>
        List<String> countries = postAuths.stream()
                .map(SysPostAuth::getCountry)              // 获取逗号拼接字符串
                .filter(StringUtils::isNotBlank)            // 过滤空字符串
                .flatMap(s -> Arrays.stream(s.split(","))) // 按逗号拆分展平
                .map(String::trim)                          // 去除空格
                .filter(StringUtils::isNotBlank)            // 过滤拆分后空值
                .distinct()                                 // 去重
                .collect(Collectors.toList());
        sysPost.setCountries(countries);

        // 设置车型列表：将逗号拼接的字符串转为 List<Long>
        List<String> vehicleModels = postAuths.stream()
                .map(SysPostAuth::getVehicleModel)              // 获取逗号拼接字符串
                .filter(StringUtils::isNotBlank)            // 过滤空字符串
                .flatMap(s -> Arrays.stream(s.split(","))) // 按逗号拆分展平
                .map(String::trim)                          // 去除空格
                .filter(StringUtils::isNotBlank)            // 过滤拆分后空值
                .distinct()                                 // 去重
                .collect(Collectors.toList());
        sysPost.setVehicleModels(vehicleModels);
    }
}
