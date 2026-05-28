package com.ruoyi.vehicle.service;

import com.ruoyi.vehicle.domain.VehicleInfo;

import java.util.List;

/**
 * 首台车确认 Service 接口
 */
public interface IFirstVehicleCheckService {

    /**
     * 新增车辆完成后调用（单条/批量/接口推送均走此方法）
     * 传入本次新增涉及的所有车辆，内部提取去重后的物料号和模版ID分别重新计算打标
     *
     * @param vehicleList 本次新增的车辆列表（vehicle_id 已回填）
     */
    void handleAfterInsert(List<VehicleInfo> vehicleList);

    /**
     * 车辆逻辑删除后调用
     * 传入本次删除的所有车辆，内部提取涉及的物料号和模版ID重新计算打标
     *
     * @param deletedList 被删除的车辆列表（删除前查出）
     */
    void handleAfterDelete(List<VehicleInfo> deletedList);

    /**
     * 模版修改后调用
     * 清除该模版所有标识，重新找最早的车辆打标
     *
     * @param templateId 被修改的模版 ID
     */
    void handleAfterTemplateModified(String uuid);

    /**
     * 确认物料号首台标识
     *
     * @param vehicleId   车辆 ID
     * @param confirmedBy 确认人
     */
    void confirmMaterial(Long vehicleId, String confirmedBy);

    /**
     * 确认模版首台标识
     *
     * @param vehicleId   车辆 ID
     * @param confirmedBy 确认人
     */
    void confirmTemplate(Long vehicleId, String confirmedBy);

    /**
     * 查询物料号维度未确认列表（Tab1：整车物料号）
     */
    List<VehicleInfo> listFirstMaterialUnconfirmed();

    /**
     * 查询模版维度未确认列表（Tab2：TVV/车辆模版）
     */
    List<VehicleInfo> listFirstTemplateUnconfirmed();
}
