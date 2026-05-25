package com.ruoyi.vehicle.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.core.enums.RuleItemType;
import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.common.core.model.FieldValidationResult;
import com.ruoyi.common.core.model.RuleViolation;
import com.ruoyi.common.core.model.ValidationReport;
import com.ruoyi.common.core.parser.ValueMappingParser;
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
import com.ruoyi.vehicle.domain.*;
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

        VehicleInfo row = jsonConvert(vehicleInfo);

        VehicleLifecycle vehicleLifecycle = new VehicleLifecycle();
        vehicleLifecycle.setEntryId(vehicleInfo.getVehicleId());
        vehicleLifecycle.setTime(new Date());
        vehicleLifecycle.setVin(vehicleInfo.getVin());
        vehicleLifecycle.setOperate("0");
        vehicleLifecycle.setResult(0);
        vehicleLifecycleMapper.insert(vehicleLifecycle);

        validateVehicleInfo(Collections.singletonList(vehicleInfo.getVehicleId()));
        return vehicleInfoMapper.insertVehicleInfo(row);
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
            vehicleInfo.setJson(template.getJson());
            jsonConvert(vehicleInfo);
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
        StringBuilder msg = new StringBuilder("车辆信息：");
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
            msg.append("Vin：");
            msg.append(vehicleInfo.getVin());
            msg.append("的校验结果为：");
            msg.append(validationReport.isAllValid() ? "通过" : "失败");
        }

        if (!abnormalClassifies.isEmpty()) {
            abnormalClassifyMapper.batchInsert(abnormalClassifies);
        }
        sysNotice.setNoticeContent(msg.toString());
        sysNotice.setSorts(Arrays.asList(10, 11));
        remoteNoticeService.innerAdd(sysNotice);
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
            String tire          = getCellStringValue(row.getCell(11));
            String tvv           = getCellStringValue(row.getCell(12));;
            Date breakpointTime  = getCellDateValue(row.getCell(13));

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
                vehicleInfo.setFactoryCode(factoryCode);
                vehicleInfo.setMaterialNo(materialNo);
                vehicleInfo.setColor(color);
                vehicleInfo.setSecondaryColor(secondaryColor);
                vehicleInfo.setCountry(country);
                vehicleInfo.setIssueDate(issueDate);
                vehicleInfo.setBrand(brand);
                vehicleInfo.setWeight(weight);
                vehicleInfo.setSaleName(saleName);
                vehicleInfo.setTire(tire);

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

                VehicleInfo waitInsertORow = jsonConvert(vehicleInfo);
                vehicleInfoMapper.insertVehicleInfo(waitInsertORow);

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

/*    private int insert(VehicleInfo vehicleInfo) {
        Map<String, Object> map = vehicleInfo.getJsonMap();
        if (map != null && !map.isEmpty()) {
            map = new LinkedHashMap<>(map);
        }
        Map<String, Object> finalMap = map;
        if (map != null && !map.isEmpty()) {
            List<SysDictData> dictDataList = remoteDictService
                    .getDictDataByType("vehicle_attribute").getData();

            if (dictDataList != null && !dictDataList.isEmpty()) {

                // ── 第一步：按 uuid 分组，无 uuid 的每条独立 ──────────────────
                Map<String, List<SysDictData>> uuidGroups = new LinkedHashMap<>();
                for (SysDictData rule : dictDataList) {
                    String groupKey = StringUtils.isNotBlank(rule.getUuid())
                            ? rule.getUuid()
                            : "$$solo$$" + rule.getDictCode();
                    uuidGroups.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(rule);
                }

                // ── 第二步：识别单 key_map 链 vs 多 key_map 链 ────────────────
                Map<String, List<List<SysDictData>>> keyToChains = new LinkedHashMap<>();
                Map<String, List<SysDictData>> multiKeyChainMap = new LinkedHashMap<>();

                for (Map.Entry<String, List<SysDictData>> groupEntry : uuidGroups.entrySet()) {
                    List<SysDictData> chain = groupEntry.getValue();
                    chain.sort(Comparator.comparingLong(SysDictData::getDictCode));

                    long distinctKeyMapCount = chain.stream()
                            .map(SysDictData::getKeyMap)
                            .filter(StringUtils::isNotBlank)
                            .distinct()
                            .count();

                    if (distinctKeyMapCount > 1) {
                        multiKeyChainMap.put(groupEntry.getKey(), chain);
                    } else {
                        String keyMap = chain.stream()
                                .map(SysDictData::getKeyMap)
                                .filter(StringUtils::isNotBlank)
                                .findFirst()
                                .orElse(null);
                        if (keyMap == null) continue;
                        keyToChains.computeIfAbsent(keyMap, k -> new ArrayList<>()).add(chain);
                    }
                }

                Map<String, Object> result = new LinkedHashMap<>();

                // ── 第三步：执行单 key_map 链式规则 ──────────────────────────
                for (Map.Entry<String, Object> entry : map.entrySet()) {
                    String fieldName = entry.getKey();
                    List<List<SysDictData>> chains = keyToChains.get(fieldName);

                    // 无规则：原样保留
                    if (chains == null || chains.isEmpty()) {
                        result.put(fieldName, entry.getValue());
                        continue;
                    }

                    String rawValue = entry.getValue() == null
                            ? null : String.valueOf(entry.getValue());
                    for (List<SysDictData> singleChain : chains) {
                        String converted = rawValue;
                        boolean forceEmpty = false; // 是否命中了 NULL 规则

                        for (SysDictData rule : singleChain) {
                            if (StringUtils.isBlank(rule.getValueMap())) {
                                converted = "";
                                break;
                            }
                            String stepped = ValueMappingParser.convert(converted, rule.getValueMap());
                            if (ValueMappingParser.EMPTY_SENTINEL.equals(stepped)) {
                                // NULL 规则：强制置空，终止链式
                                forceEmpty = true;
                                break;
                            }
                            if (stepped == null) {
                                log.warn("[insert] value_map 链式第 {} 步返回 null，终止。" +
                                                "dict_code={}, fieldName={}",
                                        rule.getDictSort(), rule.getDictCode(), fieldName);
                                break;
                            }
                            converted = stepped;
                        }

                        if (forceEmpty) {
                            converted = "";
                        } else {
                            converted = StringUtils.isNotBlank(converted) ? converted : rawValue;
                            converted = "N/A".equals(converted) ? "" : converted;
                        }

                        String targetLabel = singleChain.get(singleChain.size() - 1).getDictLabel();
                        result.put(targetLabel, converted);
                    }
                }

                // ── 第四步：执行多 key_map 链 ────────────────────────────────
                for (List<SysDictData> multiChain : multiKeyChainMap.values()) {
                    List<String> parts = new ArrayList<>();
                    SysDictData lastRule = null;

                    for (SysDictData rule : multiChain) {
                        if (StringUtils.isBlank(rule.getKeyMap())) continue;

                        Object rawObj = map.get(rule.getKeyMap());
                        String rawValue = rawObj == null ? null : String.valueOf(rawObj);

                        String converted = rawValue;
                        if (StringUtils.isNotBlank(rule.getValueMap())) {
                            String stepped = ValueMappingParser.convert(rawValue, rule.getValueMap());
                            if (ValueMappingParser.EMPTY_SENTINEL.equals(stepped)) {
                                // NULL 规则：本段强制为空，不计入拼接
                                lastRule = rule;
                                continue;
                            }
                            if (stepped != null) {
                                converted = stepped;
                            } else {
                                log.warn("[insert] 多key_map链转换返回 null，dict_code={}, keyMap={}",
                                        rule.getDictCode(), rule.getKeyMap());
                            }
                        }
                        converted = StringUtils.isNotBlank(converted) ? converted : rawValue;
                        converted = "N/A".equals(converted) ? "" : converted;

                        if (StringUtils.isNotBlank(converted)) {
                            parts.add(converted);
                        }
                        lastRule = rule;
                    }

                    if (lastRule != null && !parts.isEmpty()) {
                        String targetLabel = lastRule.getDictLabel();
                        result.put(targetLabel, String.join("/", parts));
                    }
                }

                finalMap = result;
            }
        }

        try {
            vehicleInfo.setJson(objectMapper.writeValueAsString(finalMap));
        } catch (Exception e) {
            throw new RuntimeException("无法格式化JSON数据");
        }

        return vehicleInfoMapper.insertVehicleInfo(vehicleInfo);
    }*/

    private VehicleInfo jsonConvert(VehicleInfo vehicleInfo) {
        Map<String, Object> map = vehicleInfo.getJsonMap();
        if (map != null && !map.isEmpty()) {
            map = new LinkedHashMap<>(map);
        }
        Map<String, Object> finalMap = map;

        if (map != null && !map.isEmpty()) {
            List<SysDictData> dictDataList = remoteDictService
                    .getDictDataByType("vehicle_attribute").getData();

            if (dictDataList != null && !dictDataList.isEmpty()) {

                // ── 第一步：按 uuid 分组，无 uuid 的每条独立 ──────────────────
                Map<String, List<SysDictData>> uuidGroups = new LinkedHashMap<>();
                for (SysDictData rule : dictDataList) {
                    String groupKey = StringUtils.isNotBlank(rule.getUuid())
                            ? rule.getUuid()
                            : "$$solo$$" + rule.getDictCode();
                    uuidGroups.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(rule);
                }

                // ── 第二步：识别单 key_map 链 vs 多 key_map 链 ────────────────
                // keyToChains：keyMap → List<链>（一个keyMap可能对应多条uuid链）
                Map<String, List<List<SysDictData>>> keyToChains = new LinkedHashMap<>();
                // multiKeyChainMap：一条链里有多个 keyMap（如 49.1. 拆成多个字段）
                Map<String, List<SysDictData>> multiKeyChainMap = new LinkedHashMap<>();

                for (Map.Entry<String, List<SysDictData>> groupEntry : uuidGroups.entrySet()) {
                    List<SysDictData> chain = groupEntry.getValue();
                    chain.sort(Comparator.comparingLong(SysDictData::getDictCode));

                    long distinctKeyMapCount = chain.stream()
                            .map(SysDictData::getKeyMap)
                            .filter(StringUtils::isNotBlank)
                            .distinct()
                            .count();

                    if (distinctKeyMapCount > 1) {
                        multiKeyChainMap.put(groupEntry.getKey(), chain);
                    } else {
                        String keyMap = chain.stream()
                                .map(SysDictData::getKeyMap)
                                .filter(StringUtils::isNotBlank)
                                .findFirst()
                                .orElse(null);
                        if (keyMap == null) continue;
                        keyToChains.computeIfAbsent(keyMap, k -> new ArrayList<>()).add(chain);
                    }
                }

                Map<String, Object> result = new LinkedHashMap<>();

                // ── 第三步：执行单 key_map 链式规则 ──────────────────────────
                for (Map.Entry<String, Object> entry : map.entrySet()) {
                    String fieldName = entry.getKey();
                    List<List<SysDictData>> chains = keyToChains.get(fieldName);

                    // 无规则：原样保留
                    if (chains == null || chains.isEmpty()) {
                        result.put(fieldName, entry.getValue());
                        continue;
                    }

                    String rawValue = entry.getValue() == null
                            ? null : String.valueOf(entry.getValue());

                    // ★ 关键修复：含 \n 的值按行拆分，每行分别匹配链中对应 dictLabel
                    // 判断：当这个 key 对应多条链（chains.size() > 1）或值含 \n 时，
                    // 视为多行多值，按行索引分配到各链
                    boolean isMultiLine = rawValue != null && rawValue.contains("\n");

                    if (isMultiLine && chains.size() > 1) {
                        // 多行多链：按行顺序依次对应每条链
                        String[] lines = rawValue.split("\n", -1);
                        for (int lineIdx = 0; lineIdx < chains.size(); lineIdx++) {
                            List<SysDictData> singleChain = chains.get(lineIdx);
                            String lineValue = lineIdx < lines.length
                                    ? lines[lineIdx].trim() : "";

                            String converted = applyChain(lineValue, singleChain, fieldName);
                            String targetLabel = singleChain.get(singleChain.size() - 1).getDictLabel();
                            if (StringUtils.isNotBlank(converted)) {
                                result.put(targetLabel, converted);
                            }
                        }
                    } else if (isMultiLine && chains.size() == 1) {
                        // ★ 单链但多行：说明这个 key 下的值本身就是多行，
                        // 整体传入链处理（例如 valueMap 用 SPLIT_MULTIROW），
                        // 或者直接原样保留各行（链不知道怎么拆就原样存）
                        List<SysDictData> singleChain = chains.get(0);
                        String converted = applyChain(rawValue, singleChain, fieldName);
                        String targetLabel = singleChain.get(singleChain.size() - 1).getDictLabel();
                        // 如果转换结果还是含 \n（链没有拆分），
                        // 把 \n 替换成 | 以匹配 eCoC 多值格式
                        if (converted != null && converted.contains("\n")) {
                            converted = converted.replace("\n", "|");
                        }
                        if (StringUtils.isNotBlank(converted)) {
                            result.put(targetLabel, converted);
                        }
                    } else {
                        // 单行单链（原有逻辑）
                        for (List<SysDictData> singleChain : chains) {
                            String converted = applyChain(rawValue, singleChain, fieldName);
                            String targetLabel = singleChain.get(singleChain.size() - 1).getDictLabel();
                            if (StringUtils.isNotBlank(converted)) {
                                result.put(targetLabel, converted);
                            }
                        }
                    }
                }

                // ── 第四步：执行多 key_map 链 ────────────────────────────────
                // ★ 修复：多 key_map 链里每一段都独立写到自己的 dictLabel，
                //    不再用 "/" 拼接成一个字符串（否则 INTPI/EL 会写进 WorkingPrinciple）
                for (List<SysDictData> multiChain : multiKeyChainMap.values()) {
                    multiChain.sort(Comparator.comparingLong(SysDictData::getDictCode));

                    for (SysDictData rule : multiChain) {
                        if (StringUtils.isBlank(rule.getKeyMap())) continue;
                        if (StringUtils.isBlank(rule.getDictLabel())) continue;

                        Object rawObj = map.get(rule.getKeyMap());
                        String rawValue = rawObj == null ? null : String.valueOf(rawObj);

                        String converted = rawValue;
                        if (StringUtils.isNotBlank(rule.getValueMap())) {
                            String stepped = ValueMappingParser.convert(rawValue, rule.getValueMap());
                            if (ValueMappingParser.EMPTY_SENTINEL.equals(stepped)) {
                                // NULL 规则：本段强制为空，跳过
                                continue;
                            }
                            if (stepped != null) {
                                converted = stepped;
                            } else {
                                log.warn("[insert] 多key_map链转换返回 null，dict_code={}, keyMap={}",
                                        rule.getDictCode(), rule.getKeyMap());
                            }
                        }

                        converted = StringUtils.isNotBlank(converted) ? converted : rawValue;
                        converted = "N/A".equals(converted) ? "" : converted;

                        // ★ 每一段独立写到自己的 dictLabel
                        if (StringUtils.isNotBlank(converted)) {
                            result.put(rule.getDictLabel(), converted);
                        }
                    }
                }

                finalMap = result;
            }
        }

        try {
            vehicleInfo.setJson(objectMapper.writeValueAsString(finalMap));
        } catch (Exception e) {
            throw new RuntimeException("无法格式化JSON数据");
        }

        return vehicleInfo;
    }

    /**
     * ★ 新增辅助方法：对单条链执行链式转换，返回最终值。
     * 抽取复用，避免在第三步/第四步重复写相同逻辑。
     */
    private String applyChain(String rawValue, List<SysDictData> chain, String fieldName) {
        String converted = rawValue;
        boolean forceEmpty = false;

        for (SysDictData rule : chain) {
            if (StringUtils.isBlank(rule.getValueMap())) {
                converted = "";
                break;
            }
            String stepped = ValueMappingParser.convert(converted, rule.getValueMap());
            if (ValueMappingParser.EMPTY_SENTINEL.equals(stepped)) {
                forceEmpty = true;
                break;
            }
            if (stepped == null) {
                log.warn("[insert] value_map 链式第 {} 步返回 null，终止。dict_code={}, fieldName={}",
                        rule.getDictSort(), rule.getDictCode(), fieldName);
                break;
            }
            converted = stepped;
        }

        if (forceEmpty) {
            return "";
        }
        converted = StringUtils.isNotBlank(converted) ? converted : rawValue;
        converted = "N/A".equals(converted) ? "" : converted;
        return converted;
    }
}
