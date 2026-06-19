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
     */
    VehicleInfo selectVehicleInfoById(Long vehicleId);

    /**
     * 查询车辆信息列表
     */
    List<VehicleInfo> selectVehicleInfoList(VehicleInfo vehicleInfo);

    /**
     * 新增车辆信息
     */
    int insertVehicleInfo(VehicleInfo vehicleInfo);

    /**
     * 修改车辆信息
     */
    int updateVehicleInfo(VehicleInfo vehicleInfo);

    /**
     * 批量逻辑删除车辆信息
     */
    int deleteVehicleInfoByIds(Long[] vehicleIds);

    /**
     * 批量恢复车辆信息
     */
    int restoreVehicleInfoByIds(Long[] vehicleIds);

    /**
     * 批量物理删除车辆信息
     */
    int permanentlyDeleteVehicleInfoByIds(Long[] vehicleIds);

    /**
     * 物理删除单条车辆信息
     */
    int permanentlyDeleteVehicleInfoById(Long vehicleId);

    VehicleInfo selectVehicleInfoByWvtaNo(@Param("wvtaNo") String wvtaNo);

    int updateStatus(@Param("updateBy") String updateBy,
                     @Param("vehicleId") Long vehicleId,
                     @Param("status") Integer status);

    VehicleInfo selectVehicleInfoByVin(@Param("vin") String vin);

    VehicleInfo selectByVinAndDeleted(@Param("vin") String vin, @Param("deleted") int deleted);

    List<VehicleInfo> selectVehicleInfoByIds(Long[] vehicleIds);

    List<VehicleInfo> checkOldTemplate();

    int updateVehicleInfoOldTemplate(@Param("vehicleIds") List<Long> vehicleIds,
                                     @Param("status") Integer status);

    int updateVehicleInfoGenerateTimeout(@Param("vehicleIds") List<Long> vehicleIds,
                                         @Param("status") Integer status);

    List<VehicleInfo> selectVehicleInfoByVinManufactureDate(String vin);

    int updateTempVersionByVin(@Param("vin") String vin, @Param("tempVersion") String tempVersion);

    int updateVehicleTemplateIdByTempVersion(@Param("breakpointId") Long breakpointId,
                                             @Param("list") List<String> vinList);


    // ===================================================================
    //  首台车管理
    // ===================================================================

    /**
     * 查询该物料号下制造时间最早的 vehicle_id（已加 LIMIT 1，不会返回多条）
     */
    Long findEarliestIdByMaterialNo(@Param("materialNo") String materialNo);

    /**
     * 查询该模版下制造时间最早的 vehicle_id（已加 LIMIT 1，不会返回多条）
     */
    Long findEarliestIdByTemplateId(@Param("templateId") String templateId);

    /**
     * 该物料号下是否存在已确认的记录（generate_affirm = 1）
     */
    boolean existsConfirmedMaterial(@Param("materialNo") String materialNo);

    /**
     * 该模版下是否存在已确认的记录（upload_affirm = 1）
     */
    boolean existsConfirmedTemplate(@Param("templateId") String templateId);

    /**
     * 清除该物料号下所有车辆的物料号首台标识（first_material_flag → 0）
     */
    void clearMaterialFlagByMaterialNo(@Param("materialNo") String materialNo);

    /**
     * 清除该模版下所有车辆的模版首台标识（first_template_flag → 0）
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
     * 确认物料号首台（toggle generate_affirm，写入确认人和确认原因）
     *
     * @param vehicleId           车辆ID
     * @param confirmedBy         确认人
     */
    void confirmMaterialFlag(@Param("vehicleId") Long vehicleId,
                             @Param("confirmedBy") String confirmedBy);

    /**
     * 确认模版首台（toggle upload_affirm，写入确认人和确认原因）
     *
     * @param vehicleId         车辆ID
     * @param confirmedBy       确认人
     */
    void confirmTemplateFlag(@Param("vehicleId") Long vehicleId,
                             @Param("confirmedBy") String confirmedBy);

    /**
     * 重置该物料号下所有车辆的物料号确认状态
     * <p>将 generate_affirm=0，清空 material_confirmed_by / material_confirmed_time
     * <p>暂时未使用（预留，用于未来可能的业务撤销场景）
     */
    void resetMaterialConfirm(@Param("materialNo") String materialNo);

    /**
     * 重置该模版下所有车辆的模版确认状态
     * <p>将 upload_affirm=0，清空 template_confirmed_by / template_confirmed_time
     * <p>模版内容改动后调用，让人重新确认
     */
    void resetTemplateConfirm(@Param("templateId") String templateId);

    /**
     * 查询首台车待确认列表
     * <p>dimension = "material" 时查物料号维度（first_material_flag=1 AND generate_affirm=0）
     * <p>dimension = "template" 时查模版维度（first_template_flag=1 AND upload_affirm=0）
     */
    List<VehicleInfo> listFirstVehicleUnconfirmed(@Param("vehicleInfo") VehicleInfo vehicleInfo,
                                                  @Param("dimension") String dimension);

    /**
     * 通过模版组 uuid 查询该 uuid 下所有关联的 vehicle_template_id 列表
     */
    List<String> findTemplateIdsByUuid(@Param("uuid") String uuid);

    List<Map<String, String>> selectOldVersionAndNewVersion(@Param("list") List<String> materialNoList);

    /**
     * 批量更新生成确认通知发送状态
     *
     * @param vehicleIds 车辆ID列表
     * @param status     发送状态 0-未发送 1-已发送
     */
    void updateGenerateAffirmNotice(@Param("vehicleIds") List<Long> vehicleIds, @Param("status") Integer status);

    /**
     * 批量更新上传确认通知发送状态
     *
     * @param vehicleIds 车辆ID列表
     * @param status     发送状态 0-未发送 1-已发送
     */
    void updateUploadAffirmNotice(@Param("vehicleIds") List<Long> vehicleIds, @Param("status") Integer status);

    /**
     * 查询需要发送生成确认通知的记录
     * 条件：generate_affirm=0 且 generate_affirm_notice=0 且已到提醒时间
     *
     * @return 车辆信息列表
     */
    List<VehicleInfo> listPendingGenerateAffirmNotice();

    /**
     * 查询需要发送上传确认通知的记录
     * 条件：upload_affirm=0 且 upload_affirm_notice=0 且已到提醒时间
     *
     * @return 车辆信息列表
     */
    List<VehicleInfo> listPendingUploadAffirmNotice();

    /**
     * 物料号维度待确认首台车，每个 material_no 取制造日期最早一条
     */
    List<VehicleInfo> listFirstMaterialUnconfirmedEarliest();

    /**
     * 模版维度待确认首台车，每个 vehicle_template_id 取制造日期最早一条
     */
    List<VehicleInfo> listFirstTemplateUnconfirmedEarliest();

    List<VehicleInfo> checkVehicleInfoTimeoutGenerate(@Param("noticeStatus") Integer noticeStatus);

    List<String> findMaterialNosByTemplateId(@Param("templateId") String templateId);

    void updateGenerateAffirmByMaterialNo(@Param("materialNo") String materialNo,
                                           @Param("generateAffirm") int generateAffirm);

    void updateUploadAffirmByTemplateId(@Param("templateId") String templateId,
                                        @Param("uploadAffirm") int uploadAffirm);

    /**
     * 查询指定 material_no 下、vehicle_template_id 为空的车辆信息
     * 用于回溯补关联
     */
    List<VehicleInfo> selectByMaterialNoWithoutTemplate(@Param("materialNo") String materialNo);

    /**
     * 回溯补关联：只更新模版相关字段，不影响其他业务字段
     */
    void retroactiveLinkTemplate(
            @Param("vehicleId")           Long   vehicleId,
            @Param("templateId")          String templateId,
            @Param("tvv")                 String tvv,
            @Param("wvtaNo")              String wvtaNo,
            @Param("json")                String json,
            @Param("brand")               String brand,
            @Param("weight")              String weight,
            @Param("saleName")            String saleName,
            @Param("tire")                String tire,
            @Param("tireResistanceGrade") String tireResistanceGrade,
            @Param("vehicleModel")        String vehicleModel,
            @Param("projectName")         String projectName
    );

    /**
     * 按 VIN 更新车辆上传状态
     *
     * @param vin          车辆VIN
     * @param uploadStatus 新状态（0=未生成 1=已生成 2=已上传 3=未上传 4=上传失败）
     */
    void updateUploadStatusByVin(@Param("vin") String vin,
                                 @Param("uploadStatus") int uploadStatus);
}
