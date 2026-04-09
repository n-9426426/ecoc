package com.ruoyi.vehicle.service;

import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.vehicle.domain.VehicleInfo;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

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

    /**
     * excel数据导入
     *
     * @param file EXCEL文件
     * @return 导入结果
     */
    public AjaxResult importExcel(MultipartFile file) throws Exception;

    /**
     * 调用Python对文件进行OCR识别
     *
     * @param file PDF文件
     * @return 识别结果
     */
    public Flux<ServerSentEvent<String>> importPdf(MultipartFile file);

    /**
     * OCR识别进度回调
     */
    public void sendProgress(String taskId, Map<String, Object> data);

    /**
     * OCR识别完成回调
     */
    public void sendComplete(String taskId, Map<String, Object> data);

    /**
     * OCR识别错误回调
     */
    public void sendError(String taskId, Map<String, Object> data);
}
