package com.ruoyi.vehicle.service;

import com.ruoyi.common.core.model.ValidationReport;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.vehicle.domain.VehicleInfo;
import com.ruoyi.vehicle.domain.dto.VehicleDto;

import java.util.List;

public interface IVehicleInfoService {
    /**
     * 查询车辆信息
     *
     * @param vehicleId 车辆ID
     * @return 车辆信息
     */
    public VehicleInfo selectVehicleInfoById(Long vehicleId);

    /**
     * 查询车辆信息列表
     *
     * @param vehicleInfo 车辆信息
     * @return 车辆信息集合
     */
    public List<VehicleInfo> selectVehicleInfoList(VehicleInfo vehicleInfo);

    /**
     * 新增车辆信息
     *
     * @param vehicleInfo 车辆信息
     * @return 结果
     */
    public int insertVehicleInfo(VehicleInfo vehicleInfo);

    /**
     * 修改车辆信息
     *
     * @param vehicleInfo 车辆信息
     * @return 结果
     */
    public int updateVehicleInfo(VehicleInfo vehicleInfo);

    /**
     * 批量删除车辆信息
     *
     * @param vehicleIds 需要删除的车辆ID
     * @return 结果
     */
    public AjaxResult deleteVehicleInfoByIds(Long[] vehicleIds);

    /**
     * 批量恢复车辆信息
     *
     * @param vehicleIds 需要恢复的车辆主键集合
     * @return 结果
     */
    public AjaxResult restoreVehicleInfoByIds(Long[] vehicleIds);

    /**
     * 永久删除车辆信息
     *
     * @param vehicleId 需要删除的车辆主键
     * @return 结果
     */
    public int permanentlyDeleteVehicleInfoById(Long vehicleId);

    /**
     * 批量永久删除车辆信息
     *
     * @param vehicleIds 需要删除的车辆主键集合
     * @return 结果
     */
    public int permanentlyDeleteVehicleInfoByIds(Long[] vehicleIds);

    public int updateStatus(VehicleInfo vehicleInfo);

    public List<ValidationReport> validateVehicleInfo(List<Long> vehicleInfoId);

    void getVehicleInfoFromMes(VehicleDto vehicleDto);
}
