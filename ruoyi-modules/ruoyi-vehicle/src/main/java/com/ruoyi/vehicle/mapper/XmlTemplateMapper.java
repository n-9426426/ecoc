package com.ruoyi.vehicle.mapper;

import com.ruoyi.vehicle.domain.XmlTemplate;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface XmlTemplateMapper {
    // 查询列表（含逻辑删除过滤）
    List<XmlTemplate> selectTemplateList(XmlTemplate query);

    // 根据ID查询（含 deleted=0）
    XmlTemplate selectById(@Param("templateId") Long templateId);

    // 插入
    int insert(XmlTemplate template);

    // 更新
    int updateById(XmlTemplate template);

    // 逻辑删除（主表）
    int deleteByIds(@Param("templateIds") List<Long> templateIds);

    List<XmlTemplate> selectTemplateAll();
}