package com.ruoyi.vehicle.service.impl;

import com.alibaba.fastjson2.JSON;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import com.ruoyi.system.api.enums.SysNoticeModel;
import com.ruoyi.system.api.model.LoginUser;
import com.ruoyi.vehicle.domain.*;
import com.ruoyi.vehicle.domain.dto.VehicleDto;
import com.ruoyi.vehicle.domain.vo.VehicleJsonKeyVo;
import com.ruoyi.vehicle.enums.VehicleLifecycleOperation;
import com.ruoyi.vehicle.mapper.*;
import com.ruoyi.vehicle.service.IFirstVehicleCheckService;
import com.ruoyi.vehicle.service.IVehicleInfoService;
import com.ruoyi.vehicle.service.IVehicleValidationService;
import com.ruoyi.vehicle.utils.ExcelUtil;
import com.ruoyi.vehicle.utils.JsonDictConverter;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service("vehicleInfoService")
public class VehicleInfoServiceImpl implements IVehicleInfoService {

    private static final Logger log = LoggerFactory.getLogger(VehicleInfoServiceImpl.class);

    @Autowired
    private ExcelUtil excelUtil;

    @Autowired
    private VehicleInfoMapper vehicleInfoMapper;

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
    private IFirstVehicleCheckService firstVehicleCheckService;

    @Autowired
    private MaterialBlacklistMapper materialBlacklistMapper;

    // @Lazy 打破循环依赖，同时保证拿到的是带事务代理的 self
    @Lazy
    @Autowired
    private VehicleInfoServiceImpl self;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /** SSE sink 注册表，taskId -> sink，与 VehicleTemplate 保持一致 */
    private final Map<String, Sinks.Many<ServerSentEvent<String>>> sinks = new ConcurrentHashMap<>();

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
            Map<String, Object> convertedMap = jsonDictConverter.convertJsonToMap(vehicle.getJson());
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
                        // 同一 dict_label 可能对应多条记录（每条记录有独立的 original_system 和 key_map）
                        // 使用 groupingBy 收集同一 dict_label 下的所有 SysDictData
                        Map<String, List<SysDictData>> dictLabelMap = dictResult.getData().stream()
                                .filter(d -> StringUtils.isNotBlank(d.getDictLabel()))
                                .collect(java.util.stream.Collectors.groupingBy(SysDictData::getDictLabel));

                        Map<String, Map<String, Object>> jsonDictMap = new LinkedHashMap<>();
                        for (String key : jsonMap.keySet()) {
                            List<SysDictData> dictDataList = dictLabelMap.get(key);
                            // 取第一条记录用于公共字段（cocOrder、valueConnection 各条相同）
                            SysDictData first = (dictDataList != null && !dictDataList.isEmpty()) ? dictDataList.get(0) : null;
                            Map<String, Object> labels = new HashMap<>();
                            labels.put("cocOrder", first != null ? first.getCocOrder() : null);
                            labels.put("valueConnection", first != null ? first.getValueConnection() : null);
                            // 将每条记录的 original_system -> key_map 收集为嵌套 Map 对象，
                            // 直接以 "originalSystemConnection" 为 key 存入 labels，
                            // 最终结构：{ "originalSystemConnection": { "认证系统__0": "xxx", ... } }
                            if (dictDataList != null) {
                                Map<String, String> systemKeyMap = new LinkedHashMap<>();
                                for (SysDictData dictData : dictDataList) {
                                    if (StringUtils.isNotBlank(dictData.getOriginalSystem())
                                            && StringUtils.isNotBlank(dictData.getKeyMap())) {
                                        systemKeyMap.put(dictData.getOriginalSystem(), dictData.getKeyMap());
                                    }
                                }
                                if (!systemKeyMap.isEmpty()) {
                                    labels.put("originalSystemConnection", systemKeyMap);
                                }
                            }
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
        List<SysDictData> colorDict = remoteDictService.getDictDataByType("color").getData();
        Map<String, String> colorMap = colorDict.stream().collect(Collectors.toMap(
                SysDictData::getDictValue,
                SysDictData::getDictLabel,
                (oldVal, newVal) -> oldVal
        ));
        if (vehicleInfo.getColors() != null && !vehicleInfo.getColors().isEmpty()) {
            List<String> colors = vehicleInfo.getColors();
            Set<String> targetLabels = colors.stream()
                    .map(colorMap::get)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            colors = colorMap.entrySet().stream()
                    .filter(entry -> targetLabels.contains(entry.getValue()))
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());
            vehicleInfo.setColors(colors);
        }
        if (vehicleInfo.getSecondaryColors() != null && !vehicleInfo.getSecondaryColors().isEmpty()) {
            List<String> secondaryColors = vehicleInfo.getSecondaryColors();
            Set<String> targetSecondaryLabels = secondaryColors.stream()
                    .map(colorMap::get)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            secondaryColors = colorMap.entrySet().stream()
                    .filter(entry -> targetSecondaryLabels.contains(entry.getValue()))
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());
            vehicleInfo.setSecondaryColors(secondaryColors);
        }
        List<VehicleInfo> list = vehicleInfoMapper.selectVehicleInfoList(vehicleInfo);
        for (VehicleInfo vehicle : list) {
            if (StringUtils.isNotBlank(vehicle.getJson())) {
                Map<String, Object> convertedMap = jsonDictConverter.convertJsonToMap(vehicle.getJson());
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
        VehicleInfo existing = vehicleInfoMapper.selectVehicleInfoByVin(vehicleInfo.getVin());
        if (existing != null) {
            vehicleInfo.setVehicleId(existing.getVehicleId());
        }

        // 查模板
        Material query = new Material();
        query.setMaterialNo(vehicleInfo.getMaterialNo());
        query.setStatus(0);
        List<Material> materialList = materialMapper.selectMaterialList(query);
        Long vehicleTemplateId;
        if (!materialList.isEmpty()) {
            Material material = materialList.get(0);
            vehicleTemplateId = material.getVehicleTemplateId();
            // 查模板详情，自动填充关联字段
            VehicleTemplate template = vehicleTemplateMapper.selectVehicleTemplateById(vehicleTemplateId);
            if (template == null) {
                throw new RuntimeException("模板不存在，templateId=" + vehicleTemplateId);
            }

            String json = "";
            try {
                JsonNode rootNode = objectMapper.readTree(template.getJson());
                // 扁平化并替换字段值
                replaceFieldValues(rootNode, material);
                json = objectMapper.writeValueAsString(rootNode);
            } catch (JsonProcessingException e) {
                throw new RuntimeException("动态参数替换失败");
            }
            vehicleInfo.setVehicleTemplateId(String.valueOf(vehicleTemplateId));
            vehicleInfo.setTvv(template.getTvv().replace(",", ""));
            vehicleInfo.setWvtaNo(template.getWvtaCocNo());
            vehicleInfo.setCocTemplateNo(template.getCocTemplateNo());
            vehicleInfo.setJson(json);
            vehicleInfo.setVehicleModel(material.getVehicleModel());
            vehicleInfo.setProjectName(material.getName());
            vehicleInfo.setBrand(material.getBrand());
            vehicleInfo.setTireResistanceGrade(material.getTireResistanceGrade());
            vehicleInfo.setSaleName(material.getSaleName());
            vehicleInfo.setWeight(material.getWeight());
            vehicleInfo.setTire(material.getTire());


            // 按物料号首台车逻辑覆盖 generate_affirm
            applyFirstVehicleAffirm(vehicleInfo, vehicleInfo.getMaterialNo(), String.valueOf(vehicleTemplateId));
        }
        vehicleInfo.setUploadStatus(0);
        vehicleInfo.setValidationResult(0);
        vehicleInfo.setDeleted(0);
        vehicleInfo.setCreateTime(vehicleInfo.getCreateTime() == null ? DateUtils.getNowDate() : vehicleInfo.getCreateTime());
        if (vehicleInfo.getIssueDate() == null) {
            vehicleInfo.setIssueDate(vehicleInfo.getCreateTime());
        }
        vehicleInfo.setCreateBy(SecurityUtils.getUsername() != null ? SecurityUtils.getUsername() : "MES To System");
        if (vehicleInfo.getVehicleId() != null) {
            deleteVehicleInfoByIds(new Long[]{vehicleInfo.getVehicleId()});
        }


        int insertRow = vehicleInfoMapper.insertVehicleInfo(vehicleInfo);
        log.info("[黑名单检查] 准备检查 vin={}, materialNo={}",
                vehicleInfo.getVin(), vehicleInfo.getMaterialNo());
        checkMaterialInBlacklist(vehicleInfo);

        // VehicleTemplate.json 已在模板导入阶段完成字段映射，直接使用
        VehicleLifecycle vehicleLifecycle = new VehicleLifecycle();
        vehicleLifecycle.setEntryId(vehicleInfo.getVehicleId());
        vehicleLifecycle.setTime(new Date());
        vehicleLifecycle.setVin(vehicleInfo.getVin());
        vehicleLifecycle.setOperate(VehicleLifecycleOperation.VEHICLE_INFO_CREATE.getOperation());
        vehicleLifecycle.setResult(0);
        vehicleLifecycleMapper.insert(vehicleLifecycle);

        self.validateVehicleInfo(Collections.singletonList(vehicleInfo.getVehicleId()));
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
            params.put("id", String.valueOf(vehicleInfo.getVehicleId()));
            params.put("vin", vehicleInfo.getVin());
            params.put("vehicleModel", vehicleInfo.getVehicleModel());
            params.put("factoryCode", vehicleInfo.getFactoryCode());
            params.put("country", vehicleInfo.getCountry());
            if (vehicleInfo.getIssueDate() != null) {
                params.put("issueDate", DateUtils.parseDateToStr("yyyy-MM-dd HH:mm:ss", vehicleInfo.getIssueDate()));
            }
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

        if (Integer.valueOf(0).equals(vehicleInfo.getGenerateAffirm())) {
            String sb =
                    "物料号 " +
                            vehicleInfo.getMaterialNo() +
                            " 生成待确认" +
                            System.lineSeparator();
            Map<String, String> params = new HashMap<>();
            params.put("id", String.valueOf(vehicleInfo.getVehicleId()));
            params.put("vin", vehicleInfo.getVin());
            params.put("vehicleModel", vehicleInfo.getVehicleModel());
            params.put("factoryCode", vehicleInfo.getFactoryCode());
            params.put("country", vehicleInfo.getCountry());
            if (vehicleInfo.getIssueDate() != null) {
                params.put("issueDate", DateUtils.parseDateToStr("yyyy-MM-dd HH:mm:ss", vehicleInfo.getIssueDate()));
            }
            params.put("materialNo", vehicleInfo.getMaterialNo());
            SysNotice sysNotice = new SysNotice();
            sysNotice.setModel(SysNoticeModel.FIRST_VEHICLE_GENERATE_AFFIRM.getModel());
            sysNotice.setQueryParams(JSON.toJSONString(params));
            sysNotice.setIsRead(false);
            sysNotice.setStatus("0");
            sysNotice.setNoticeType("1");
            sysNotice.setNoticeTitle("首台车生成待确认");
            sysNotice.setNoticeContent(sb);
            sysNotice.setCreateBy("自动提醒");
            sysNotice.setCreateTime(new Date());
            sysNotice.setSorts(Arrays.asList(18, 19));
            remoteNoticeService.innerAdd(sysNotice);
        }

        if (Integer.valueOf(0).equals(vehicleInfo.getUploadAffirm())) {
            String sb =
                    "物料号 " +
                            vehicleInfo.getMaterialNo() +
                            " 上传待确认" +
                            System.lineSeparator();
            Map<String, String> params = new HashMap<>();
            params.put("id", String.valueOf(vehicleInfo.getVehicleId()));
            params.put("vin", vehicleInfo.getVin());
            params.put("vehicleModel", vehicleInfo.getVehicleModel());
            params.put("factoryCode", vehicleInfo.getFactoryCode());
            params.put("country", vehicleInfo.getCountry());
            if (vehicleInfo.getIssueDate() != null) {
                params.put("issueDate", DateUtils.parseDateToStr("yyyy-MM-dd HH:mm:ss", vehicleInfo.getIssueDate()));
            }
            params.put("materialNo", vehicleInfo.getMaterialNo());
            SysNotice sysNotice = new SysNotice();
            sysNotice.setModel(SysNoticeModel.FIRST_VEHICLE_UPLOAD_AFFIRM.getModel());
            sysNotice.setQueryParams(JSON.toJSONString(params));
            sysNotice.setIsRead(false);
            sysNotice.setStatus("0");
            sysNotice.setNoticeType("1");
            sysNotice.setNoticeTitle("首台车上传待确认");
            sysNotice.setNoticeContent(sb);
            sysNotice.setCreateBy("自动提醒");
            sysNotice.setCreateTime(new Date());
            sysNotice.setSorts(Arrays.asList(20, 21));
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
            material.setStatus(0);
            List<Material> materialList = materialMapper.selectMaterialList(material);
            Long vehicleTemplateId;
            if (materialList.isEmpty()) {
                throw new RuntimeException("该物料号、品牌、重量、销售名称、轮胎无对应的可用车辆模板");
            } else {
                vehicleTemplateId = materialList.get(0).getVehicleTemplateId();
            }
            VehicleTemplate template = vehicleTemplateMapper.selectVehicleTemplateById(vehicleTemplateId);
            if (template == null) {
                throw new RuntimeException("模板不存在，templateId=" + vehicleTemplateId);
            }
            vehicleInfo.setVehicleTemplateId(String.valueOf(vehicleTemplateId));
            vehicleInfo.setTvv(template.getTvv().replace(",", ""));
            vehicleInfo.setWvtaNo(template.getWvtaCocNo());
            vehicleInfo.setCocTemplateNo(template.getCocTemplateNo());
            // VehicleTemplate.json 已在导入阶段完成字段映射，直接使用，无需再次转换
            vehicleInfo.setJson(template.getJson());
            // 判断首台车原因并写入 affirmCause
            applyFirstVehicleAffirmOnUpdate(vehicleInfo, vehicleInfo.getMaterialNo(), String.valueOf(vehicleTemplateId));
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
            ValidationReport validationReport = vehicleValidationService.validate(
                    vehicleInfo.getJson(), vehicleInfo.getVehicleModel(), null, isFullyElectricFromJson(vehicleInfo.getJson()), true);
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
            params.put("id", String.valueOf(vehicleInfo.getVehicleId()));
            params.put("vin", vehicleInfo.getVin());
            params.put("vehicleModel", vehicleInfo.getVehicleModel());
            params.put("factoryCode", vehicleInfo.getFactoryCode());
            params.put("country", vehicleInfo.getCountry());
            if (vehicleInfo.getIssueDate() != null) {
                params.put("issueDate", DateUtils.parseDateToStr("yyyy-MM-dd HH:mm:ss", vehicleInfo.getIssueDate()));
            }
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

    /**
     * 从 JSON 字符串中提取 EnergySource 字段，判断车辆是否为纯电动：
     * 按 "|" 分隔后所有分段均为 "95" 才返回 true，存在非 "95" 分段返回 false；
     * EnergySource 缺失/为空，或 JSON 解析失败，返回 null（表示无法判定，
     * 调用方按未传入处理，由 vehicleValidationService 内部走原有的自行计算兜底逻辑）。
     */
    private Boolean isFullyElectricFromJson(String json) {
        if (StringUtils.isBlank(json)) return null;
        try {
            Map<String, Object> map = objectMapper.readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            Object energySourceObj = map.get("EnergySource");
            if (energySourceObj == null) return null;
            String energySource = energySourceObj.toString().trim();
            if (energySource.isEmpty()) return null;
            for (String seg : energySource.split("\\|")) {
                if (!"95".equals(seg.trim())) return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("解析 JSON 获取 EnergySource 失败", e);
            return null;
        }
    }

    @Override
    public Map<String, Object> getVehicleInfoFromMes(VehicleDto.Vehicle vehicle, Date now, LoginUser loginUser) throws JsonProcessingException {
        Set<String> permissions = loginUser.getPermissions();
        if (!permissions.contains("vehicle:info:toSystem")) {
            throw new ServiceException("没有权限执行此操作");
        }

        VehicleInfo vehicleInfo = new VehicleInfo();
        BeanUtils.copyProperties(vehicle, vehicleInfo);
        vehicleInfo.setCustomerNo(vehicle.getCustomerNumber());
        vehicleInfo.setCreateTime(now);

        vehicleInfo.setColor(vehicleInfo.getColor().substring(0, 2));

        // 复合色拆分映射表从字典中查询：dict_label=色码(Z3)，dict_value=主色,副色(UU,CP)
        Map<String, String[]> compositeColorMap = remoteDictService
                .getDictDataByType("color_composite")
                .getData().stream()
                .collect(Collectors.toMap(
                        SysDictData::getDictLabel,
                        d -> d.getDictValue().split(",", 2),
                        (k1, k2) -> k1));
        String[] composite = compositeColorMap.get(vehicleInfo.getColor());
        if (composite != null) {
            vehicleInfo.setColor(composite[0].trim());
            vehicleInfo.setSecondaryColor(composite[1].trim());
        } else {
            vehicleInfo.setColor(vehicleInfo.getColor());
        }
        if (vehicleInfo.getColor() == null) {
            throw new RuntimeException("颜色代码不存在");
        }

        try {
            if (vehicleInfo.getIssueDate() == null) {
                vehicleInfo.setIssueDate(now);
            }
            self.insertVehicleInfo(vehicleInfo);
        } catch (Exception e) {
            Throwable cause = e;
            while (cause.getCause() != null && cause.getMessage() == null) {
                cause = cause.getCause();
            }
            throw new RuntimeException(cause.getMessage() != null ? cause.getMessage() : e.toString(), e);
        }

        firstVehicleCheckService.handleAfterInsert(Collections.singletonList(vehicleInfo));

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

    /**
     * Excel 导入入口（异步 SSE 模式）：
     * 提交任务后立即返回 taskId，前端通过 getImportFlux 订阅进度推送，
     * 与 VehicleTemplate 的导入反馈机制保持一致。
     */
    @Override
    public String submitImportTask(MultipartFile file) {
        String taskId = com.ruoyi.common.core.utils.uuid.UUID.randomUUID().toString();
        Sinks.Many<ServerSentEvent<String>> sink = Sinks.many().unicast().onBackpressureBuffer();
        sinks.put(taskId, sink);

        // 在请求线程提前读取文件内容和用户信息，避免异步线程中上下文丢失
        byte[] fileBytes;
        try {
            fileBytes = file.getBytes();
        } catch (Exception e) {
            sinks.remove(taskId);
            throw new com.ruoyi.common.core.exception.ServiceException("文件读取失败: " + e.getMessage());
        }
        String currentUser = SecurityUtils.getUsername();
        String lang = excelUtil.resolveCurrentLang();

        CompletableFuture.runAsync(() -> doImportVehicleInfo(taskId, fileBytes, currentUser, lang));
        return taskId;
    }

    /**
     * 订阅导入进度 SSE 流。
     */
    @Override
    public Flux<ServerSentEvent<String>> getImportFlux(String taskId) {
        Sinks.Many<ServerSentEvent<String>> sink = sinks.get(taskId);
        if (sink == null) {
            return Flux.just(ServerSentEvent.<String>builder()
                    .event("error")
                    .data("{\"message\":\"任务不存在或已过期\"}")
                    .build());
        }
        return sink.asFlux()
                .doOnCancel(() -> {
                    log.info("客户端断开连接, taskId={}", taskId);
                    sinks.remove(taskId);
                })
                .doOnComplete(() -> {
                    log.info("SSE 完成, taskId={}", taskId);
                    sinks.remove(taskId);
                });
    }

    /**
     * 实际导入逻辑，在异步线程中执行。
     * 每行独立事务（REQUIRES_NEW）：锁及时释放，避免大事务死锁。
     * 逐行通过 SSE 推送 progress/complete/error 事件，与 VehicleTemplate 保持一致。
     */
    private void doImportVehicleInfo(String taskId, byte[] fileBytes, String createBy, String lang) {
        Sinks.Many<ServerSentEvent<String>> sink = sinks.get(taskId);
        if (sink == null) return;

        int successCount = 0;
        int failCount = 0;
        List<String> errorDetails = new ArrayList<>();
        List<VehicleInfo> importedList = new ArrayList<>();

        try {
            List<VehicleInfo> vehicleInfoList = excelUtil.importExcel(
                    new java.io.ByteArrayInputStream(fileBytes),
                    "vehicle_info",
                    VehicleInfo.class,
                    lang,
                    2   // 跳过列头后的 2 行（原逻辑从 rowIndex=3 开始）
            );

            if (vehicleInfoList.isEmpty()) {
                pushEvent(sink, "error", "{\"message\":\"Excel中没有数据行\"}");
                sink.tryEmitComplete();
                sinks.remove(taskId);
                return;
            }

            int total = vehicleInfoList.size();

            for (int i = 0; i < total; i++) {
                int rowNum = i + 4; // 与原逻辑 excelRowNum 保持一致
                VehicleInfo vehicleInfo = vehicleInfoList.get(i);
                String vin = vehicleInfo.getVin();

                List<String> missingFields = new ArrayList<>();
                if (StringUtils.isBlank(vehicleInfo.getVin()))          missingFields.add("VIN");
                if (StringUtils.isBlank(vehicleInfo.getMaterialNo()))   missingFields.add("Material No");
                if (StringUtils.isBlank(vehicleInfo.getFactoryCode()))  missingFields.add("Factory Code");
                if (StringUtils.isBlank(vehicleInfo.getColor()))        missingFields.add("Color");
                if (StringUtils.isBlank(vehicleInfo.getCountry()))      missingFields.add("Country");
                if (vehicleInfo.getManufactureDate() == null)           missingFields.add("Manufacture Date");

                if (!missingFields.isEmpty()) {
                    failCount++;
                    String reason = String.join("、", missingFields) + " 不能为空";
                    errorDetails.add("第" + rowNum + "行：" + reason);
                    pushEvent(sink, "progress", String.format(
                            "{\"row\":%d,\"total\":%d,\"status\":\"fail\",\"reason\":\"%s\"}",
                            rowNum, total, escapeJson(reason)));
                    continue;
                }

                try {
                    // 1. 先用 dict_label 查出原始色码（如 "Z3"）
                    vehicleInfo.setColor(vehicleInfo.getColor().substring(0, 2));
                    String color = vehicleInfo.getColor().substring(0, 2);

                    // 2. 查复合色字典，判断是否需要拆分主色和副色（dict_label=Z3，dict_value=UU,CP）
                    Map<String, String> compositeColorMap = remoteDictService
                            .getDictDataByType("color_composite")
                            .getData().stream()
                            .collect(Collectors.toMap(
                                    SysDictData::getDictLabel,
                                    SysDictData::getDictValue,
                                    (k1, k2) -> k1));
                    String compositeValue = compositeColorMap.get(vehicleInfo.getColor());
                    if (compositeValue != null) {
                        // 复合色：dict_value 格式为 "主色,副色"，如 "UU,CP"
                        String[] parts = compositeValue.split(",", 2);
                        vehicleInfo.setColor(parts[0].trim());
                        vehicleInfo.setSecondaryColor(parts[1].trim());
                    } else {
                        // 单色：直接使用原始色码
                        vehicleInfo.setColor(vehicleInfo.getColor());
                    }

                    // VIN 已存在则覆盖
                    VehicleInfo existing = vehicleInfoMapper.selectVehicleInfoByVin(vin);
                    if (existing != null) {
                        deleteVehicleInfoByIds(new Long[]{existing.getVehicleId()});
                        log.info("导入覆盖：VIN[{}] 原记录已删除", vin);
                    }

                    // 查物料
                    Material query = new Material();
                    query.setMaterialNo(vehicleInfo.getMaterialNo());
                    query.setStatus(0);
                    List<Material> materialList = materialMapper.selectMaterialList(query);
                    if (!materialList.isEmpty()) {
                        Long templateId = materialList.get(0).getVehicleTemplateId();

                        // 查模板
                        VehicleTemplate template = vehicleTemplateMapper.selectVehicleTemplateById(templateId);
                        if (template == null) {
                            throw new IllegalArgumentException("模板ID[" + templateId + "]不存在");
                        }

                        vehicleInfo.setCreateBy(createBy);
                        vehicleInfo.setVehicleModel(materialList.get(0).getVehicleModel());

                        // 手动写入mes中传进来的值
                        Map<String, Object> excelMap = new LinkedHashMap<>();
                        // VehicleIdentificationNumber
                        excelMap.put("vin", vehicleInfo.getVin());
                        // DateManufactureVehicle
                        excelMap.put("manufactureDate", com.alibaba.fastjson2.util.DateUtils.format(vehicleInfo.getManufactureDate(), "dd/MM/yyyy"));
                        // SignatureDate
                        if (vehicleInfo.getIssueDate() != null) {
                            excelMap.put("issueDate", com.alibaba.fastjson2.util.DateUtils.format(vehicleInfo.getIssueDate(), "dd/MM/yyyy"));
                        }
                        // CommercialName
//                        excelMap.put("customerNo", vehicleInfo.getCustomerNo());
                        // Colour
                        excelMap.put("color", color);
                        excelMap.put("country", vehicleInfo.getCountry());
                        String convertedMesJson = jsonConvertFromMesExcel(excelMap);

                        log.info("[导入前] vin={}, templateId={}", vehicleInfo.getVin(),
                                materialList.get(0).getVehicleTemplateId());
                        // 每行独立事务插入
                        self.insertSingleVehicleInfoRow(vehicleInfo, template, materialList.get(0), convertedMesJson);
                        importedList.add(vehicleInfo);
                        successCount++;

                        pushEvent(sink, "progress", String.format(
                                "{\"row\":%d,\"total\":%d,\"status\":\"success\"}", rowNum, total));
                    } else {
                        vehicleInfo.setCreateBy(createBy);

                        if (vehicleInfo.getIssueDate() == null) {
                            vehicleInfo.setIssueDate(new Date());
                        }
                        vehicleInfoMapper.insertVehicleInfo(vehicleInfo);
                        checkMaterialInBlacklist(vehicleInfo);
                        importedList.add(vehicleInfo);
                        successCount++;
                        pushEvent(sink, "progress", String.format(
                                "{\"row\":%d,\"total\":%d,\"status\":\"success\"}", rowNum, total));
                    }
                } catch (Exception rowEx) {
                    failCount++;
                    String errorMsg = String.format("第%d行: %s", rowNum, rowEx.getMessage());
                    errorDetails.add(errorMsg);
                    log.warn("导入第{}行失败: {}", rowNum, rowEx.getMessage());

                    pushEvent(sink, "progress", String.format(
                            "{\"row\":%d,\"total\":%d,\"status\":\"fail\",\"reason\":\"%s\"}",
                            rowNum, total, escapeJson(rowEx.getMessage())));
                }
            }

        } catch (Exception e) {
            log.error("文件导入失败, taskId={}", taskId, e);
            pushEvent(sink, "error", String.format(
                    "{\"message\":\"文件解析失败: %s\"}", escapeJson(e.getMessage())));
            sink.tryEmitComplete();
            sinks.remove(taskId);
            return;
        }

        // 所有行处理完后，批量触发首台车打标
        if (!importedList.isEmpty()) {
            try {
                log.info("[导入后] 触发 handleAfterInsert, importedList size={}", importedList.size());
                importedList.forEach(v -> log.info("[导入后] vin={}, templateId={}, upload_affirm={}",
                        v.getVin(), v.getVehicleTemplateId(), v.getUploadAffirm()));
                firstVehicleCheckService.handleAfterInsert(importedList);
            } catch (Exception e) {
                log.error("首台车打标失败, taskId={}", taskId, e);
            }
        }

        String detailJson = errorDetails.stream()
                .map(s -> "\"" + escapeJson(s) + "\"")
                .collect(Collectors.joining(",", "[", "]"));

        pushEvent(sink, "complete", String.format(
                "{\"successCount\":%d,\"failCount\":%d,\"errorDetails\":%s}",
                successCount, failCount, detailJson));

        sink.tryEmitComplete();
        sinks.remove(taskId);
    }

    /**
     * 单行独立事务：REQUIRES_NEW 保证每行提交后立即释放锁，
     * 与其他并发导入事务不形成循环等待，根治死锁问题。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void insertSingleVehicleInfoRow(VehicleInfo vehicleInfo, VehicleTemplate template, Material material, String convertedMesJson) {
        String vin = vehicleInfo.getVin();

        // 补充模板关联字段
        vehicleInfo.setTvv(template.getTvv().replace(",", ""));
        vehicleInfo.setWvtaNo(template.getWvtaCocNo());
        vehicleInfo.setCocTemplateNo(template.getCocTemplateNo());
        // 只保留 vehicle_attribute 字典中 dict_label 对应的键，其余键删除
        vehicleInfo.setJson(filterJsonByVehicleAttribute(template.getJson()));
        try {
            JsonNode rootNode = objectMapper.readTree(vehicleInfo.getJson());
            // 扁平化并替换字段值
            replaceFieldValues(rootNode, material);
            vehicleInfo.setJson(objectMapper.writeValueAsString(rootNode));

            // 将 MES 转换结果覆盖合并进模板 JSON：
            // MES 字段的 key 已为 dict_label，与模板 JSON 的 key 一致，直接覆盖对应键的值。
            if (StringUtils.isNotBlank(convertedMesJson)) {
                try {
                    Map<String, Object> baseMap = objectMapper.readValue(
                            vehicleInfo.getJson(),
                            new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                    Map<String, Object> mesMap = objectMapper.readValue(
                            convertedMesJson,
                            new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                    baseMap.putAll(mesMap);
                    vehicleInfo.setJson(objectMapper.writeValueAsString(baseMap));
                } catch (Exception mergeEx) {
                    log.warn("[insertSingleVehicleInfoRow] MES 字段合并失败，vin={}, 跳过合并", vin, mergeEx);
                }
            }
        } catch (JsonProcessingException e) {
            throw new RuntimeException("动态参数替换失败");
        }
        vehicleInfo.setVehicleTemplateId(String.valueOf(template.getTemplateId()));

        // 补充系统字段
        vehicleInfo.setUploadStatus(0);
        vehicleInfo.setValidationResult(0);
        vehicleInfo.setDeleted(0);
        // 按物料号/模版首台车逻辑覆盖 generate_affirm / upload_affirm
        applyFirstVehicleAffirm(vehicleInfo, vehicleInfo.getMaterialNo(), String.valueOf(template.getTemplateId()));
        vehicleInfo.setVehicleModel(material.getVehicleModel());
        vehicleInfo.setProjectName(material.getName());
        vehicleInfo.setBrand(material.getBrand());
        vehicleInfo.setTireResistanceGrade(material.getTireResistanceGrade());
        vehicleInfo.setSaleName(material.getSaleName());
        vehicleInfo.setWeight(material.getWeight());
        vehicleInfo.setTire(material.getTire());
        if (StringUtils.isBlank(vehicleInfo.getCreateBy())) {
            vehicleInfo.setCreateBy(SecurityUtils.getUsername() != null ? SecurityUtils.getUsername() : "Import");
        }
        vehicleInfo.setCreateTime(DateUtils.getNowDate());

        // 发证日期为空则用创建时间代替
        if (vehicleInfo.getIssueDate() == null) {
            vehicleInfo.setIssueDate(vehicleInfo.getCreateTime());
        }
        vehicleInfoMapper.insertVehicleInfo(vehicleInfo);
        checkMaterialInBlacklist(vehicleInfo);

        VehicleLifecycle lifecycle = new VehicleLifecycle();
        lifecycle.setTime(new Date());
        lifecycle.setVin(vin);
        lifecycle.setOperate(VehicleLifecycleOperation.VEHICLE_INFO_CREATE.getOperation());
        lifecycle.setResult(0);
        vehicleLifecycleMapper.insert(lifecycle);

        validateVehicleInfo(Collections.singletonList(vehicleInfo.getVehicleId()));
    }

    /**
     * 批量修改关联模版
     * 规则：所选车辆必须属于同一整车物料号（material_no），否则拒绝操作
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int batchUpdateVehicleTemplate(Map<Long, Long> vehicleUpdateTemplateIds) {
        if (vehicleUpdateTemplateIds == null || vehicleUpdateTemplateIds.isEmpty()) {
            throw new ServiceException("请选择需要修改的车辆");
        }

        Long[] idArray = vehicleUpdateTemplateIds.keySet().toArray(new Long[0]);
        List<VehicleInfo> vehicleList = vehicleInfoMapper.selectVehicleInfoByIds(idArray);
        if (vehicleList.isEmpty()) {
            throw new ServiceException("未找到对应的车辆信息");
        }

        // 2. 校验所有车辆必须属于同一整车物料号
        Set<String> materialNos = vehicleList.stream()
                .map(VehicleInfo::getMaterialNo)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toSet());
        if (materialNos.size() > 1) {
            throw new ServiceException("批量修改关联模版只允许操作同一整车物料号下的车辆，"
                    + "当前选中了 " + materialNos.size() + " 种不同物料号：" + materialNos);
        }
        if (materialNos.isEmpty()) {
            throw new ServiceException("所选车辆物料号为空，无法执行批量修改");
        }

        // 3. 预校验所有 templateId 合法性，避免更新到一半报错
        Map<Long, VehicleTemplate> templateCache = new HashMap<>();
        for (Long templateId : new HashSet<>(vehicleUpdateTemplateIds.values())) {
            VehicleTemplate template = vehicleTemplateMapper.selectVehicleTemplateById(templateId);
            if (template == null) {
                throw new ServiceException("目标模版不存在，templateId=" + templateId);
            }
            if (!"0".equals(template.getStatus())) {
                throw new ServiceException("目标模版已停用，请选择启用状态的模版，templateId=" + templateId);
            }
            templateCache.put(templateId, template);
        }

        // 4. 按vehicleId找到对应车辆和模版逐条更新
        Map<Long, VehicleInfo> vehicleMap = vehicleList.stream()
                .collect(Collectors.toMap(VehicleInfo::getVehicleId, v -> v));

        String operator = SecurityUtils.getUsername();
        List<String> failVins = new ArrayList<>();
        int successCount = 0;

        for (Map.Entry<Long, Long> entry : vehicleUpdateTemplateIds.entrySet()) {
            Long vehicleId = entry.getKey();
            Long templateId = entry.getValue();
            VehicleInfo vehicle = vehicleMap.get(vehicleId);
            VehicleTemplate template = templateCache.get(templateId);

            if (vehicle == null) {
                failVins.add(String.valueOf(vehicleId));
                continue;
            }

            try {
                vehicle.setVehicleTemplateId(String.valueOf(templateId));
                vehicle.setWvtaNo(template.getWvtaCocNo());
                vehicle.setCocTemplateNo(template.getCocTemplateNo());
                vehicle.setJson(template.getJson());
                vehicle.setUpdateBy(operator);
                vehicle.setUpdateTime(DateUtils.getNowDate());
                vehicle.setValidationResult(0);
                vehicle.setUploadStatus(0);

                vehicleInfoMapper.updateVehicleInfo(vehicle);
                successCount++;
            } catch (Exception e) {
                log.error("批量修改模版：vehicleId={} 更新失败", vehicleId, e);
                failVins.add(String.valueOf(vehicleId));
            }
        }

        // 5. 批量触发校验
        List<Long> updatedIds = vehicleList.stream()
                .map(VehicleInfo::getVehicleId)
                .filter(id -> !failVins.contains(String.valueOf(id)))
                .collect(Collectors.toList());
        if (!updatedIds.isEmpty()) {
            validateVehicleInfo(updatedIds);
        }

        if (!failVins.isEmpty()) {
            throw new ServiceException("部分车辆模版更新失败，vehicleId=" + failVins
                    + "，已成功更新 " + successCount + " 条");
        }

        return successCount;
    }

    /**
     *  根据传入的 ids 查询所有车辆 JSON 键的并集，关联 vehicle_attribute 字典信息后返回
     */
    @Override
    public List<VehicleJsonKeyVo> listJsonKeysByVehicleIds(List<Long> vehicleIds) {
        if (vehicleIds == null || vehicleIds.isEmpty()) {
            throw new ServiceException("请传入车辆ID列表");
        }

        // 1. 批量查出车辆，收集 JSON 键并集（保持键首次出现的顺序）
        Long[] idArray = vehicleIds.toArray(new Long[0]);
        List<VehicleInfo> vehicleList = vehicleInfoMapper.selectVehicleInfoByIds(idArray);

        Set<String> materialNos = vehicleList.stream()
                .map(VehicleInfo::getMaterialNo)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toSet());
        if (materialNos.size() > 1) {
            throw new ServiceException("批量修改关联模版只允许操作同一整车物料号下的车辆，"
                    + "当前选中了 " + materialNos.size() + " 种不同物料号：" + materialNos);
        }
        if (materialNos.isEmpty()) {
            throw new ServiceException("所选车辆物料号为空，无法执行批量修改");
        }

        // 使用 LinkedHashSet 保证顺序同时去重
        Set<String> keyUnion = new LinkedHashSet<>();
        for (VehicleInfo vehicle : vehicleList) {
            if (StringUtils.isBlank(vehicle.getJson())) {
                continue;
            }
            try {
                Map<String, Object> jsonMap = objectMapper.readValue(
                        vehicle.getJson(),
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                keyUnion.addAll(jsonMap.keySet());
            } catch (Exception e) {
                log.warn("解析 vehicle JSON 失败, vehicleId={}", vehicle.getVehicleId(), e);
            }
        }

        if (keyUnion.isEmpty()) {
            return Collections.emptyList();
        }

        // 2. 拉取 vehicle_attribute 字典，构建 dictLabel -> SysDictData 映射
        Map<String, SysDictData> dictLabelMap = Collections.emptyMap();
        try {
            com.ruoyi.common.core.domain.R<List<SysDictData>> dictResult =
                    remoteDictService.getDictDataByType("vehicle_attribute");
            if (dictResult != null && dictResult.getData() != null) {
                dictLabelMap = dictResult.getData().stream()
                        .filter(d -> StringUtils.isNotBlank(d.getDictLabel()))
                        .collect(java.util.stream.Collectors.toMap(
                                SysDictData::getDictLabel,
                                d -> d,
                                (existing, replacement) -> existing
                        ));
            }
        } catch (Exception e) {
            log.warn("获取 vehicle_attribute 字典失败", e);
        }

        // 3. 组装 VO 列表
        List<VehicleJsonKeyVo> result = new ArrayList<>(keyUnion.size());
        for (String key : keyUnion) {
            SysDictData dictData = dictLabelMap.get(key);
            result.add(new VehicleJsonKeyVo(
                    key,
                    dictData != null ? dictData.getOtherLabel()       : null,
                    dictData != null ? dictData.getOtherLabelSystem() : null,
                    dictData != null ? dictData.getCocOrder()         : null
            ));
        }
        return result;
    }

    /**
     * 根据传入的 ids 和 fieldValues，
     * 将每辆车 JSON 中与 fieldValues.key 相同的键的值替换为对应新值，保存回数据库
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int batchUpdateVehicleJsonFields(List<Long> vehicleIds, Map<String, String> fieldValues) {
        if (vehicleIds == null || vehicleIds.isEmpty()) {
            throw new ServiceException("请传入车辆ID列表");
        }
        if (fieldValues == null || fieldValues.isEmpty()) {
            throw new ServiceException("请传入需要修改的字段映射");
        }

        // 1. 批量查出车辆
        Long[] idArray = vehicleIds.toArray(new Long[0]);
        List<VehicleInfo> vehicleList = vehicleInfoMapper.selectVehicleInfoByIds(idArray);
        if (vehicleList.isEmpty()) {
            throw new ServiceException("未找到对应的车辆信息");
        }

        String operator = SecurityUtils.getUsername();
        int successCount = 0;
        List<String> failedVehicleIds = new ArrayList<>();

        for (VehicleInfo vehicle : vehicleList) {
            try {
                String originalJson = vehicle.getJson();

                // JSON 为空时跳过（不报错）
                if (StringUtils.isBlank(originalJson)) {
                    log.warn("vehicleId={} 的 JSON 为空，跳过字段更新", vehicle.getVehicleId());
                    continue;
                }

                // 2. 反序列化 JSON
                Map<String, Object> jsonMap = objectMapper.readValue(
                        originalJson,
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});

                // 3. 只替换 JSON 中已存在的键；不存在的键忽略（不新增）
                boolean changed = false;
                for (Map.Entry<String, String> entry : fieldValues.entrySet()) {
                    if (jsonMap.containsKey(entry.getKey())) {
                        jsonMap.put(entry.getKey(), entry.getValue());
                        changed = true;
                    }
                }

                if (!changed) {
                    // 该车辆 JSON 中没有任何匹配的键，无需更新
                    continue;
                }

                // 4. 序列化回 JSON 字符串
                String newJson = objectMapper.writeValueAsString(jsonMap);

                // 5. 更新数据库（只改 json、update_by、update_time，不触碰其他字段）
                VehicleInfo update = new VehicleInfo();
                update.setVehicleId(vehicle.getVehicleId());
                update.setJson(newJson);
                update.setVin(null);   // 防止误更新 vin
                update.setUpdateBy(operator);
                update.setUpdateTime(DateUtils.getNowDate());

                vehicleInfoMapper.updateVehicleInfo(update);
                successCount++;

            } catch (Exception e) {
                log.error("批量修改 JSON 字段：vehicleId={} 处理失败", vehicle.getVehicleId(), e);
                failedVehicleIds.add(String.valueOf(vehicle.getVehicleId()));
            }
        }

        // 6. 对成功更新的车辆触发重新校验
        if (successCount > 0) {
            List<Long> updatedIds = vehicleList.stream()
                    .map(VehicleInfo::getVehicleId)
                    .filter(id -> !failedVehicleIds.contains(String.valueOf(id)))
                    .collect(java.util.stream.Collectors.toList());
            validateVehicleInfo(updatedIds);
        }

        if (!failedVehicleIds.isEmpty()) {
            throw new ServiceException("部分车辆 JSON 字段更新失败，vehicleId=" + failedVehicleIds
                    + "，已成功更新 " + successCount + " 辆");
        }

        return successCount;
    }

    @Override
    public Map<String, List<Map<String, Object>>> getTemplateVersion(List<Long> vehicleIds) {
        List<VehicleInfo> vehicleInfoList = vehicleInfoMapper.selectVehicleInfoByIds(vehicleIds.toArray(new Long[0]));
        if (vehicleInfoList.isEmpty()) {
            throw new ServiceException("请传入车辆ID列表");
        }
        List<String> materialNoList = vehicleInfoList.stream()
                .map(VehicleInfo::getMaterialNo)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .collect(Collectors.toList());

        Map<String, VehicleInfo> vinToVehicleInfo = vehicleInfoList.stream()
                .collect(Collectors.toMap(VehicleInfo::getVin, v -> v, (a, b) -> a));

        List<Map<String, Object>> mapList = vehicleInfoMapper.selectOldVersionAndNewVersion(materialNoList)
                .stream()
                .map(m -> {
                    LinkedHashMap<String, Object> newMap = new LinkedHashMap<>(m);
                    // 假设 vehicleId 可以从某个地方获取，比如根据 vin 查询
                    String vin = (String) m.get("vin");
                    Long vehicleId = vinToVehicleInfo.get(vin).getVehicleId();
                    newMap.put("vehicleId", vehicleId);
                    return newMap;
                })
                .collect(Collectors.toList());

        for (Map<String, Object> map : mapList) {
            String vin = (String) map.get("vin");
            VehicleInfo vehicleInfo = vinToVehicleInfo.get(vin);
            if (vehicleInfo != null && vehicleInfo.getVehicleTemplateId() != null) {
                VehicleTemplate template = vehicleTemplateMapper.selectVehicleTemplateById(Long.valueOf(vehicleInfo.getVehicleTemplateId()));
                if (template != null) {
                    VehicleTemplate query = new VehicleTemplate();
                    query.setUuid(template.getUuid());
                    query.setIsLast(0);
                    List<VehicleTemplate> vehicleTemplateList = vehicleTemplateMapper.selectVehicleTemplateList(query);
                    Map<String, Object> versionMap = new LinkedHashMap<>();
                    for (VehicleTemplate vehicleTemplate : vehicleTemplateList) {
                        versionMap.put(vehicleTemplate.getWvtaCocNo() + "(" + vehicleTemplate.getVersion() + ")", vehicleTemplate.getTemplateId());
                    }
                    map.put("versions", versionMap);
                }
            }
        }

        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        for (String materialNo : materialNoList) {
            Set<String> materialVins = vehicleInfoList.stream()
                    .filter(v -> materialNo.equals(v.getMaterialNo()))
                    .map(VehicleInfo::getVin)
                    .collect(Collectors.toSet());
            List<Map<String, Object>> materialMapList = mapList.stream()
                    .filter(m -> materialVins.contains((String) m.get("vin")))
                    .collect(Collectors.toList());
            result.put(materialNo, materialMapList);
        }

        return result;
    }

    @Override
    public List<VehicleInfo> listFirstVehicleUnconfirmed(VehicleInfo vehicleInfo, String dimension) {
        List<VehicleInfo> list = vehicleInfoMapper.listFirstVehicleUnconfirmed(vehicleInfo, dimension);
        return list;
    }

    @Override
    public List<VehicleInfo> selectVehicleInfoByIds(Long[] vehicleIds) {
        List<VehicleInfo> snapshot = vehicleInfoMapper.selectVehicleInfoByIds(vehicleIds);
        return snapshot;
    }

    public int updateVehicleTemplateId(String vin, Long templateId) {
        return vehicleTemplateMapper.updateVehicleTemplateId(vin, templateId);
    }

    @Override
    public List<VehicleInfo> listAllFirstVehicleUnconfirmed() {
        // 物料号维度：first_material_flag=1 AND generate_affirm=0，每个 material_no 取制造日期最早一条
        List<VehicleInfo> materialList = vehicleInfoMapper.listFirstMaterialUnconfirmedEarliest();

        // 模版维度：first_template_flag=1 AND upload_affirm=0，每个 vehicle_template_id 取制造日期最早一条
        List<VehicleInfo> templateList = vehicleInfoMapper.listFirstTemplateUnconfirmedEarliest();

        // 按 vehicle_id 去重合并（同一辆车可能同时出现在两个维度）
        // 物料号维度优先放入，模版维度用 putIfAbsent 补充不重复的
        Map<Long, VehicleInfo> mergedMap = new LinkedHashMap<>();
        for (VehicleInfo v : materialList) {
            mergedMap.put(v.getVehicleId(), v);
        }
        for (VehicleInfo v : templateList) {
            mergedMap.putIfAbsent(v.getVehicleId(), v);
        }
        return new ArrayList<>(mergedMap.values());
    }

    @Override
    public List<VehicleInfo> listFirstVehicleUnconfirmedAll(VehicleInfo vehicleInfo) {
        // 物料号维度：first_material_flag=1 AND generate_affirm=0
        List<VehicleInfo> materialList = vehicleInfoMapper.listFirstVehicleUnconfirmed(vehicleInfo, "material");
        // 模版维度：first_template_flag=1 AND upload_affirm=0
        List<VehicleInfo> templateList = vehicleInfoMapper.listFirstVehicleUnconfirmed(vehicleInfo, "template");

        // 按 vehicle_id 合并去重，同一辆车只显示一次
        Map<Long, VehicleInfo> mergedMap = new LinkedHashMap<>();
        materialList.forEach(v -> mergedMap.put(v.getVehicleId(), v));
        // 模版维度的车如果已在物料号维度里，不覆盖；如果不在，补充进来
        templateList.forEach(v -> mergedMap.putIfAbsent(v.getVehicleId(), v));

        return new ArrayList<>(mergedMap.values());
    }
    // ========== SSE 工具方法（与 VehicleTemplateServiceImpl 保持一致）==========

    private void pushEvent(Sinks.Many<ServerSentEvent<String>> sink, String eventType, String data) {
        Sinks.EmitResult result = sink.tryEmitNext(
                ServerSentEvent.<String>builder()
                        .event(eventType)
                        .data(data)
                        .build());
        if (result.isFailure()) {
            log.warn("SSE 推送失败, event={}, result={}", eventType, result);
        }
    }

    private String escapeJson(String raw) {
        if (raw == null) return "";
        return raw.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    // ========== 工具方法 ==========
    private void applyFirstVehicleAffirm(VehicleInfo vehicleInfo, String materialNo, String templateId) {
        boolean materialSwitchOn = firstVehicleCheckService.isSwitchOn("new_material");
        boolean templateSwitchOn = firstVehicleCheckService.isSwitchOn("new_template");

        boolean materialNeedsConfirm = false;
        boolean templateNeedsConfirm = false;
        List<String> causes = new ArrayList<>();

        // ── 物料号维度 ────────────────────────────────────────────────────────
        if (StringUtils.isNotBlank(materialNo) && materialSwitchOn) {
            Long earliestMaterialId = vehicleInfoMapper.findEarliestIdByMaterialNo(materialNo);
            if (earliestMaterialId == null) {
                materialNeedsConfirm = true;
                causes.add("物料号");
            } else {
                VehicleInfo earliest = vehicleInfoMapper.selectVehicleInfoById(earliestMaterialId);
                if (earliest != null && Integer.valueOf(0).equals(earliest.getGenerateAffirm())) {
                    materialNeedsConfirm = true;
                    causes.add("物料号");
                }
            }
        }

        // ── 模板维度 ──────────────────────────────────────────────────────────
        if (StringUtils.isNotBlank(templateId) && templateSwitchOn) {
            Long earliestTemplateId = vehicleInfoMapper.findEarliestIdByTemplateId(templateId);
            if (earliestTemplateId == null) {
                // 该模版首次出现 → 模版新增
                templateNeedsConfirm = true;
                causes.add("模版新增");
            } else {
                VehicleInfo earliest = vehicleInfoMapper.selectVehicleInfoById(earliestTemplateId);
                if (earliest != null && Integer.valueOf(0).equals(earliest.getUploadAffirm())) {
                    // 该模版已有记录但仍待确认 → 模版编辑
                    templateNeedsConfirm = true;
                    causes.add("模版编辑");
                } else {
                    // 检查该模版是否被修改过（同 uuid 下是否有更新版本）
                    if (isTemplateModified(templateId)) {
                        templateNeedsConfirm = true;
                        causes.add("模版修改");
                    }
                }
            }
        }

        if (materialNeedsConfirm) {
            vehicleInfo.setFirstMaterialFlag(1);
            vehicleInfo.setGenerateAffirm(0);
        } else {
            vehicleInfo.setGenerateAffirm(1);
        }


        if (templateNeedsConfirm) {
            vehicleInfo.setFirstTemplateFlag(1);
            vehicleInfo.setUploadAffirm(0);
        } else {
            vehicleInfo.setUploadAffirm(1);
        }

        int affirm = (materialNeedsConfirm || templateNeedsConfirm) ? 0 : 1;
        vehicleInfo.setGenerateAffirm(affirm);
        vehicleInfo.setUploadAffirm(affirm);

        if (!causes.isEmpty()) {
            vehicleInfo.setAffirmCause(String.join("、", causes));
        }
    }

    private void applyFirstVehicleAffirmOnUpdate(VehicleInfo vehicleInfo, String materialNo, String templateId) {
        boolean materialSwitchOn = firstVehicleCheckService.isSwitchOn("new_material");
        boolean templateSwitchOn = firstVehicleCheckService.isSwitchOn("new_template");

        boolean materialNeedsConfirm = false;
        boolean templateNeedsConfirm = false;
        List<String> causes = new ArrayList<>();

        // ── 物料号维度 ────────────────────────────────────────────────────────
        if (StringUtils.isNotBlank(materialNo) && materialSwitchOn) {
            Long earliestMaterialId = vehicleInfoMapper.findEarliestIdByMaterialNo(materialNo);
            if (earliestMaterialId == null) {
                materialNeedsConfirm = true;
                causes.add("物料号");
            } else {
                VehicleInfo earliest = vehicleInfoMapper.selectVehicleInfoById(earliestMaterialId);
                if (earliest != null && Integer.valueOf(0).equals(earliest.getGenerateAffirm())) {
                    materialNeedsConfirm = true;
                    causes.add("物料号");
                }
            }
        }

        // ── 模板维度 ──────────────────────────────────────────────────────────
        if (StringUtils.isNotBlank(templateId) && templateSwitchOn) {
            Long earliestTemplateId = vehicleInfoMapper.findEarliestIdByTemplateId(templateId);
            if (earliestTemplateId == null) {
                templateNeedsConfirm = true;
                causes.add("模版新增");
            } else {
                VehicleInfo earliest = vehicleInfoMapper.selectVehicleInfoById(earliestTemplateId);
                if (earliest != null && Integer.valueOf(0).equals(earliest.getUploadAffirm())) {
                    templateNeedsConfirm = true;
                    causes.add("模版编辑");
                } else {
                    // 检查该模版是否被修改过（同 uuid 下是否有更新版本）
                    if (isTemplateModified(templateId)) {
                        templateNeedsConfirm = true;
                        causes.add("模版修改");
                    }
                }
            }
        }

        if (materialNeedsConfirm) {
            vehicleInfo.setFirstMaterialFlag(1);
            vehicleInfo.setGenerateAffirm(0);
        } else {
            vehicleInfo.setGenerateAffirm(1);
        }
        if (templateNeedsConfirm) {
            vehicleInfo.setFirstTemplateFlag(1);
            vehicleInfo.setUploadAffirm(0);
        } else {
            vehicleInfo.setUploadAffirm(1);
        }

        if (materialNeedsConfirm || templateNeedsConfirm) {
            vehicleInfo.setGenerateAffirm(0);
            vehicleInfo.setUploadAffirm(0);
            vehicleInfo.setAffirmCause(String.join("、", causes));
        }
    }

    /**
     * 将 MES Excel 行数据（表头 → 值 的 Map）按 vehicle_attribute 字典中 keyMap 以 "MES_" 开头的规则
     * 做字段映射，返回映射后的 JSON 字符串。
     * 逻辑与 VehicleTemplateServiceImpl#jsonConvertFromTemplateJson 完全一致，
     * 区别仅在于：输入为已解析好的 Map&lt;String, Object&gt;，且只处理 keyMap 以 "MES_" 开头的字典条目。
     *
     * @param excelRowMap Excel 行中特定列的表头 → 值映射（由调用方按需截取后传入）
     * @return 映射后的 JSON 字符串
     */
    private String jsonConvertFromMesExcel(Map<String, Object> excelRowMap) {
        if (excelRowMap == null || excelRowMap.isEmpty()) {
            try {
                return objectMapper.writeValueAsString(new LinkedHashMap<>());
            } catch (Exception e) {
                throw new RuntimeException("MES Excel JSON 序列化失败: " + e.getMessage());
            }
        }

        Map<String, Object> map = new LinkedHashMap<>(excelRowMap);
        Map<String, Object> finalMap = map;

        try {
            List<SysDictData> allDictData =
                    remoteDictService.getDictDataByType("vehicle_attribute").getData();

            if (allDictData != null && !allDictData.isEmpty()) {

                // ── 第一步：全量按 uuid 分组，无 uuid 的每条独立 ──────────────────
                Map<String, List<SysDictData>> uuidGroups = new LinkedHashMap<>();
                for (SysDictData rule : allDictData) {
                    String groupKey = com.ruoyi.common.core.utils.StringUtils.isNotBlank(rule.getUuid())
                            ? rule.getUuid()
                            : "$$solo$$" + rule.getDictCode();
                    uuidGroups.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(rule);
                }

                // ── 第二步：识别单 key_map 链 vs 多 key_map 链 ────────────────
                // keyMap 索引只取 originalSystem 以 "MES_" 开头的那条记录，
                // chain 保持完整（包含所有 originalSystem 的记录），保证链式转换不断裂。
                Map<String, List<List<SysDictData>>> keyToChains = new LinkedHashMap<>();
                Map<String, List<SysDictData>> multiKeyChainMap = new LinkedHashMap<>();

                for (Map.Entry<String, List<SysDictData>> groupEntry : uuidGroups.entrySet()) {
                    List<SysDictData> chain = groupEntry.getValue();
                    chain.sort(Comparator.comparingLong(SysDictData::getDictCode));

                    // 在 chain 中找出 originalSystem 以 "MES_" 开头的记录，取其 keyMap 作为索引
                    long distinctKeyMapCount = chain.stream()
                            .filter(d -> com.ruoyi.common.core.utils.StringUtils.isNotBlank(d.getOriginalSystem())
                                    && d.getOriginalSystem().startsWith("MES_"))
                            .map(SysDictData::getKeyMap)
                            .filter(com.ruoyi.common.core.utils.StringUtils::isNotBlank)
                            .distinct()
                            .count();

                    if (distinctKeyMapCount > 1) {
                        multiKeyChainMap.put(groupEntry.getKey(), chain);
                    } else {
                        String keyMap = chain.stream()
                                .filter(d -> com.ruoyi.common.core.utils.StringUtils.isNotBlank(d.getOriginalSystem())
                                        && d.getOriginalSystem().startsWith("MES_"))
                                .map(SysDictData::getKeyMap)
                                .filter(com.ruoyi.common.core.utils.StringUtils::isNotBlank)
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

                    // 无规则：跳过（传入的 key 是 MES 列头，无映射规则则不写入 result）
                    if (chains == null || chains.isEmpty()) {
                        continue;
                    }

                    String rawValue = entry.getValue() == null
                            ? null : String.valueOf(entry.getValue());

                    for (List<SysDictData> singleChain : chains) {
                        String converted = applyMesChain(rawValue, singleChain, fieldName);
                        String targetLabel = singleChain.get(singleChain.size() - 1).getDictLabel();
                        if (ValueMappingParser.EMPTY_SENTINEL.equals(converted)) {
                            result.put(targetLabel, null);
                        } else if (StringUtils.isNotBlank(converted)) {
                            result.put(targetLabel, converted);
                        }
                    }
                }

                // ── 第三步结束后：补充 NULL 规则兜底（不依赖 key_map 是否存在于模板 JSON）──
                for (List<SysDictData> chain : keyToChains.values().stream()
                        .flatMap(List::stream).collect(Collectors.toList())) {
                    SysDictData last = chain.get(chain.size() - 1);
                    if ("NULL".equalsIgnoreCase(
                            last.getValueMap() == null ? "" : last.getValueMap().trim())) {
                        result.putIfAbsent(last.getDictLabel(), null);
                    }
                }

                // ── 第四步：执行多 key_map 链 ────────────────────────────────
                for (List<SysDictData> multiChain : multiKeyChainMap.values()) {
                    multiChain.sort(Comparator.comparingLong(SysDictData::getDictCode));

                    String[] groupSeps = multiChain.stream()
                            .map(SysDictData::getValueMap)
                            .map(com.ruoyi.common.core.parser.ValueMappingParser::extractGroupJoinSep)
                            .filter(Objects::nonNull)
                            .findFirst()
                            .orElse(null);

                    String outSep = (groupSeps != null) ? groupSeps[1] : ",";

                    for (SysDictData rule : multiChain) {
                        if (com.ruoyi.common.core.utils.StringUtils.isBlank(rule.getDictLabel())) continue;
                        if (com.ruoyi.common.core.utils.StringUtils.isNotBlank(rule.getValueMap())
                                && rule.getValueMap().trim().toUpperCase().startsWith("GROUP_JOIN_SEP")) {
                            continue;
                        }

                        Object rawObj = com.ruoyi.common.core.utils.StringUtils.isNotBlank(rule.getKeyMap())
                                ? map.get(rule.getKeyMap()) : null;
                        String rawValue = rawObj == null ? null : String.valueOf(rawObj);
                        String converted = rawValue;

                        if (com.ruoyi.common.core.utils.StringUtils.isNotBlank(rule.getValueMap())) {
                            Map<String, String> mergedMap =
                                    com.ruoyi.common.core.parser.ValueMappingParser.mergeValueConnection(rule.getValueConnection());
                            String stepped =
                                    com.ruoyi.common.core.parser.ValueMappingParser.convertWithDictMap(rawValue, rule.getValueMap(), mergedMap);

                            if (com.ruoyi.common.core.parser.ValueMappingParser.EMPTY_SENTINEL.equals(stepped)) {
                                if (!result.containsKey(rule.getDictLabel())) {
                                    result.put(rule.getDictLabel(), null);
                                }
                                continue;
                            }
                            if (stepped != null) {
                                converted = stepped;
                            } else {
                                log.warn("[jsonConvertFromMesExcel] 多key_map链转换返回 null，dict_code={}, keyMap={}",
                                        rule.getDictCode(), rule.getKeyMap());
                            }
                        }

                        converted = com.ruoyi.common.core.utils.StringUtils.isNotBlank(converted) ? converted : rawValue;
                        converted = "N/A".equals(converted) ? "" : converted;

                        if (com.ruoyi.common.core.utils.StringUtils.isNotBlank(converted)) {
                            String label = rule.getDictLabel();
                            Object existing = result.get(label);
                            if (existing != null && com.ruoyi.common.core.utils.StringUtils.isNotBlank(String.valueOf(existing))) {
                                result.put(label, existing + outSep + converted);
                            } else {
                                result.put(label, converted);
                            }
                        }
                    }
                }

                finalMap = result;
            }
        } catch (Exception e) {
            log.error("[jsonConvertFromMesExcel] 字段映射异常，返回原始 map", e);
        }

        try {
            return objectMapper.writeValueAsString(finalMap);
        } catch (Exception e) {
            throw new RuntimeException("MES Excel JSON 映射后序列化失败: " + e.getMessage());
        }
    }

    /**
     * 链式规则执行（供 jsonConvertFromMesExcel 使用，与 VehicleTemplateServiceImpl#applyChain 逻辑一致）
     */
    private String applyMesChain(String rawValue, List<SysDictData> chain, String fieldName) {
        String current = rawValue;
        for (SysDictData rule : chain) {
            Map<String, String> mergedMap = com.ruoyi.common.core.parser.ValueMappingParser.mergeValueConnection(rule.getValueConnection());
            current = com.ruoyi.common.core.parser.ValueMappingParser.convertWithDictMap(current, rule.getValueMap(), mergedMap);
            if (current == null) {
                log.warn("[applyMesChain] 转换返回 null, fieldName={}, dictCode={}, valueMap={}",
                        fieldName, rule.getDictCode(), rule.getValueMap());
                return null;
            }
            if (com.ruoyi.common.core.parser.ValueMappingParser.EMPTY_SENTINEL.equals(current)) {
                return com.ruoyi.common.core.parser.ValueMappingParser.EMPTY_SENTINEL;
            }
        }
        return current;
    }

    /**
     * 过滤 JSON 字符串，只保留 sys_data 中 dict_type='vehicle_attribute' 的 dict_label 所对应的键，
     * 其余顶层键一律删除。
     *
     * @param json 原始 JSON 字符串（来自 VehicleTemplate）
     * @return 过滤后的 JSON 字符串；若获取字典失败或 JSON 解析失败则返回原始 json
     */
    private String filterJsonByVehicleAttribute(String json) {
        if (StringUtils.isBlank(json)) {
            return json;
        }
        try {
            // 1. 从远程字典服务获取 vehicle_attribute 的所有 dict_label，构成白名单 Set
            com.ruoyi.common.core.domain.R<List<SysDictData>> dictResult =
                    remoteDictService.getDictDataByType("vehicle_attribute");
            if (dictResult == null || dictResult.getData() == null) {
                log.warn("filterJsonByVehicleAttribute: 获取 vehicle_attribute 字典失败，跳过过滤，返回原始 JSON");
                return json;
            }
            Set<String> allowedKeys = dictResult.getData().stream()
                    .map(SysDictData::getDictLabel)
                    .filter(StringUtils::isNotBlank)
                    .collect(Collectors.toSet());

            // 2. 解析 JSON，删除不在白名单中的顶层键
            Map<String, Object> jsonMap = objectMapper.readValue(
                    json,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});

            jsonMap.keySet().retainAll(allowedKeys);

            // 3. 序列化回 JSON 字符串
            return objectMapper.writeValueAsString(jsonMap);
        } catch (Exception e) {
            log.error("filterJsonByVehicleAttribute: JSON 过滤异常，返回原始 JSON", e);
            return json;
        }
    }

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

    /**
     * 递归遍历 JSON 节点，替换目标字段的值
     */
    private void replaceFieldValues(JsonNode node, Material material) {
        if (node.isObject()) {
            ObjectNode objectNode = (ObjectNode) node;
            Iterator<Map.Entry<String, JsonNode>> fields = objectNode.fields();

            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String fieldName = entry.getKey();
                JsonNode fieldValue = entry.getValue();

                // 根据字段名进行替换
                switch (fieldName) {
                    case "TyreSize":
                        String tyreSizeRaw = fieldValue.asText();
                        if (material.getTire() != null && tyreSizeRaw != null && tyreSizeRaw.contains(";")) {
                            String[] tyreSizeParts = tyreSizeRaw.split(";", -1);

                            // 找到 material.getTire() 匹配的是第几段（0=前半段，1=后半段，以此类推）
                            int matchIndex = -1;
                            for (int i = 0; i < tyreSizeParts.length; i++) {
                                if (tyreSizeParts[i].trim().equals(material.getTire().trim())) {
                                    matchIndex = i;
                                    break;
                                }
                            }

                            if (matchIndex >= 0) {
                                // 需要同步裁剪的轮胎相关字段
                                List<String> tyreFields = Arrays.asList(
                                        "TyreNumber", "TyreSize", "LoadCapacityIndexSingleWheel",
                                        "SpeedCategorySymbol", "RimSize", "RimOffSet", "RollingResistanceClass",
                                        "TyreFittedProductionIndicator", "TyreCategory", "TyreMaximumSpeedIndicator"
                                );

                                final int finalMatchIndex = matchIndex;
                                for (String tyreField : tyreFields) {
                                    JsonNode tyreFieldNode = objectNode.get(tyreField);
                                    if (tyreFieldNode == null || tyreFieldNode.isNull()) continue;

                                    String rawVal = tyreFieldNode.asText();
                                    if (rawVal == null || rawVal.isEmpty()) continue;

                                    String[] parts = rawVal.split(";", -1);
                                    if (finalMatchIndex < parts.length) {
                                        // 保留匹配到的那一段
                                        objectNode.put(tyreField, parts[finalMatchIndex].trim());
                                    }
                                    // 若该字段段数不够（如单值字段），保持原值不变
                                }
                            }
                        }
                        break;
                    case "ActualMass":
                        if (material.getWeight() != null) {
                            objectNode.put(fieldName, material.getWeight());
                        }
                        break;
                    case "Make":
                        if (material.getBrand() != null) {
                            objectNode.put(fieldName, material.getBrand());
                        }
                        break;
                    case "CommercialName":
                        if (material.getSaleName() != null) {
                            objectNode.put(fieldName, material.getSaleName());
                        }
                        break;
                    case "RollingResistanceClass":
                        if (material.getTireResistanceGrade() != null) {
                            List<SysDictData> tireResistanceGradeData = remoteDictService
                                    .getDictDataByType("rolling_resistance_class").getData();
                            // 找到 dict_label = "RollingResistanceClass" 的字典条目，取其 value_connection
                            SysDictData rrcDictData = tireResistanceGradeData.stream()
                                    .filter(d -> "RollingResistanceClass".equals(d.getDictLabel()))
                                    .findFirst()
                                    .orElse(null);
                            String converted = null;
                            if (rrcDictData != null
                                    && rrcDictData.getValueMap() != null
                                    && rrcDictData.getValueConnection() != null) {
                                Map<String, String> mergedMap = ValueMappingParser
                                        .mergeValueConnection(rrcDictData.getValueConnection());
                                converted = ValueMappingParser
                                        .convertWithDictMap(material.getTireResistanceGrade(),
                                                rrcDictData.getValueMap(), mergedMap);
                            }
                            objectNode.put(fieldName, converted != null ? converted : fieldValue.asText());
                        }
                        break;
                    default:
                        // 递归处理嵌套对象或数组
                        if (fieldValue.isObject() || fieldValue.isArray()) {
                            replaceFieldValues(fieldValue, material);
                        }
                        break;
                }
            }
        } else if (node.isArray()) {
            // 遍历数组中的每个元素
            for (JsonNode arrayElement : node) {
                replaceFieldValues(arrayElement, material);
            }
        }
    }

    private void checkMaterialInBlacklist(VehicleInfo vehicleInfo) {
        if (vehicleInfo == null || vehicleInfo.getMaterialNo() == null) {
            log.info("[黑名单] vehicleInfo 或 materialNo 为空，跳过");
            return;
        }

        // 1. 按 materialNo 查
        MaterialBlacklist materialBlacklist = materialBlacklistMapper
                .selectMaterialBlacklistByMaterialNo(vehicleInfo.getMaterialNo());
        log.info("[黑名单] 按materialNo={} 查询结果={}", vehicleInfo.getMaterialNo(), materialBlacklist);

        // 2a. 按 customerNo 查
        if (materialBlacklist == null) {
            materialBlacklist = materialBlacklistMapper
                    .selectMaterialBlacklistByCustomerNo(vehicleInfo.getCustomerNo());
            log.info("[黑名单] 按customerNo={} 查询结果={}", vehicleInfo.getCustomerNo(), materialBlacklist);
        }

        // 3. 按 json 里的字段查
        if (materialBlacklist == null && vehicleInfo.getJson() != null) {
            try {
                JsonNode jsonObj = new ObjectMapper().readTree(vehicleInfo.getJson());
                JsonNode customerNoNode = jsonObj.get("CommercialName");
                log.info("[黑名单] json CommercialName={}", customerNoNode);
                if (customerNoNode != null && StringUtils.isNotBlank(customerNoNode.asText())) {
                    materialBlacklist = materialBlacklistMapper
                            .selectMaterialBlacklistByCustomerNo(customerNoNode.asText());
                }
                if (materialBlacklist == null) {
                    JsonNode makeNode = jsonObj.get("Make");
                    log.info("[黑名单] json Make={}", makeNode);
                    if (makeNode != null && StringUtils.isNotBlank(makeNode.asText())) {
                        materialBlacklist = materialBlacklistMapper
                                .selectMaterialBlacklistByBrand(makeNode.asText());
                    }
                }
            } catch (Exception e) {
                log.warn("[黑名单] json 解析失败", e);
            }
        }

        log.info("[黑名单] 最终匹配结果={}", materialBlacklist);

        if (materialBlacklist != null) {
            VehicleInfo update = new VehicleInfo();
            update.setVehicleId(vehicleInfo.getVehicleId());
            update.setDeleted(2);
            vehicleInfoMapper.updateVehicleInfo(update);
            vehicleInfo.setDeleted(2);
            log.info("[黑名单] 命中，vin={} 标记为 deleted=2", vehicleInfo.getVin());
        }
    }

    /**
     * 校验车辆是否允许上传 XML
     * 规则：该车辆关联模版的首台车必须已确认（upload_affirm=1），
     *       或者该车辆本身就是首台车（first_template_flag=1）
     */
    private void checkUploadPermission(VehicleInfo vehicleInfo) {
        // 首台车本身始终允许操作
        if (Integer.valueOf(1).equals(vehicleInfo.getFirstTemplateFlag())) {
            return;
        }
        // 其他车辆：检查该模版下是否已有确认记录
        boolean confirmed = vehicleInfoMapper.existsConfirmedTemplate(vehicleInfo.getVehicleTemplateId());
        if (!confirmed) {
            throw new ServiceException("该模版首台车尚未确认上传，当前车辆暂不可上传");
        }
    }

    private boolean isTemplateModified(String templateId) {
        try {
            VehicleTemplate current = vehicleTemplateMapper.selectVehicleTemplateById(Long.parseLong(templateId));
            if (current == null || current.getUuid() == null) {
                return false;
            }
            VehicleTemplate query = new VehicleTemplate();
            query.setUuid(current.getUuid());
            List<VehicleTemplate> allVersions = vehicleTemplateMapper.selectVehicleTemplateList(query);
            if (allVersions == null || allVersions.size() <= 1) {
                return false;
            }
            BigDecimal currentVersion = new BigDecimal(current.getVersion());
            return allVersions.stream()
                    .filter(t -> t.getVersion() != null)
                    .anyMatch(t -> new BigDecimal(t.getVersion()).compareTo(currentVersion) > 0);
        } catch (Exception e) {
            log.warn("[isTemplateModified] 检查模版是否修改失败，templateId={}", templateId, e);
            return false;
        }
    }
}