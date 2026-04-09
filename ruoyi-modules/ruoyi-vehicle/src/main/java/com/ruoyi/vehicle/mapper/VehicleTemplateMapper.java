package com.ruoyi.vehicle.mapper;

import com.ruoyi.vehicle.domain.VehicleTemplate;
import org.apache.ibatis.annotations.Param;
import java.util.List;

public interface VehicleTemplateMapper {
    // 查询列表（含逻辑删除过滤）
    List<VehicleTemplate> selectTemplateList(VehicleTemplate query);

    // 根据ID查询（含 deleted=0）
    VehicleTemplate selectById(@Param("templateId") Long templateId);

    // 插入
    int insert(VehicleTemplate template);

    // 更新
    int updateById(VehicleTemplate template);

    // 逻辑删除（主表）
    int deleteByIds(@Param("templateIds") List<Long> templateIds);
}