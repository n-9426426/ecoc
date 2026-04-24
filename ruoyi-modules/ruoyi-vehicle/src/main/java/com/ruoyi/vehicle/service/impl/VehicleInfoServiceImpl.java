package com.ruoyi.vehicle.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.core.model.ValidationReport;
import com.ruoyi.common.core.utils.DateUtils;
import com.ruoyi.common.core.utils.StringUtils;
import com.ruoyi.common.core.utils.bean.BeanUtils;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.security.utils.SecurityUtils;
import com.ruoyi.system.api.RemoteDictService;
import com.ruoyi.system.api.RemoteTranslateService;
import com.ruoyi.system.api.domain.SysDictData;
import com.ruoyi.vehicle.domain.VehicleInfo;
import com.ruoyi.vehicle.domain.VehicleLifecycle;
import com.ruoyi.vehicle.domain.dto.VehicleDto;
import com.ruoyi.vehicle.mapper.VehicleInfoMapper;
import com.ruoyi.vehicle.mapper.VehicleLifecycleMapper;
import com.ruoyi.vehicle.mapper.VehicleTemplateMaterialMapper;
import com.ruoyi.vehicle.service.IVehicleInfoService;
import com.ruoyi.vehicle.service.IVehicleValidationService;
import com.ruoyi.vehicle.utils.JsonDictConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service("vehicleInfoService")
public class VehicleInfoServiceImpl implements IVehicleInfoService {

    private static final Logger log = LoggerFactory.getLogger(VehicleInfoServiceImpl.class);

    @Autowired
    private VehicleInfoMapper vehicleInfoMapper;

    @Autowired
    private VehicleTemplateMaterialMapper vehicleTemplateMaterialMapper;

    @Autowired
    private RemoteDictService remoteDictService;

    @Autowired
    private RemoteTranslateService remoteTranslateService;

    @Autowired
    private IVehicleValidationService vehicleValidationService;

    @Autowired
    private JsonDictConverter jsonDictConverter;

    @Autowired
    private VehicleLifecycleMapper vehicleLifecycleMapper;

    private static final ObjectMapper objectMapper = new ObjectMapper();

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
    @Transactional(rollbackFor = Exception.class)
    public int insertVehicleInfo(VehicleInfo vehicleInfo) {
        if (selectVehicleInfoByWvtaNo(vehicleInfo.getWvtaNo()) != null) {
            throw new RuntimeException("数据已存在");
        }
        Long vehicleTemplateId = vehicleTemplateMaterialMapper.selectVehicleTemplateIdByMaterialNo(vehicleInfo.getMaterialNo());
        if (vehicleTemplateId == null) {
            throw new RuntimeException("该物料号对应的车辆模板不存在");
        }
        vehicleInfo.setVehicleTemplateId(String.valueOf(vehicleTemplateId));
        vehicleInfo.setUploadStatus(0);
        vehicleInfo.setValidationResult(0);
        vehicleInfo.setCreateTime(DateUtils.getNowDate());
        vehicleInfo.setCreateBy(SecurityUtils.getUsername() == null ? "MES To System" : SecurityUtils.getUsername());
        int row = vehicleInfoMapper.insertVehicleInfo(vehicleInfo);
        VehicleLifecycle vehicleLifecycle = new VehicleLifecycle();
        vehicleLifecycle.setTime(new Date());
        vehicleLifecycle.setVin(vehicleInfo.getVin());
        vehicleLifecycle.setOperate("0");
        vehicleLifecycle.setResult(0);
        vehicleLifecycleMapper.insert(vehicleLifecycle);
        return row;
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
    public int updateStatus(VehicleInfo vehicleInfo) {
        String updateBy = SecurityUtils.getUsername();
        return vehicleInfoMapper.updateStatus(updateBy, vehicleInfo.getVehicleId(), vehicleInfo.getStatus());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<ValidationReport> validateVehicleInfo(List<Long> vehicleInfoIds) {
        List<ValidationReport> validationReports = new LinkedList<>();
        for (Long vehicleInfoId : vehicleInfoIds) {
            VehicleInfo vehicleInfo = vehicleInfoMapper.selectVehicleInfoById(vehicleInfoId);
            List<SysDictData> sysDictData = remoteDictService.getDictDataByType("vehicle_attribute").getData();
            sysDictData = sysDictData.stream()
                    .filter(data -> vehicleInfo.getVehicleModel().equals(data.getDictTypeAffiliation()))
                    .collect(Collectors.toList());
            SysDictData vehicleModel = remoteDictService.getDataByDictCode(vehicleInfo.getVehicleModel()).getData();
            ValidationReport validationReport = vehicleValidationService.validate(vehicleInfo.getJson(), vehicleModel.getDictValue(), "C");
            if (validationReport.isAllValid()) {
                vehicleInfo.setValidationResult(1);
            } else {
                vehicleInfo.setValidationResult(2);
                try {
                    vehicleInfo.setValidationReportJson(objectMapper.writeValueAsString(validationReport));
                } catch (JsonProcessingException e) {
                    log.error("对象转 JSON 失败", e);
                    throw new RuntimeException("校验报告保存失败");
                }
            }
            validationReports.add(validationReport);
            vehicleInfoMapper.updateVehicleInfo(vehicleInfo);
            VehicleLifecycle vehicleLifecycle = new VehicleLifecycle();
            vehicleLifecycle.setTime(new Date());
            vehicleLifecycle.setVin(vehicleInfo.getVin());
            vehicleLifecycle.setOperate("1");
            vehicleLifecycle.setResult(validationReport.isAllValid() ? 0 : 1);
            vehicleLifecycleMapper.insert(vehicleLifecycle);
        }

        return validationReports;
    }

    @Override
    public void getVehicleInfoFromMes(VehicleDto vehicleDto) {
        VehicleInfo vehicleInfo = new VehicleInfo();
        BeanUtils.copyProperties(vehicleDto, vehicleInfo);
        List<SysDictData> sysDictData = remoteDictService.getDictDataByType("vehicle_model").getData();
        for (SysDictData dictData : sysDictData) {
            if (dictData.getDictLabel().equals(vehicleDto.getVehicleModel())) {
                vehicleInfo.setVehicleModel(dictData.getDictCode());
                break;
            }
        }
        if (vehicleInfo.getVehicleModel() == null) {
            throw new RuntimeException("车型代码不存在");
        }
        insertVehicleInfo(vehicleInfo);
    }
}
