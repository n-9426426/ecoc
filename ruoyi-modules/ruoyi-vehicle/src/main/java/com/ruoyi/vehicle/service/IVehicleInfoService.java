package com.ruoyi.vehicle.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.ruoyi.common.core.model.ValidationReport;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.system.api.model.LoginUser;
import com.ruoyi.vehicle.domain.VehicleInfo;
import com.ruoyi.vehicle.domain.dto.VehicleDto;
import com.ruoyi.vehicle.domain.vo.VehicleJsonKeyVo;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.util.Date;
import java.util.List;
import java.util.Map;

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
    public int updateVehicleInfo(VehicleInfo vehicleInfo, boolean needValid);

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

    Map<String, Object> getVehicleInfoFromMes(VehicleDto.Vehicle vehicle, Date now, LoginUser loginUser) throws JsonProcessingException;

    VehicleInfo selectVehicleInfoByVin(String vin);

    String submitImportTask(MultipartFile file);

    Flux<ServerSentEvent<String>> getImportFlux(String taskId);

    /**
     * 批量修改关联模版（只允许同一整车物料号下的车辆）
     *
     * @param vehicleIds 车辆ID列表
     * @param templateId 目标模版ID
     * @return 成功更新行数
     */
    int batchUpdateVehicleTemplate(List<Long> vehicleIds);

    /**
     * 根据 vehicleIds 汇总所有车辆 JSON 键的并集，并关联字典 label 信息
     *
     * @param vehicleIds 车辆ID列表
     * @return JSON 键关联字典信息列表（已去重，保持插入顺序）
     */
    List<VehicleJsonKeyVo> listJsonKeysByVehicleIds(List<Long> vehicleIds);

    /**
     * 批量替换车辆 JSON 中指定键的值
     *
     * @param vehicleIds  车辆ID列表
     * @param fieldValues 需要替换的字段 Map（key=JSON键, value=新值）
     * @return 成功更新的车辆数量
     */
    int batchUpdateVehicleJsonFields(List<Long> vehicleIds, Map<String, String> fieldValues);

    Map<String, List<Map<String, String>>> getTemplateVersion(List<Long> vehicleIds);

    List<VehicleInfo> listFirstVehicleUnconfirmed(VehicleInfo vehicleInfo, String dimension);

    List<VehicleInfo> selectVehicleInfoByIds(Long[] vehicleIds);

    /**
     * 查询所有待确认首台车（物料号维度 + 模版维度合并，无分页）
     */
    List<VehicleInfo> listAllFirstVehicleUnconfirmed();
}
