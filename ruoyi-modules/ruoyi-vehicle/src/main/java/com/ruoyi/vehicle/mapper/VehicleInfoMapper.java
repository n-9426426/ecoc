package com.ruoyi.vehicle.mapper;

import com.ruoyi.vehicle.domain.VehicleInfo;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 车辆信息 数据层
 *
 * @author ruoyi
 */
public interface VehicleInfoMapper {

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
    public int deleteVehicleInfoByIds(Long[] vehicleIds);

    /**
     * 批量恢复车辆信息
     *
     * @param vehicleIds 需要恢复的车辆主键集合
     * @return 结果
     */
    public int restoreVehicleInfoByIds(Long[] vehicleIds);

    /**
     * 批量删除车辆信息
     *
     * @param vehicleIds 需要删除的车辆主键集合
     * @return 结果
     */
    public int permanentlyDeleteVehicleInfoByIds(Long[] vehicleIds);

    /**
     * 物理删除超过一个月的逻辑删除数据
     *
     * @return 删除行数
     */
    public int permanentlyDeleteVehicleInfoById(Long vehicleId);

    VehicleInfo selectVehicleInfoByWvtaNo(String vin);

    int updateStatus( @Param("updateBy") String updateBy, @Param("vehicleId") Long vehicleId, @Param("status") Integer status);
}