package com.ruoyi.vehicle.service;

import com.ruoyi.vehicle.domain.VehicleInfo;

import java.util.List;

/**
 * 首台车确认 Service 接口
 *
 * <p>触发时机：
 * <ul>
 *   <li>手动新增 / Excel 导入 / 接口推送（新记录）→ {@link #handleAfterInsert}</li>
 *   <li>接口推送（已有 VIN 更新）          → {@link #handleAfterUpdate}</li>
 *   <li>删除车辆（逻辑删除）               → {@link #handleAfterDelete}</li>
 *   <li>模版内容发生改动                   → {@link #handleAfterTemplateModified}</li>
 * </ul>
 */
public interface IFirstVehicleCheckService {

    /**
     * 新增车辆后触发（手动新增 / Excel 导入 / 接口推送新 VIN）
     *
     * @param vehicleList 本次新增的车辆列表
     */
    void handleAfterInsert(List<VehicleInfo> vehicleList);

    /**
     * 更新车辆后触发（接口推送已有 VIN 时走更新）
     * <p>方法内部会根据 vehicleId 自行查询更新前的旧数据，
     * 对比物料号和模版 ID 是否发生变化，有变化则触发相关维度重算。
     *
     * @param newInfo 更新后的车辆信息（必须含 vehicleId）
     */
    void handleAfterUpdate(VehicleInfo newInfo);

    /**
     * 删除车辆后触发（逻辑删除）
     * <p>只对"未确认"的车辆涉及的物料号/模版重算；已确认的不受影响。
     *
     * @param deletedList 本次被删除的车辆列表（删除前的快照，含 flag 和 affirm 字段）
     */
    void handleAfterDelete(List<VehicleInfo> deletedList);

    /**
     * 模版内容改动后触发
     * <p>会重置该 uuid 下所有关联车辆的模版确认状态，并重新打标。
     *
     * @param uuid 模版组 uuid
     */
    void handleAfterTemplateModified(String uuid);

    /**
     * 确认物料号首台（确认可生成）
     *
     * @param vehicleId           车辆ID
     * @param confirmedBy         确认人
     * @param generateAffirmCause 生成确认原因
     */
    void confirmMaterial(Long vehicleId, String confirmedBy, String generateAffirmCause);

    /**
     * 确认模版首台（确认可上传）
     *
     * @param vehicleId         车辆ID
     * @param confirmedBy       确认人
     * @param uploadAffirmCause 上传确认原因
     */
    void confirmTemplate(Long vehicleId, String confirmedBy, String uploadAffirmCause);
}
