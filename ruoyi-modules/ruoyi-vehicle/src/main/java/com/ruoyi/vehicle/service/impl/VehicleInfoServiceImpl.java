package com.ruoyi.vehicle.service.impl;

import com.alibaba.fastjson2.JSON;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.core.enums.RuleItemType;
import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.common.core.model.FieldValidationResult;
import com.ruoyi.common.core.model.RuleViolation;
import com.ruoyi.common.core.model.ValidationReport;
import com.ruoyi.common.core.utils.DateUtils;
import com.ruoyi.common.core.utils.StringUtils;
import com.ruoyi.common.core.utils.bean.BeanUtils;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.security.utils.SecurityUtils;
import com.ruoyi.system.api.RemoteDictService;
import com.ruoyi.system.api.RemoteNoticeService;
import com.ruoyi.system.api.RemoteTranslateService;
import com.ruoyi.system.api.domain.SysDictData;
import com.ruoyi.system.api.domain.SysNotice;
import com.ruoyi.system.api.enums.SysNoticeModel;
import com.ruoyi.system.api.model.LoginUser;
import com.ruoyi.vehicle.domain.*;
import com.ruoyi.vehicle.domain.dto.VehicleDto;
import com.ruoyi.vehicle.enums.VehicleLifecycleOperation;
import com.ruoyi.vehicle.mapper.*;
import com.ruoyi.vehicle.service.IFirstVehicleCheckService;
import com.ruoyi.vehicle.service.IMaterialBlacklistService;
import com.ruoyi.vehicle.service.IVehicleInfoService;
import com.ruoyi.vehicle.service.IVehicleValidationService;
import com.ruoyi.vehicle.utils.JsonDictConverter;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;


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

    @Autowired
    private AbnormalClassifyMapper abnormalClassifyMapper;

    @Autowired
    private RemoteNoticeService remoteNoticeService;

    @Autowired
    private VehicleTemplateMapper vehicleTemplateMapper;

    @Autowired
    private MaterialMapper materialMapper;

    @Autowired
    private IMaterialBlacklistService materialBlacklistService;

    @Autowired
    private IFirstVehicleCheckService firstVehicleCheckService;

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

            // 解析 json 的每个 key，关联 vehicle_attribute 字典，
            // 查出对应的 otherLabel 和 otherLabelSystem 并挂载到实体
            try {
                Map<String, Object> jsonMap = objectMapper.readValue(
                        vehicle.getJson(),
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});

                if (!jsonMap.isEmpty()) {
                    com.ruoyi.common.core.domain.R<List<SysDictData>> dictResult =
                            remoteDictService.getDictDataByType("vehicle_attribute");

                    if (dictResult != null && dictResult.getData() != null) {
                        Map<String, SysDictData> dictLabelMap = dictResult.getData().stream()
                                .filter(d -> StringUtils.isNotBlank(d.getDictLabel()))
                                .collect(java.util.stream.Collectors.toMap(
                                        SysDictData::getDictLabel,
                                        d -> d,
                                        (existing, replacement) -> existing
                                ));

                        Map<String, Map<String, String>> jsonDictMap = new LinkedHashMap<>();
                        for (String key : jsonMap.keySet()) {
                            SysDictData dictData = dictLabelMap.get(key);
                            Map<String, String> labels = new HashMap<>();
                            labels.put("otherLabel",       dictData != null ? dictData.getOtherLabel()       : null);
                            labels.put("otherLabelSystem", dictData != null ? dictData.getOtherLabelSystem() : null);
                            jsonDictMap.put(key, labels);
                        }
                        vehicle.setOtherSystem(jsonDictMap);
                    }
                }
            } catch (Exception e) {
                log.warn("VehicleInfo json 字典匹配失败, vehicleId={}", vehicleId, e);
            }
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
        for (VehicleInfo vehicle : list) {
            if (StringUtils.isNotBlank(vehicle.getJson())) {
                Map<String, Object> convertedMap = jsonDictConverter.convertJsonKeysToDictLabel(vehicle.getJson());
                vehicle.setJsonMap(convertedMap);
            }
            // 回收站数据，vin 去掉 _DEL_ 后缀，只影响显示
            if (vehicle.getVin() != null && vehicle.getVin().contains("_DEL_")) {
                vehicle.setVin(vehicle.getVin().substring(0, vehicle.getVin().indexOf("_DEL_")));
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
        // VIN判重
        if (vehicleInfoMapper.selectVehicleInfoByVin(vehicleInfo.getVin()) != null) {
            throw new RuntimeException("VIN[" + vehicleInfo.getVin() + "]已存在");
        }

        // 查模板
        Material material = new Material();
        material.setMaterialNo(vehicleInfo.getMaterialNo());
        List<Material> materialList = materialMapper.selectMaterialList(material);
        Long vehicleTemplateId;
        if (materialList.isEmpty()) {
            vehicleTemplateId = vehicleTemplateMaterialMapper
                    .selectVehicleTemplateIdByMaterialNo(vehicleInfo.getMaterialNo(), vehicleInfo.getTvv(), vehicleInfo.getBrand(),
                            vehicleInfo.getWeight(), vehicleInfo.getSaleName(), vehicleInfo.getTire(), vehicleInfo.getBreakpointTime());
            if (vehicleTemplateId == null) {
                throw new RuntimeException("该物料号、品牌、重量、销售名称、轮胎无对应的可用车辆模板");
            }
        } else {
            vehicleTemplateId = materialList.get(0).getVehicleTemplateId();
        }

        // 查模板详情，自动填充关联字段
        VehicleTemplate template = vehicleTemplateMapper.selectVehicleTemplateById(vehicleTemplateId);
        if (template == null) {
            throw new RuntimeException("模板不存在，templateId=" + vehicleTemplateId);
        }

        vehicleInfo.setVehicleTemplateId(String.valueOf(vehicleTemplateId));
        vehicleInfo.setWvtaNo(template.getWvtaCocNo());
        vehicleInfo.setCocTemplateNo(template.getCocTemplateNo());
        vehicleInfo.setJson(template.getJson());
        vehicleInfo.setUploadStatus(0);
        vehicleInfo.setValidationResult(0);
        vehicleInfo.setDeleted(0);
        vehicleInfo.setCreateTime(vehicleInfo.getCreateTime() == null ? DateUtils.getNowDate() : vehicleInfo.getCreateTime());
        vehicleInfo.setCreateBy(SecurityUtils.getUsername() != null
                ? SecurityUtils.getUsername() : "MES To System");

        // VehicleTemplate.json 已在模板导入阶段完成字段映射，直接使用
        VehicleLifecycle vehicleLifecycle = new VehicleLifecycle();
        vehicleLifecycle.setEntryId(vehicleInfo.getVehicleId());
        vehicleLifecycle.setTime(new Date());
        vehicleLifecycle.setVin(vehicleInfo.getVin());
        vehicleLifecycle.setOperate(VehicleLifecycleOperation.VEHICLE_INFO_CREATE.getOperation());
        vehicleLifecycle.setResult(0);
        vehicleLifecycleMapper.insert(vehicleLifecycle);

        validateVehicleInfo(Collections.singletonList(vehicleInfo.getVehicleId()));
        int insertRow = vehicleInfoMapper.insertVehicleInfo(vehicleInfo);
        if (insertRow > 0) {
            // 新增：触发首台车标识检查
            firstVehicleCheckService.handleAfterInsert(Collections.singletonList(vehicleInfo));
        }

        if (vehicleInfo.getBreakpointTime() != null) {
            String sb =
                    "车辆VIN " +
                            vehicleInfo.getVin() +
                            " 存在断点: " +
                            DateUtils.parseDateToStr("yyyy-MM-dd HH:mm:ss", vehicleInfo.getBreakpointTime()) +
                            System.lineSeparator();
            Map<String, String> params = new HashMap<>();
            params.put("vin", vehicleInfo.getVin());
            params.put("vehicleModel", vehicleInfo.getVehicleModel());
            params.put("factoryCode", vehicleInfo.getFactoryCode());
            params.put("country", vehicleInfo.getCountry());
            params.put("issueDate", DateUtils.parseDateToStr("yyyy-MM-dd HH:mm:ss", vehicleInfo.getIssueDate()));
            params.put("materialNo", vehicleInfo.getMaterialNo());
            SysNotice sysNotice = new SysNotice();
            sysNotice.setModel(SysNoticeModel.VEHICLE_INFO.getModel());
            sysNotice.setQueryParams(JSON.toJSONString(params));
            sysNotice.setIsRead(false);
            sysNotice.setStatus("0");
            sysNotice.setNoticeType("1");
            sysNotice.setNoticeTitle("MES系统推送断点提醒");
            sysNotice.setNoticeContent(sb);
            sysNotice.setCreateBy("自动提醒");
            sysNotice.setCreateTime(new Date());
            sysNotice.setSorts(Arrays.asList(16, 17));
            remoteNoticeService.innerAdd(sysNotice);
        }
        return insertRow;
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
    @Transactional(rollbackFor = Exception.class)
    public int updateVehicleInfo(VehicleInfo vehicleInfo, boolean needValid) {
        if (StringUtils.isNotBlank(vehicleInfo.getMaterialNo())) {
            Material material = new Material();
            material.setMaterialNo(vehicleInfo.getMaterialNo());
            List<Material> materialList = materialMapper.selectMaterialList(material);
            Long vehicleTemplateId;
            if (materialList.isEmpty()) {
                vehicleTemplateId = vehicleTemplateMaterialMapper
                        .selectVehicleTemplateIdByMaterialNo(vehicleInfo.getMaterialNo(), vehicleInfo.getTvv(), vehicleInfo.getBrand(),
                                vehicleInfo.getWeight(), vehicleInfo.getSaleName(), vehicleInfo.getTire(), vehicleInfo.getBreakpointTime());
                if (vehicleTemplateId == null) {
                    throw new RuntimeException("该物料号、品牌、重量、销售名称、轮胎无对应的可用车辆模板");
                }
            } else {
                vehicleTemplateId = materialList.get(0).getVehicleTemplateId();
            }
            VehicleTemplate template = vehicleTemplateMapper.selectVehicleTemplateById(vehicleTemplateId);
            if (template == null) {
                throw new RuntimeException("模板不存在，templateId=" + vehicleTemplateId);
            }
            vehicleInfo.setVehicleTemplateId(String.valueOf(vehicleTemplateId));
            vehicleInfo.setWvtaNo(template.getWvtaCocNo());
            vehicleInfo.setCocTemplateNo(template.getCocTemplateNo());
            // VehicleTemplate.json 已在导入阶段完成字段映射，直接使用，无需再次转换
            vehicleInfo.setJson(template.getJson());
        }
        // 去掉这里强制重置，交给调用方自己决定
        vehicleInfo.setVin(null);
        vehicleInfo.setUpdateTime(DateUtils.getNowDate());
        vehicleInfo.setUpdateBy(SecurityUtils.getUsername());
        int row = vehicleInfoMapper.updateVehicleInfo(vehicleInfo);
        if (needValid) {
            validateVehicleInfo(Collections.singletonList(vehicleInfo.getVehicleId()));
        }
        return row;
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
            List<VehicleInfo> list = vehicleInfoMapper.selectVehicleInfoByIds(vehicleIds);
            for (VehicleInfo v : list) {
                v.setVin(v.getVin() + "_DEL_" + System.currentTimeMillis());
                v.setDeleted(2);
                vehicleInfoMapper.updateVehicleInfo(v);
            }
            // 删除完成后统一触发（传删除前查出的原始列表）
            firstVehicleCheckService.handleAfterDelete(list);
            return AjaxResult.success(list.size());
        } catch (Exception e) {
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
        List<VehicleInfo> list = vehicleInfoMapper.selectVehicleInfoByIds(vehicleIds);
        List<String> conflictVins = new ArrayList<>();

        for (VehicleInfo v : list) {
            String realVin = v.getVin().contains("_DEL_")
                    ? v.getVin().substring(0, v.getVin().indexOf("_DEL_"))
                    : v.getVin();

            // 检查是否有相同 vin 的正常数据
            VehicleInfo existing = vehicleInfoMapper.selectByVinAndDeleted(realVin, 0);
            if (existing != null) {
                conflictVins.add(realVin);
            }
        }

        // 有冲突直接返回错误
        if (!conflictVins.isEmpty()) {
            return AjaxResult.error("以下VIN已存在正常数据，无法恢复，请先处理冲突："
                    + String.join(", ", conflictVins));
        }

        // 无冲突正常恢复
        for (VehicleInfo v : list) {
            String realVin = v.getVin().contains("_DEL_")
                    ? v.getVin().substring(0, v.getVin().indexOf("_DEL_"))
                    : v.getVin();
            v.setVin(realVin);
            v.setDeleted(0);
            vehicleInfoMapper.updateVehicleInfo(v);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("restoreRows", list.size());
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
        List<AbnormalClassify> abnormalClassifies = new ArrayList<>();
        AbnormalClassify abnormalClassify;
        for (Long vehicleInfoId : vehicleInfoIds) {
            VehicleInfo vehicleInfo = vehicleInfoMapper.selectVehicleInfoById(vehicleInfoId);
            ValidationReport validationReport = vehicleValidationService.validate(vehicleInfo.getJson(), vehicleInfo.getVehicleModel(), null);
            if (validationReport.isAllValid()) {
                vehicleInfo.setValidationResult(1);
            } else {
                vehicleInfo.setValidationResult(2);
                try {
                    vehicleInfo.setValidationReportJson(objectMapper.writeValueAsString(validationReport.getFailedFields()));
                } catch (JsonProcessingException e) {
                    log.error("对象转 JSON 失败", e);
                    throw new RuntimeException("校验报告保存失败");
                }
            }
            validationReports.add(validationReport);
            vehicleInfoMapper.updateVehicleInfo(vehicleInfo);
            VehicleLifecycle vehicleLifecycle = new VehicleLifecycle();
            vehicleLifecycle.setEntryId(vehicleInfo.getVehicleId());
            vehicleLifecycle.setTime(new Date());
            vehicleLifecycle.setVin(vehicleInfo.getVin());
            vehicleLifecycle.setOperate(VehicleLifecycleOperation.VEHICLE_INFO_VALIDATE.getOperation());
            vehicleLifecycle.setResult(validationReport.isAllValid() ? 0 : 1);
            vehicleLifecycleMapper.insert(vehicleLifecycle);

            for (FieldValidationResult fieldValidationResult: validationReport.getFieldResults()) {
                for (RuleViolation ruleViolation: fieldValidationResult.getViolations()) {
                    abnormalClassify = new AbnormalClassify();
                    abnormalClassify.setEntryId(String.valueOf(vehicleInfoId));
                    abnormalClassify.setEntryType("Vehicle Info");
                    abnormalClassify.setRuleType(RuleItemType.getRuleType(ruleViolation.getRuleType()));
                    abnormalClassifies.add(abnormalClassify);
                }
            }

            Map<String, String> params = new HashMap<>();
            params.put("vin", vehicleInfo.getVin());
            params.put("vehicleModel", vehicleInfo.getVehicleModel());
            params.put("factoryCode", vehicleInfo.getFactoryCode());
            params.put("country", vehicleInfo.getCountry());
            params.put("issueDate", DateUtils.parseDateToStr("yyyy-MM-dd HH:mm:ss", vehicleInfo.getIssueDate()));
            params.put("materialNo", vehicleInfo.getMaterialNo());
            params.put("validationResult", validationReport.isAllValid() ? "1": "2");
            SysNotice sysNotice = new SysNotice();
            sysNotice.setModel(SysNoticeModel.VEHICLE_INFO.getModel());
            sysNotice.setQueryParams(JSON.toJSONString(params));
            sysNotice.setIsRead(false);
            sysNotice.setNoticeType("1");
            sysNotice.setNoticeTitle("车辆信息校验完成通知");
            String msg =
                    "车辆VIN " +
                            vehicleInfo.getVin() +
                            " 的校验结果为：" +
                            (validationReport.isAllValid() ? "通过" : "失败");
            sysNotice.setNoticeContent(msg);
            sysNotice.setSorts(Arrays.asList(10, 11));
            sysNotice.setCreateBy("自动提醒");
            sysNotice.setCreateTime(new Date());
            remoteNoticeService.innerAdd(sysNotice);
        }
        if (!abnormalClassifies.isEmpty()) {
            abnormalClassifyMapper.batchInsert(abnormalClassifies);
        }
        return validationReports;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> getVehicleInfoFromMes(VehicleDto.Vehicle vehicle, Date now, LoginUser loginUser) {
        // 直接用传进来的 loginUser，不从 SecurityContext 取
        Set<String> permissions = loginUser.getPermissions();
        if (!permissions.contains("vehicle:info:toSystem")) {
            throw new ServiceException("没有权限执行此操作");
        }
        VehicleInfo vehicleInfo = new VehicleInfo();
        BeanUtils.copyProperties(vehicle, vehicleInfo);
        vehicleInfo.setCreateTime(now);
        List<SysDictData> sysDictData = remoteDictService.getDictDataByType("vehicle_model").getData();
        for (SysDictData dictData : sysDictData) {
            if (dictData.getDictLabel().equals(vehicle.getVehicleModel())) {
                vehicleInfo.setVehicleModel(dictData.getDictValue());
                break;
            }
        }
        if (vehicleInfo.getVehicleModel() == null) {
            throw new RuntimeException("车型代码不存在");
        }
        insertVehicleInfo(vehicleInfo);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("vin", vehicleInfo.getVin());
        result.put("recordId", vehicleInfo.getVehicleId());
        result.put("receiveTime", com.alibaba.fastjson2.util.DateUtils.format(now, "yyyy-MM-dd HH:mm:ss"));
        result.put("cause", null);
        return result;
    }

    @Override
    public VehicleInfo selectVehicleInfoByVin(String vin) {
        return vehicleInfoMapper.selectVehicleInfoByVin(vin);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void importVehicleInfoFromExcel(MultipartFile file) throws IOException {
        Workbook workbook = new XSSFWorkbook(file.getInputStream());
        Sheet sheet = workbook.getSheetAt(0);

        // 第一行是表头，从第二行开始读数据
        int lastRowNum = sheet.getLastRowNum();
        if (lastRowNum < 1) {
            throw new RuntimeException("Excel中没有数据行");
        }

        List<String> errorMsgs = new ArrayList<>();

        for (int rowIndex = 1; rowIndex <= lastRowNum; rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) continue;

            // 读取各列
            String vin                 = getCellStringValue(row.getCell(0));
            String vehicleModel        = getCellStringValue(row.getCell(1));
            String materialNo          = getCellStringValue(row.getCell(2));
            String brand               = getCellStringValue(row.getCell(3));
            String weight              = getCellStringValue(row.getCell(4));
            String saleName            = getCellStringValue(row.getCell(5));
            String tire                = getCellStringValue(row.getCell(6));
            String projectName         = getCellStringValue(row.getCell(7));
            String customerNo          = getCellStringValue(row.getCell(8));
            String tireResistanceGrade = getCellStringValue(row.getCell(9));
            String factoryCode         = getCellStringValue(row.getCell(10));
            String factoryName         = getCellStringValue(row.getCell(11));
            String country             = getCellStringValue(row.getCell(12));
            String color               = getCellStringValue(row.getCell(13));
            String secondaryColor      = getCellStringValue(row.getCell(14));
            Date issueDate             = getCellDateValue(row.getCell(15));
            String certificateVersion  = getCellStringValue(row.getCell(16));
            String tvv                 = getCellStringValue(row.getCell(17));
            Date manufactureDate       = getCellDateValue(row.getCell(18));
            String engineNumber        = getCellStringValue(row.getCell(19));
            String batteryNumber       = getCellStringValue(row.getCell(20));
            String motorNumber         = getCellStringValue(row.getCell(21));
            Date breakpointTime        = getCellDateValue(row.getCell(22));

            // 跳过空行
            if (StringUtils.isBlank(vin)) continue;

            try {
                // VIN判重
                if (vehicleInfoMapper.selectVehicleInfoByVin(vin) != null) {
                    errorMsgs.add("第" + (rowIndex + 1) + "行：VIN[" + vin + "]已存在，跳过");
                    continue;
                }

                // 通过物料号查模板ID
                Long templateId = vehicleTemplateMaterialMapper.selectVehicleTemplateIdByMaterialNo(materialNo, tvv, brand, weight, saleName, tire, breakpointTime);
                if (templateId == null) {
                    errorMsgs.add("第" + (rowIndex + 1) + "行：物料号[" + materialNo + "]未找到可用关联模板，跳过");
                    continue;
                }

                // 查模板详情，获取 wvtaCocNo、cocTemplateNo、json
                VehicleTemplate template = vehicleTemplateMapper
                        .selectVehicleTemplateById(templateId);
                if (template == null) {
                    errorMsgs.add("第" + (rowIndex + 1) + "行：模板ID[" + templateId + "]不存在，跳过");
                    continue;
                }

                // 组装 VehicleInfo
                VehicleInfo vehicleInfo = new VehicleInfo();
                vehicleInfo.setVin(vin);
                vehicleInfo.setVehicleModel(vehicleModel);
                vehicleInfo.setMaterialNo(materialNo);
                vehicleInfo.setBrand(brand);
                vehicleInfo.setWeight(weight);
                vehicleInfo.setSaleName(saleName);
                vehicleInfo.setTire(tire);
                vehicleInfo.setProjectName(projectName);
                vehicleInfo.setCustomerNo(customerNo);
                vehicleInfo.setTireResistanceGrade(tireResistanceGrade);
                vehicleInfo.setFactoryCode(factoryCode);
                vehicleInfo.setFactoryName(factoryName);
                vehicleInfo.setCountry(country);
                vehicleInfo.setColor(color);
                vehicleInfo.setSecondaryColor(secondaryColor);
                vehicleInfo.setIssueDate(issueDate);
                vehicleInfo.setCertificateVersion(certificateVersion);
                vehicleInfo.setTvv(tvv);
                vehicleInfo.setManufactureDate(manufactureDate);
                vehicleInfo.setEngineNumber(engineNumber);
                vehicleInfo.setBatteryNumber(batteryNumber);
                vehicleInfo.setMotorNumber(motorNumber);
                vehicleInfo.setBreakpointTime(breakpointTime);


                // 从模板自动获取
                vehicleInfo.setWvtaNo(template.getWvtaCocNo());
                vehicleInfo.setCocTemplateNo(template.getCocTemplateNo());
                vehicleInfo.setJson(template.getJson());
                vehicleInfo.setVehicleTemplateId(String.valueOf(templateId));

                // 默认值
                vehicleInfo.setUploadStatus(0);
                vehicleInfo.setValidationResult(0);
                vehicleInfo.setDeleted(0);
                vehicleInfo.setGenerateAffirm(0);
                vehicleInfo.setUploadAffirm(0);
                vehicleInfo.setCreateBy(SecurityUtils.getUsername() != null ? SecurityUtils.getUsername() : "MES To System");
                vehicleInfo.setCreateTime(DateUtils.getNowDate());

                // VehicleTemplate.json 已在导入阶段完成字段映射，直接使用，无需再次转换
                vehicleInfoMapper.insertVehicleInfo(vehicleInfo);

                // 写入生命周期
                VehicleLifecycle lifecycle = new VehicleLifecycle();
                lifecycle.setTime(new Date());
                lifecycle.setVin(vin);
                lifecycle.setOperate("0");
                lifecycle.setResult(0);
                vehicleLifecycleMapper.insert(lifecycle);

                validateVehicleInfo(Collections.singletonList(vehicleInfo.getVehicleId()));

            } catch (Exception e) {
                log.error("导入第{}行异常：{}", rowIndex + 1, e.getMessage(), e);
                errorMsgs.add("第" + (rowIndex + 1) + "行：导入异常，" + e.getMessage());
            }
        }

        workbook.close();

        // 有错误行则汇总提示，但不影响成功行
        if (!errorMsgs.isEmpty()) {
            throw new RuntimeException("部分数据导入失败：\n" + String.join("\n", errorMsgs));
        }
    }

    @Override
    public List<String> selectAllMaterialNos() {
        return vehicleTemplateMaterialMapper.selectAllMaterialNos();
    }

    @Override
    public Long selectVehicleTemplateIdByMaterialNo(String materialNo) {
        return vehicleTemplateMaterialMapper.selectVehicleTemplateIdByMaterialNo(materialNo, null, null, null, null, null, null);
    }

    @Override
    public VehicleTemplate selectVehicleTemplateById(Long templateId) {
        return vehicleTemplateMapper.selectVehicleTemplateById(templateId);
    }

    @Override
    public List<Map<String, Object>> selectVehicleTemplateIdByCondition(String materialNo, String brand, String weight, String saleName, String tire, String tvv) {
        List<VehicleTemplate> templates = vehicleTemplateMapper.selectVehicleTemplateIdByCondition(materialNo, brand, weight, saleName, tire, tvv);
        if (templates.isEmpty()) {
            throw new RuntimeException("无法匹配任何可用模板");
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (VehicleTemplate template : templates) {
            Map<String, Object> templateMap = new HashMap<>();
            templateMap.put("vehicleTemplateId", template.getTemplateId());
            templateMap.put("version", template.getVersion());
            templateMap.put("tvv", template.getTvv());
            result.add(templateMap);
        }
        return result;
    }

    public int updateVehicleTemplateId(String vin, Long templateId) {
        return vehicleTemplateMapper.updateVehicleTemplateId(vin, templateId);
    }

// ========== 工具方法 ==========

    private String getCellStringValue(Cell cell) {
        if (cell == null) return "";
        switch (cell.getCellType()) {
            case STRING:  return cell.getStringCellValue().trim();
            case NUMERIC:
                // 防止数字被读成科学计数法
                return new java.math.BigDecimal(cell.getNumericCellValue())
                        .stripTrailingZeros().toPlainString();
            case BOOLEAN: return String.valueOf(cell.getBooleanCellValue());
            default:      return "";
        }
    }

    private Date getCellDateValue(Cell cell) {
        if (cell == null) return null;
        if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            return cell.getDateCellValue();
        }
        if (cell.getCellType() == CellType.STRING) {
            String val = cell.getStringCellValue().trim();
            if (StringUtils.isNotBlank(val)) {
                try {
                    return new java.text.SimpleDateFormat("yyyy-MM-dd").parse(val);
                } catch (Exception e) {
                    log.warn("日期解析失败：{}", val);
                }
            }
        }
        return null;
    }

}
