package com.ruoyi.vehicle.service.impl;

import com.ruoyi.common.core.utils.DateUtils;
import com.ruoyi.common.core.utils.StringUtils;
import com.ruoyi.common.core.utils.poi.ExcelUtil;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.security.utils.SecurityUtils;
import com.ruoyi.system.api.RemoteDictService;
import com.ruoyi.system.api.RemoteTranslateService;
import com.ruoyi.vehicle.domain.VehicleInfo;
import com.ruoyi.vehicle.mapper.VehicleInfoMapper;
import com.ruoyi.vehicle.service.IVehicleInfoService;
import com.ruoyi.vehicle.utils.JsonDictConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service("vehicleInfoService")
public class VehicleInfoServiceImpl implements IVehicleInfoService {

    private static final Logger log = LoggerFactory.getLogger(VehicleInfoServiceImpl.class);

    @Autowired
    private VehicleInfoMapper vehicleInfoMapper;

    @Autowired
    private RemoteDictService remoteDictService;

    @Autowired
    private RemoteTranslateService remoteTranslateService;

    @Autowired
    private JsonDictConverter jsonDictConverter;

    /**
     * 查询车辆信息
     *
     * @param vehicleId 车辆ID
     * @return 车辆信息
     */
    @Override
    public VehicleInfo selectVehicleInfoById(Long vehicleId) {
        VehicleInfo vehicle = vehicleInfoMapper.selectVehicleInfoById(vehicleId);
        if (vehicle != null && StringUtils.isNotBlank(vehicle.getJson())) {
            // 转换 JSON key 为 dict_label
            Map<String, Object> convertedMap = jsonDictConverter.convertJsonKeysToDictLabel(vehicle.getJson());
            vehicle.setJsonMap(convertedMap);
        }
        return vehicle;
    }

    /**
     * 查询车辆信息列表
     *
     * @param vehicleInfo 车辆信息
     * @return 车辆信息
     */
    @Override
    public List<VehicleInfo> selectVehicleInfoList(VehicleInfo vehicleInfo) {
        List<VehicleInfo> list = vehicleInfoMapper.selectVehicleInfoList(vehicleInfo);
        // 批量转换
        for (VehicleInfo vehicle : list) {
            if (StringUtils.isNotBlank(vehicle.getJson())) {
                Map<String, Object> convertedMap = jsonDictConverter.convertJsonKeysToDictLabel(vehicle.getJson());
                vehicle.setJsonMap(convertedMap);
            }
        }
        return list;
    }

    /**
     * 新增车辆信息
     *
     * @param vehicleInfo 车辆信息
     * @return 结果
     */
    @Override
    public int insertVehicleInfo(VehicleInfo vehicleInfo) {
        if (selectVehicleInfoByWvtaNo(vehicleInfo.getWvtaNo()) != null) {
            throw new RuntimeException("数据已存在");
        }
        vehicleInfo.setCreateTime(DateUtils.getNowDate());
        vehicleInfo.setCreateBy(SecurityUtils.getUsername());
        return vehicleInfoMapper.insertVehicleInfo(vehicleInfo);
    }

    private VehicleInfo selectVehicleInfoByWvtaNo(String vin) {
        return vehicleInfoMapper.selectVehicleInfoByWvtaNo(vin);
    }

    /**
     * 修改车辆信息
     *
     * @param vehicleInfo 车辆信息
     * @return 结果
     */
    @Override
    public int updateVehicleInfo(VehicleInfo vehicleInfo) {
        vehicleInfo.setUpdateTime(DateUtils.getNowDate());
        vehicleInfo.setUpdateBy(SecurityUtils.getUsername());
        return vehicleInfoMapper.updateVehicleInfo(vehicleInfo);
    }

    /**
     * 批量删除车辆信息
     *
     * @param vehicleIds 需要删除的车辆ID
     * @return 结果
     */
    @Override
    public AjaxResult deleteVehicleInfoByIds(Long[] vehicleIds) {
        try {
            int deleteRows = vehicleInfoMapper.deleteVehicleInfoByIds(vehicleIds);
            return AjaxResult.success(deleteRows);
        } catch (Exception e){
            return AjaxResult.error(e.getMessage());
        }
    }

    /**
     * 批量恢复车辆信息
     *
     * @param vehicleIds 需要恢复的车辆主键集合
     * @return 结果
     */
    @Override
    public AjaxResult restoreVehicleInfoByIds(Long[] vehicleIds) {
        int restoreRows = vehicleInfoMapper.restoreVehicleInfoByIds(vehicleIds);
        Map<String, Object> result = new HashMap<>();
        result.put("restoreRows", restoreRows);
        return AjaxResult.success(result);
    }

    /**
     * 永久删除车辆信息
     *
     * @param vehicleId 需要永久删除的车辆主键集合
     * @return 结果
     */
    @Override
    public int permanentlyDeleteVehicleInfoById(Long vehicleId) {
        return vehicleInfoMapper.permanentlyDeleteVehicleInfoById(vehicleId);
    }

    /**
     * 批量永久删除车辆信息
     *
     * @param vehicleIds 需要永久删除的车辆主键集合
     * @return 结果
     */
    @Override
    public int permanentlyDeleteVehicleInfoByIds(Long[] vehicleIds) {
        return vehicleInfoMapper.permanentlyDeleteVehicleInfoByIds(vehicleIds);
    }

    @Override
    public AjaxResult importExcel(MultipartFile file) throws Exception {
        ExcelUtil<VehicleInfo> excelUtil = new ExcelUtil<>(VehicleInfo.class);
        List<VehicleInfo> vehicleList = excelUtil.importExcel(file.getInputStream());

        if (vehicleList.isEmpty()) {
            return AjaxResult.error(remoteTranslateService.translate("vehicle.import.data.empty", null));
        }

        int successCount = 0;
        int failCount = 0;
        StringBuilder failMsg = new StringBuilder();

        for (VehicleInfo vehicle : vehicleList) {
            try {
                // 检查wvta是否已存在
                VehicleInfo query = new VehicleInfo();
                query.setWvtaNo(vehicle.getWvtaNo());
                List<VehicleInfo> existList = this.selectVehicleInfoList(query);

                if (!existList.isEmpty()) {
                    failCount++;
                    failMsg.append(remoteTranslateService.translate("vehicle.import.wvta.exists", vehicle.getWvtaNo()));
                    continue;
                }
                successCount++;
            } catch (Exception e) {
                failCount++;
                failMsg.append(StringUtils.format(remoteTranslateService.translate("vehicle.import.fail", null), vehicle.getWvtaNo(), e.getMessage()));
            }
        }

        Map<String, Object> resultMap = new HashMap<String, Object>();
        resultMap.put("total", vehicleList.size());
        resultMap.put("successCount", successCount);
        resultMap.put("failCount", failCount);
        resultMap.put("failMsg", failMsg.toString());
        resultMap.put("message", StringUtils.format(remoteTranslateService.translate("common.import.result", null), successCount, failCount));
        resultMap.put("data", vehicleList);
        return AjaxResult.success(resultMap);
    }
}
