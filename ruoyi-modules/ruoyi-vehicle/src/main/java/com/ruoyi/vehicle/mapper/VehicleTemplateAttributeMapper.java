package com.ruoyi.vehicle.mapper;

import com.ruoyi.vehicle.domain.VehicleTemplateAttribute;
import org.apache.ibatis.annotations.Param;
import java.util.List;

public interface VehicleTemplateAttributeMapper {
    // 查询模板下所有未删除属性（含逻辑删除过滤）
    List<VehicleTemplateAttribute> selectByTemplateId(@Param("templateId") Long templateId);

    // 批量查询（用于列表）
    List<VehicleTemplateAttribute> selectByTemplateIds(@Param("templateIds") List<Long> templateIds);

    // 物理删除：将指定模板下所有属性标记为 deleted=1
    int deleteByTemplateIds(@Param("templateIds") List<Long> templateIds);

    // 插入属性
    int insert(VehicleTemplateAttribute attribute);
}