package com.ruoyi.vehicle.mapper;

import com.ruoyi.vehicle.domain.XmlTemplateAttribute;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface XmlTemplateAttributeMapper {
    // 查询模板下所有未删除属性（含逻辑删除过滤）
    List<XmlTemplateAttribute> selectByTemplateId(@Param("templateId") Long templateId);

    // 批量查询（用于列表）
    List<XmlTemplateAttribute> selectByTemplateIds(@Param("templateIds") List<Long> templateIds);

    // 物理删除：将指定模板下所有属性标记为 deleted=1
    int deleteByTemplateIds(@Param("templateIds") List<Long> templateIds);

    // 插入属性
    int insert(XmlTemplateAttribute attribute);
}