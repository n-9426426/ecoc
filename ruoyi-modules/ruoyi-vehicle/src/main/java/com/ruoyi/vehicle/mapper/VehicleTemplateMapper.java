package com.ruoyi.vehicle.mapper;

import com.ruoyi.vehicle.domain.VehicleTemplate;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface VehicleTemplateMapper {

    List<VehicleTemplate> selectVehicleTemplateList(VehicleTemplate template);

    VehicleTemplate selectVehicleTemplateById(Long templateId);

    int insertVehicleTemplate(VehicleTemplate template);

    int updateVehicleTemplate(VehicleTemplate template);

    int deleteVehicleTemplateByIds(Long[] templateIds);

    int updateStatus(@Param("templateId") Long templateId, @Param("status") String status);

    int updateValidateResult(@Param("templateId") Long templateId,
                             @Param("validateResult") String validateResult,
                             @Param("validateMsg") String validateMsg);

    int batchUpdateValidateResult(List<VehicleTemplate> list);

    List<VehicleTemplate> selectVehicleTemplateOption();
}