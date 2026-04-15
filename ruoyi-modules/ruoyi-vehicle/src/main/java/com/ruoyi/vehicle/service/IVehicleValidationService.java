package com.ruoyi.vehicle.service;


import com.ruoyi.common.core.model.ValidationReport;

/**
 * 车辆信息校验 Service 接口
 */
public interface IVehicleValidationService {

    /**
     * 校验车辆信息 JSON 中的所有字段
     *
     * @param jsonStr           vehicle_info.json 字段内容
     * @param vehicleCategory   车型（如 M1, N2, L1e）
     * @param stageOfCompletion 完成阶段（如 C, A）
     * @return 校验报告，包含所有未通过的字段名和错误信息
     */
    ValidationReport validate(String jsonStr, String vehicleCategory, String stageOfCompletion);

    /**
     * 根据 vehicleId 从数据库读取 JSON 并校验
     *
     * @param vehicleId 车辆ID
     * @return 校验报告
     */
    ValidationReport validateByVehicleId(Long vehicleId);
}