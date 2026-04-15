package com.ruoyi.vehicle.mapper;

import com.ruoyi.vehicle.domain.VehicleTemplateMaterial;

import java.util.List;

public interface VehicleTemplateMaterialMapper {

    List<VehicleTemplateMaterial> selectByTemplateId(Long templateId);

    int batchInsert(List<VehicleTemplateMaterial> list);

    int deleteByTemplateId(Long templateId);

    int deleteByTemplateIds(Long[] templateIds);
}