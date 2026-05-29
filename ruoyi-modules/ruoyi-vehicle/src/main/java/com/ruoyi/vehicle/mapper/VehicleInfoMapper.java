package com.ruoyi.vehicle.mapper;

import com.ruoyi.vehicle.domain.VehicleInfo;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

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

    VehicleInfo selectVehicleInfoByWvtaNo(@Param("wvtaNo") String wvtaNo);

    int updateStatus( @Param("updateBy") String updateBy, @Param("vehicleId") Long vehicleId, @Param("status") Integer status);

    VehicleInfo selectVehicleInfoByVin(@Param("vin") String vin);

    VehicleInfo selectByVinAndDeleted(@Param("vin") String vin, @Param("deleted") int deleted);

    List<VehicleInfo> selectVehicleInfoByIds(Long[] vehicleIds);

    List<VehicleInfo> checkOldTemplate();

    int updateVehicleInfoOldTemplate(@Param("vehicleIds") List<Long> vehicleIds, @Param("status") Integer status);

    List<VehicleInfo> selectVehicleInfoByVinManufactureDate(String vin);

    int updateTempVersionByVin(@Param("vin") String vin, @Param("tempVersion") String tempVersion);

    int updateVehicleTemplateIdByTempVersion(@Param("breakpointId") Long breakpointId, @Param("list") List<String> vinList);


    // ===================================================================
    //  首台车管理
    // ===================================================================

    /**
     * 查询该物料号下制造时间最早的 vehicle_id
     */
    Long findEarliestIdByMaterialNo(@Param("materialNo") String materialNo);

    /**
     * 查询该模版下制造时间最早的 vehicle_id
     */
    Long findEarliestIdByTemplateId(@Param("templateId") String templateId);

    /**
     * 查询该物料号下当前 first_material_flag=1 且未确认的 vehicle_id
     * 正常情况下最多只有一条
     */
    Long findPendingMaterialFlagId(@Param("materialNo") String materialNo);

    /**
     * 查询该模版下当前 first_template_flag=1 且未确认的 vehicle_id
     */
    Long findPendingTemplateFlagId(@Param("templateId") String templateId);

    /**
     * 该物料号下是否存在已确认的记录（material_confirmed_time IS NOT NULL）
     */
    boolean existsConfirmedMaterial(@Param("materialNo") String materialNo);

    /**
     * 该模版下是否存在已确认的记录（template_confirmed_time IS NOT NULL）
     */
    boolean existsConfirmedTemplate(@Param("templateId") String templateId);

    /**
     * 清除该物料号下所有车辆的物料号首台标识
     */
    void clearMaterialFlagByMaterialNo(@Param("materialNo") String materialNo);

    /**
     * 清除该模版下所有车辆的模版首台标识
     */
    void clearTemplateFlagByTemplateId(@Param("templateId") String templateId);

    /**
     * 设置指定车辆的物料号首台标识
     *
     * @param vehicleId 车辆ID
     * @param flag      0-取消 1-打标
     */
    void markMaterialFlag(@Param("vehicleId") Long vehicleId, @Param("flag") int flag);

    /**
     * 设置指定车辆的模版首台标识
     *
     * @param vehicleId 车辆ID
     * @param flag      0-取消 1-打标
     */
    void markTemplateFlag(@Param("vehicleId") Long vehicleId, @Param("flag") int flag);

    /**
     * 查询物料号维度未确认列表（Tab1）
     */
    List<VehicleInfo> listFirstMaterialUnconfirmed();

    /**
     * 查询模版维度未确认列表（Tab2）
     */
    List<VehicleInfo> listFirstTemplateUnconfirmed();

    /**
     * 确认物料号首台：清除标识并记录确认人、确认时间
     */
    void confirmMaterialFlag(@Param("vehicleId") Long vehicleId, @Param("confirmedBy") String confirmedBy);

    /**
     * 确认模版首台：清除标识并记录确认人、确认时间
     */
    void confirmTemplateFlag(@Param("vehicleId") Long vehicleId, @Param("confirmedBy") String confirmedBy);

    List<String> findTemplateIdsByUuid(@Param("uuid") String uuid);

    List<Map<String, String>> selectOldVersionAndNewVersion(@Param("list") List<String> materialNoList);
}
