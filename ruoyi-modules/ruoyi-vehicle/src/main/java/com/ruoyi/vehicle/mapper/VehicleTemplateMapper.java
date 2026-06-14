package com.ruoyi.vehicle.mapper;

import com.ruoyi.vehicle.domain.VehicleTemplate;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

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

    VehicleTemplate selectVehicleByUuid(@Param("uuid") String uuid);

    int updateAllTemplateNotIsLast(@Param("uuid") String uuid);

    List<VehicleTemplate> selectExpiringTemplates(@Param("needExpiredNotice") Integer needExpiredNotice);

    List<VehicleTemplate> selectEffectingTemplates();

    List<VehicleTemplate> selectVehicleTemplateIdByCondition(
            @Param("materialNo") String materialNo,
            @Param("brand") String brand,
            @Param("weight") String weight,
            @Param("saleName") String saleName,
            @Param("tire") String tire,
            @Param("tvv") String tvv
    );

    int updateStatusByOverdueDate();

    int updateStatusByEffectiveDate();

    List<VehicleTemplate> selectVehicleTemplateOverdueButNoNextVersion();

    int updateVehicleTemplateNoNextVersion(@Param("vehicleTemplateIds") List<Long> vehicleTemplateIds, @Param("status") Integer status);

    int updateVehicleTemplateExpired(@Param("vehicleTemplateIds") List<Long> vehicleTemplateIds, @Param("status") Integer status);

    int updateVehicleTemplateId(@Param("vin") String vin, @Param("templateId") Long templateId);

    /**
     * 根据模板ID列表，查询每个模板下关联的 vehicle_info 数量
     *
     * @param templateIds 模板ID数组
     * @return 每个模板ID及其对应的车辆数量
     */
    List<Map<String, Object>> selectVehicleCountByTemplateIds(@Param("templateIds") Long[] templateIds);

    void resetAffirmByUuid(@Param("uuid") String uuid);

    void updateGenerateAffirmByTemplateId(@Param("templateId") Long templateId, @Param("generateAffirm") int generateAffirm);

    void updateUploadAffirmByTemplateId(@Param("templateId") Long templateId, @Param("uploadAffirm") int uploadAffirm);

    int selectCountByCocTemplateNo(@Param("cocTemplateNo") String cocTemplateNo,
                                   @Param("excludeUuid") String excludeUuid);

}
