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
import com.ruoyi.vehicle.service.IMaterialBlacklistService;
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
    private IMaterialBlacklistService materialBlacklistService;

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
                            labels.put("cocOrder", dictData != null ? dictData.getCocOrder() : null);
                            labels.put("originalSystemConnection", dictData != null ? dictData.getOriginalSystemConnection() : null);
                            labels.put("valueConnection", dictData != null ? dictData.getValueConnection() : null);
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
            vehicleInfo.setWvtaNo(template.getWvtaCocNo());
            vehicleInfo.setCocTemplateNo(template.getCocTemplateNo());
            vehicleInfo.setJson(json);
            vehicleInfo.setGenerateAffirm(template.getGenerateAffirm());
            vehicleInfo.setUploadAffirm(template.getUploadAffirm());
        }
        vehicleInfo.setUploadStatus(0);
        vehicleInfo.setValidationResult(0);
        vehicleInfo.setDeleted(0);
        vehicleInfo.setCreateTime(vehicleInfo.getCreateTime() == null ? DateUtils.getNowDate() : vehicleInfo.getCreateTime());
        vehicleInfo.setCreateBy(SecurityUtils.getUsername() != null ? SecurityUtils.getUsername() : "MES To System");
        if (vehicleInfo.getVehicleId() != null) {
            deleteVehicleInfoByIds(new Long[]{vehicleInfo.getVehicleId()});
        }
        int insertRow = vehicleInfoMapper.insertVehicleInfo(vehicleInfo);
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
            params.put("issueDate", DateUtils.parseDateToStr("yyyy-MM-dd HH:mm:ss", vehicleInfo.getIssueDate()));
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
            params.put("issueDate", DateUtils.parseDateToStr("yyyy-MM-dd HH:mm:ss", vehicleInfo.getIssueDate()));
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
            params.put("id", String.valueOf(vehicleInfo.getVehicleId()));
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
    public Map<String, Object> getVehicleInfoFromMes(VehicleDto.Vehicle vehicle, Date now, LoginUser loginUser) throws JsonProcessingException {
        Set<String> permissions = loginUser.getPermissions();
        if (!permissions.contains("vehicle:info:toSystem")) {
            throw new ServiceException("没有权限执行此操作");
        }
        VehicleInfo vehicleInfo = new VehicleInfo();
        BeanUtils.copyProperties(vehicle, vehicleInfo);
        vehicleInfo.setCustomerNo(vehicle.getCustomerNumber());
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

        try {
            self.insertVehicleInfo(vehicleInfo);  // ← 走代理，事务独立
            checkMaterialInBlacklist(vehicleInfo);
        } catch (Exception e) {
            // 剥出根因，确保 message 不丢失
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
                if (vehicleInfo.getIssueDate() == null)                 missingFields.add("Issue Date");

                Map<String, String> countryLabelToValueMap = remoteDictService
                        .getDictDataByType("country")   // dict_type 替换为实际值
                        .getData().stream()
                        .collect(Collectors.toMap(
                                SysDictData::getDictLabel,
                                SysDictData::getDictValue,
                                (k1, k2) -> k1));
                String countryLabel = vehicleInfo.getCountry();
                String countryValue = countryLabelToValueMap.get(countryLabel);
                if (countryValue == null) {
                    throw new IllegalArgumentException("国家[" + countryLabel + "]在字典中未找到对应值");
                }
                vehicleInfo.setCountry(countryValue);

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
                        // 每行独立事务插入
                        self.insertSingleVehicleInfoRow(vehicleInfo, template, materialList.get(0));
                        importedList.add(vehicleInfo);
                        successCount++;

                        pushEvent(sink, "progress", String.format(
                                "{\"row\":%d,\"total\":%d,\"status\":\"success\"}", rowNum, total));
                    } else {
                        vehicleInfo.setCreateBy(createBy);
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
    public void insertSingleVehicleInfoRow(VehicleInfo vehicleInfo, VehicleTemplate template, Material material) {
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
        } catch (JsonProcessingException e) {
            throw new RuntimeException("动态参数替换失败");
        }
        vehicleInfo.setVehicleTemplateId(String.valueOf(template.getTemplateId()));

        // 补充系统字段
        vehicleInfo.setUploadStatus(0);
        vehicleInfo.setValidationResult(0);
        vehicleInfo.setDeleted(0);
        vehicleInfo.setGenerateAffirm(template.getGenerateAffirm());
        vehicleInfo.setUploadAffirm(template.getUploadAffirm());
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
    public int batchUpdateVehicleTemplate(List<Long> vehicleIds) {
        if (vehicleIds == null || vehicleIds.isEmpty()) {
            throw new ServiceException("请选择需要修改的车辆");
        }

        // 1. 批量查出车辆信息
        Long[] idArray = vehicleIds.toArray(new Long[0]);
        List<VehicleInfo> vehicleList = vehicleInfoMapper.selectVehicleInfoByIds(idArray);
        if (vehicleList.isEmpty()) {
            throw new ServiceException("未找到对应的车辆信息");
        }

        // 2. 校验所有车辆必须属于同一整车物料号
        Set<String> materialNos = vehicleList.stream()
                .map(VehicleInfo::getMaterialNo)
                .filter(StringUtils::isNotBlank)
                .collect(java.util.stream.Collectors.toSet());

        if (materialNos.size() > 1) {
            throw new ServiceException("批量修改关联模版只允许操作同一整车物料号下的车辆，"
                    + "当前选中了 " + materialNos.size() + " 种不同物料号：" + materialNos);
        }
        if (materialNos.isEmpty()) {
            throw new ServiceException("所选车辆物料号为空，无法执行批量修改");
        }

        Material query = new Material();
        query.setMaterialNo(materialNos.iterator().next());
        query.setStatus(0);
        List<Material> materialList = materialMapper.selectMaterialList(query);
        if (materialList.isEmpty()) {
            throw new ServiceException("物料号管理信息为空，无法切换版本");
        }
        Long templateId = materialList.get(0).getVehicleTemplateId();

        VehicleTemplate template = vehicleTemplateMapper.selectVehicleTemplateById(templateId);
        if (template == null) {
            throw new ServiceException("目标模版不存在，templateId=" + templateId);
        }
        // 模版需处于启用状态（status='0'）
        if (!"0".equals(template.getStatus())) {
            throw new ServiceException("目标模版已停用，请选择启用状态的模版");
        }


        // 4. 逐条更新（保留触发校验、生命周期记录的完整链路）
        String operator = SecurityUtils.getUsername();
        int successCount = 0;
        List<String> failVins = new ArrayList<>();

        for (VehicleInfo vehicle : vehicleList) {
            try {
                vehicle.setVehicleTemplateId(String.valueOf(templateId));
                vehicle.setWvtaNo(template.getWvtaCocNo());
                vehicle.setCocTemplateNo(template.getCocTemplateNo());
                vehicle.setJson(template.getJson());
                vehicle.setVin(null);
                vehicle.setUpdateBy(operator);
                vehicle.setUpdateTime(DateUtils.getNowDate());
                // 重置校验状态，等待重新校验
                vehicle.setValidationResult(0);
                vehicle.setUploadStatus(0);

                vehicleInfoMapper.updateVehicleInfo(vehicle);

                successCount++;
            } catch (Exception e) {
                log.error("批量修改模版：vehicleId={} 更新失败", vehicle.getVehicleId(), e);
                failVins.add(String.valueOf(vehicle.getVehicleId()));
            }
        }

        // 5. 批量触发校验（只校验成功更新的）
        List<Long> updatedIds = vehicleList.stream()
                .map(VehicleInfo::getVehicleId)
                .filter(id -> !failVins.contains(String.valueOf(id)))
                .collect(java.util.stream.Collectors.toList());
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
    public Map<String, List<Map<String, String>>> getTemplateVersion(List<Long> vehicleIds) {
        List<VehicleInfo> vehicleInfoList = vehicleInfoMapper.selectVehicleInfoByIds(vehicleIds.toArray(new Long[0]));
        if (vehicleInfoList.isEmpty()) {
            throw new ServiceException("请传入车辆ID列表");
        }
        List<String> materialNoList = vehicleInfoList.stream()
                .map(VehicleInfo::getMaterialNo)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .collect(java.util.stream.Collectors.toList());
        Map<String, List<Map<String, String>>> result = new HashMap<>();
        List<Map<String, String>> map = vehicleInfoMapper.selectOldVersionAndNewVersion(materialNoList);
        result.put(materialNoList.get(0), map);
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
                    case "RollingResistanceClass":
                        List<SysDictData> tireResistanceGradeData = remoteDictService.getDictDataByType("rolling_resistance_class").getData();
                        Map<String, String> tireResistanceGradeMap = tireResistanceGradeData.stream()
                                .collect(Collectors.toMap(SysDictData::getDictLabel, SysDictData::getDictValue, (a, b) -> a));
                        if (material.getTireResistanceGrade() != null) {
                            // 去掉单位：取数字部分（如 "5.96N/kN" -> "5.96"）
                            String rawGrade = material.getTireResistanceGrade().replaceAll("[^\\d.].*$", "").trim();
                            String tireResistanceGradeValue = tireResistanceGradeMap.get(rawGrade);
                            objectNode.put(fieldName, tireResistanceGradeValue != null ? tireResistanceGradeValue : fieldValue.asText());
                        }
                        break;
                    case "CommercialName":
                        if (material.getSaleName() != null) {
                            objectNode.put(fieldName, material.getSaleName());
                        }
                        break;
                    case "Make":
                        if (material.getBrand() != null) {
                            objectNode.put(fieldName, material.getBrand());
                        }
                        break;
                    case "ActualMass":
                        if (material.getWeight() != null) {
                            objectNode.put(fieldName, material.getWeight());
                        }
                        break;
                    case "TechnicallyPermissibleMaximumTowableMass":
                        if (material.getTire() != null) {
                            objectNode.put(fieldName, material.getTire());
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
        if (vehicleInfo == null || vehicleInfo.getJson() == null) {
            return;
        }

        // 1. 先按 materialNo 查
        MaterialBlacklist materialBlacklist = materialBlacklistMapper
                .selectMaterialBlacklistByMaterialNo(vehicleInfo.getMaterialNo());

        // 2. 查不到再按 customerNo 查
        if (materialBlacklist == null) {
            materialBlacklist = materialBlacklistMapper
                    .selectMaterialBlacklistByCustomerNo(vehicleInfo.getCustomerNo());
        }

        if (materialBlacklist == null) {
            try {
                JsonNode jsonObj = new ObjectMapper().readTree(vehicleInfo.getJson());
                JsonNode customerNoNode = jsonObj.get("CommercialName");
                if (customerNoNode != null && StringUtils.isNotBlank(customerNoNode.asText())) {
                    materialBlacklist = materialBlacklistMapper
                            .selectMaterialBlacklistByCustomerNo(customerNoNode.asText());
                }
            } catch (Exception e) {
                log.warn("checkMaterialInBlacklist: json 解析失败, vehicleId={}",
                        vehicleInfo.getVehicleId(), e);
            }
        }

        // 3. 还查不到再按 brand 查（从 json 取 Make 字段）
        if (materialBlacklist == null) {
            try {
                JsonNode jsonObj = new ObjectMapper().readTree(vehicleInfo.getJson());
                JsonNode makeNode = jsonObj.get("Make");
                if (makeNode != null && StringUtils.isNotBlank(makeNode.asText())) {
                    materialBlacklist = materialBlacklistMapper
                            .selectMaterialBlacklistByBrand(makeNode.asText());
                }
            } catch (Exception e) {
                log.warn("checkMaterialInBlacklist: json 解析失败, vehicleId={}",
                        vehicleInfo.getVehicleId(), e);
            }
        }

        // 4. 任意一步查到即命中，更新 deleted = 2
        if (materialBlacklist != null) {
            VehicleInfo update = new VehicleInfo();
            update.setVehicleId(vehicleInfo.getVehicleId());
            update.setDeleted(2);
            vehicleInfoMapper.updateVehicleInfo(update);
            vehicleInfo.setDeleted(2);
        }
    }
}