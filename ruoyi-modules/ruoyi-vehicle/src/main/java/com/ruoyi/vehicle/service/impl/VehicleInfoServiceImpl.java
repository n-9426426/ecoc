package com.ruoyi.vehicle.service.impl;

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
import com.ruoyi.system.api.model.LoginUser;
import com.ruoyi.vehicle.domain.AbnormalClassify;
import com.ruoyi.vehicle.domain.VehicleInfo;
import com.ruoyi.vehicle.domain.VehicleLifecycle;
import com.ruoyi.vehicle.domain.VehicleTemplate;
import com.ruoyi.vehicle.domain.dto.VehicleDto;
import com.ruoyi.vehicle.mapper.*;
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

    @Autowired
    private AbnormalClassifyMapper abnormalClassifyMapper;

    @Autowired
    private RemoteNoticeService remoteNoticeService;

    @Autowired
    private VehicleTemplateMapper vehicleTemplateMapper;

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

        // 通过物料号查模板
        Long vehicleTemplateId = vehicleTemplateMaterialMapper
                .selectVehicleTemplateIdByMaterialNo(vehicleInfo.getMaterialNo(), vehicleInfo.getBrand(),
                        vehicleInfo.getWeight(), vehicleInfo.getSaleName(), vehicleInfo.getTire());
        if (vehicleTemplateId == null) {
            throw new RuntimeException("该物料号、品牌、重量、销售名称、轮胎对应的车辆模板不存在");
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
        vehicleInfo.setCreateTime(DateUtils.getNowDate());
        vehicleInfo.setCreateBy(SecurityUtils.getUsername() != null
                ? SecurityUtils.getUsername() : "MES To System");

        int row = vehicleInfoMapper.insertVehicleInfo(vehicleInfo);

        VehicleLifecycle vehicleLifecycle = new VehicleLifecycle();
        vehicleLifecycle.setEntryId(vehicleInfo.getVehicleId());
        vehicleLifecycle.setTime(new Date());
        vehicleLifecycle.setVin(vehicleInfo.getVin());
        vehicleLifecycle.setOperate("0");
        vehicleLifecycle.setResult(0);
        vehicleLifecycleMapper.insert(vehicleLifecycle);

        validateVehicleInfo(Collections.singletonList(vehicleInfo.getVehicleId()));
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
    @Transactional(rollbackFor = Exception.class)
    public int updateVehicleInfo(VehicleInfo vehicleInfo) {
        if (StringUtils.isNotBlank(vehicleInfo.getMaterialNo())) {
            Long vehicleTemplateId = vehicleTemplateMaterialMapper
                    .selectVehicleTemplateIdByMaterialNo(vehicleInfo.getMaterialNo(), vehicleInfo.getBrand(),
                            vehicleInfo.getWeight(), vehicleInfo.getSaleName(), vehicleInfo.getTire());
            if (vehicleTemplateId == null) {
                throw new RuntimeException("该物料号对应的车辆模板不存在");
            }
            VehicleTemplate template = vehicleTemplateMapper
                    .selectVehicleTemplateById(vehicleTemplateId);
            if (template == null) {
                throw new RuntimeException("模板不存在，templateId=" + vehicleTemplateId);
            }
            vehicleInfo.setVehicleTemplateId(String.valueOf(vehicleTemplateId));
            vehicleInfo.setWvtaNo(template.getWvtaCocNo());
            vehicleInfo.setCocTemplateNo(template.getCocTemplateNo());
            vehicleInfo.setJson(template.getJson());
        }
        // 去掉这里强制重置，交给调用方自己决定
        vehicleInfo.setVin(null);
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
            // 查出要删除的车辆
            List<VehicleInfo> list = vehicleInfoMapper.selectVehicleInfoByIds(vehicleIds);
            for (VehicleInfo v : list) {
                // vin 加时间戳打破唯一键
                v.setVin(v.getVin() + "_DEL_" + System.currentTimeMillis());
                v.setDeleted(2);
                vehicleInfoMapper.updateVehicleInfo(v);
            }
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
        SysNotice sysNotice = new SysNotice();
        sysNotice.setIsRead(false);
        sysNotice.setNoticeType("1");
        sysNotice.setNoticeTitle("车辆信息校验完成通知");
        StringBuilder msg = new StringBuilder("车辆信息");
        AbnormalClassify abnormalClassify;
        for (Long vehicleInfoId : vehicleInfoIds) {
            VehicleInfo vehicleInfo = vehicleInfoMapper.selectVehicleInfoById(vehicleInfoId);
            List<SysDictData> sysDictData = remoteDictService.getDictDataByType("vehicle_attribute").getData();
            sysDictData = sysDictData.stream()
                    .filter(data -> vehicleInfo.getVehicleModel().equals(data.getDictTypeAffiliation()))
                    .collect(Collectors.toList());
            SysDictData vehicleModel = remoteDictService.getDataByDictCode(vehicleInfo.getVehicleModel()).getData();
            ValidationReport validationReport = vehicleValidationService.validate(vehicleInfo.getJson(), vehicleModel.getDictValue(), null);
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
            vehicleLifecycle.setOperate("1");
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

            msg.append(System.lineSeparator());
            msg.append("Vin");
            msg.append(vehicleInfo.getVin());
            msg.append("的校验结果为");
            msg.append(validationReport.isAllValid() ? "通过" : "失败");
        }

        if (!abnormalClassifies.isEmpty()) {
            abnormalClassifyMapper.batchInsert(abnormalClassifies);
        }
        sysNotice.setNoticeContent(msg.toString());
        remoteNoticeService.add(sysNotice);
        return validationReports;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void getVehicleInfoFromMes(VehicleDto vehicleDto) {
        // 获取当前登录用户
        LoginUser loginUser = SecurityUtils.getLoginUser();

        // 判断是否有某个权限
        Set<String> permissions = loginUser.getPermissions();
        if (!permissions.contains("vehicle:info:toSystem")) {
            throw new ServiceException("没有权限执行此操作");
        }
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

    @Override
    public VehicleInfo selectVehicleInfoByVin(String vin) {
        return vehicleInfoMapper.selectVehicleInfoByVin(vin);
    }

    @Override
    public void importVehicleInfoFromExcel(MultipartFile file) throws IOException {
        Workbook workbook = new XSSFWorkbook(file.getInputStream());
        Sheet sheet = workbook.getSheetAt(0);

        // 第一行是表头，从第二行开始读数据
        int lastRowNum = sheet.getLastRowNum();
        if (lastRowNum < 1) {
            throw new RuntimeException("Excel中没有数据行");
        }

        // 预加载字典，避免每行都调用远程接口
        List<SysDictData> vehicleModelDicts = remoteDictService
                .getDictDataByType("vehicle_model").getData();
        List<SysDictData> countryDicts = remoteDictService
                .getDictDataByType("country").getData();

        List<String> errorMsgs = new ArrayList<>();

        for (int rowIndex = 1; rowIndex <= lastRowNum; rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) continue;

            // 读取各列：VIN、车型代码、工厂代码、整车物料号、颜色、双色的次色、出口国家、发证日期
            String vin           = getCellStringValue(row.getCell(0));
            String vehicleModel  = getCellStringValue(row.getCell(1)); // dictValue，如"E03"
            String factoryCode   = getCellStringValue(row.getCell(2));
            String materialNo    = getCellStringValue(row.getCell(3));
            String color         = getCellStringValue(row.getCell(4));
            String secondaryColor= getCellStringValue(row.getCell(5));
            String country       = getCellStringValue(row.getCell(6)); // dictValue，如"西班牙"
            Date issueDate       = getCellDateValue(row.getCell(7));
            String brand         = getCellStringValue(row.getCell(8));
            String weight        = getCellStringValue(row.getCell(9));
            String saleName      = getCellStringValue(row.getCell(10));
            String trie          = getCellStringValue(row.getCell(11));

            // 跳过空行
            if (StringUtils.isBlank(vin)) continue;

            try {
                // VIN判重
                if (vehicleInfoMapper.selectVehicleInfoByVin(vin) != null) {
                    errorMsgs.add("第" + (rowIndex + 1) + "行：VIN[" + vin + "]已存在，跳过");
                    continue;
                }

                // 车型代码 dictValue -> dictCode
                Long vehicleModelCode = null;
                if (StringUtils.isNotBlank(vehicleModel)) {
                    vehicleModelCode = vehicleModelDicts.stream()
                            .filter(d -> vehicleModel.equals(d.getDictValue()))
                            .map(SysDictData::getDictCode)
                            .findFirst()
                            .orElse(null);
                    if (vehicleModelCode == null) {
                        errorMsgs.add("第" + (rowIndex + 1) + "行：车型代码[" + vehicleModel + "]在字典中不存在，跳过");
                        continue;
                    }
                }

                // 出口国家 dictValue -> dictCode（存dictCode还是dictValue根据你的字段决定）
                String countryCode = null;
                if (StringUtils.isNotBlank(country)) {
                    countryCode = countryDicts.stream()
                            .filter(d -> country.equals(d.getDictLabel()))
                            .map(SysDictData::getDictValue)
                            .findFirst()
                            .orElse(country); // 找不到就存原值
                }

                // 通过物料号查模板ID
                Long templateId = vehicleTemplateMaterialMapper
                        .selectVehicleTemplateIdByMaterialNo(materialNo, brand, weight, saleName, trie);
                if (templateId == null) {
                    errorMsgs.add("第" + (rowIndex + 1) + "行：物料号[" + materialNo + "]未找到关联模板，跳过");
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
                vehicleInfo.setVehicleModel(vehicleModelCode);
                vehicleInfo.setFactoryCode(factoryCode);
                vehicleInfo.setMaterialNo(materialNo);
                vehicleInfo.setColor(color);
                vehicleInfo.setSecondaryColor(secondaryColor);
                vehicleInfo.setCountry(countryCode);
                vehicleInfo.setIssueDate(issueDate);

                // 从模板自动获取
                vehicleInfo.setWvtaNo(template.getWvtaCocNo());
                vehicleInfo.setCocTemplateNo(template.getCocTemplateNo());
                vehicleInfo.setJson(template.getJson());
                vehicleInfo.setVehicleTemplateId(String.valueOf(templateId));

                // 默认值
                vehicleInfo.setUploadStatus(0);
                vehicleInfo.setValidationResult(0);
                vehicleInfo.setDeleted(0);
                vehicleInfo.setCreateTime(DateUtils.getNowDate());
                vehicleInfo.setCreateBy(SecurityUtils.getUsername() != null
                        ? SecurityUtils.getUsername() : "MES To System");

                vehicleInfoMapper.insertVehicleInfo(vehicleInfo);

                // 写入生命周期
                VehicleLifecycle lifecycle = new VehicleLifecycle();
                lifecycle.setTime(new Date());
                lifecycle.setVin(vin);
                lifecycle.setOperate("0");
                lifecycle.setResult(0);
                vehicleLifecycleMapper.insert(lifecycle);

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
        return vehicleTemplateMaterialMapper.selectVehicleTemplateIdByMaterialNo(materialNo, null, null, null, null);
    }

    @Override
    public VehicleTemplate selectVehicleTemplateById(Long templateId) {
        VehicleTemplate template = vehicleTemplateMapper.selectVehicleTemplateById(templateId);
        return template;
    }

    @Override
    public List<Map<String, Object>> selectVehicleTemplateIdCondition(String materialNo, String brand, String weight, String saleName, String tire) {
        List<VehicleTemplate> templates = vehicleTemplateMapper.selectVehicleTemplateIdCondition(materialNo, brand, weight, saleName, tire);
        if (templates.isEmpty()) {
            throw new RuntimeException("该物料号未关联任何模板");
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (VehicleTemplate template : templates) {
            Map<String, Object> templateMap = new HashMap<>();
            templateMap.put("vehicleTemplateId", template.getTemplateId());
            templateMap.put("wvtaNo", template.getWvtaCocNo());
            templateMap.put("cocTemplateNo", template.getCocTemplateNo());
            templateMap.put("json", template.getJson());
            templateMap.put("version", template.getVersion());
            templateMap.put("tvv", template.getTvv());
            result.add(templateMap);
        }
        return result;
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
