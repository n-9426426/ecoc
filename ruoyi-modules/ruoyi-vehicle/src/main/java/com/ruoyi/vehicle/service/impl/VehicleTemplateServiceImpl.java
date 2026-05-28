package com.ruoyi.vehicle.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.TypeReference;
import com.alibaba.fastjson2.JSON;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.core.enums.RuleItemType;
import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.common.core.model.FieldValidationResult;
import com.ruoyi.common.core.model.RuleViolation;
import com.ruoyi.common.core.model.ValidationReport;
import com.ruoyi.common.core.parser.ValueMappingParser;
import com.ruoyi.common.core.utils.DateUtils;
import com.ruoyi.common.core.utils.StringUtils;
import com.ruoyi.common.core.utils.uuid.UUID;
import com.ruoyi.common.security.utils.SecurityUtils;
import com.ruoyi.system.api.RemoteDictService;
import com.ruoyi.system.api.RemoteFileService;
import com.ruoyi.system.api.RemoteNoticeService;
import com.ruoyi.system.api.domain.SysDictData;
import com.ruoyi.system.api.domain.SysNotice;
import com.ruoyi.system.api.enums.SysNoticeModel;
import com.ruoyi.vehicle.domain.AbnormalClassify;
import com.ruoyi.vehicle.domain.VehicleTemplate;
import com.ruoyi.vehicle.domain.VehicleTemplateMaterial;
import com.ruoyi.vehicle.mapper.AbnormalClassifyMapper;
import com.ruoyi.vehicle.mapper.VehicleTemplateMapper;
import com.ruoyi.vehicle.mapper.VehicleTemplateMaterialMapper;
import com.ruoyi.vehicle.service.IFirstVehicleCheckService;
import com.ruoyi.vehicle.service.IVehicleTemplateService;
import com.ruoyi.vehicle.service.IVehicleValidationService;
import com.ruoyi.vehicle.utils.ExcelUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * 车辆模板 ServiceImpl
 */
@Service
public class VehicleTemplateServiceImpl implements IVehicleTemplateService {

    private static final Logger log = LoggerFactory.getLogger(VehicleTemplateServiceImpl.class);

    @Value("${ocr.python.url}")
    private String pythonUrl;

    @Value("${ocr.callback.url}")
    private String callbackUrl;

    @Autowired
    private ExcelUtil excelUtil;

    @Autowired
    private RemoteFileService remoteFileService;

    @Autowired
    private VehicleTemplateMapper templateMapper;

    @Autowired
    private VehicleTemplateMaterialMapper materialMapper;

    @Autowired
    private IVehicleValidationService vehicleValidationService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final ExecutorService executor = Executors.newCachedThreadPool();

    private final Map<String, Sinks.Many<ServerSentEvent<String>>> sinks = new ConcurrentHashMap<>();

    @Autowired
    private RemoteDictService remoteDictService;

    @Autowired
    private AbnormalClassifyMapper abnormalClassifyMapper;

    @Autowired
    private RemoteNoticeService remoteNoticeService;

    @Autowired
    private IFirstVehicleCheckService firstVehicleCheckService;



    /**
     * 字典缓存：dictType → (dictLabel → dictValue)
     * 按 dictType 懒加载，首次使用时通过 remoteDictService 拉取并缓存，
     * 避免 DICT_MAP 规则每次转换都发起远程调用。
     */
    private final Map<String, Map<String, String>> dictCache = new ConcurrentHashMap<>();

    /**
     * Spring 容器初始化完成后，将带缓存的字典查找逻辑注入到 ValueMappingParser。
     * DICT_MAP 规则（如 value_map = "DICT_MAP:color"）执行时会回调此处，
     * 以 rawValue 作为 dict_label，在指定 dictType 中查找对应 dict_value。
     */
    @PostConstruct
    public void initDictProvider() {
        ValueMappingParser.setDictProvider((dictType, dictLabel) -> {
            Map<String, String> labelToValue = dictCache.computeIfAbsent(dictType, type -> {
                try {
                    List<SysDictData> list = remoteDictService.getDictDataByType(type).getData();
                    Map<String, String> map = new LinkedHashMap<>();
                    if (list != null) {
                        for (SysDictData d : list) {
                            if (StringUtils.isNotBlank(d.getDictLabel())) {
                                map.put(d.getDictLabel(), d.getDictValue());
                            }
                        }
                    }
                    return map;
                } catch (Exception e) {
                    log.error("[DictProvider] 加载字典失败 dictType={}: {}", type, e.getMessage());
                    return Collections.emptyMap();
                }
            });
            return labelToValue.get(dictLabel);
        });
        log.info("[DictProvider] ValueMappingParser 字典提供者已注入（带缓存）");
    }

    /**
     * 清除字典缓存。
     * 在字典数据变更后可主动调用，使下次 DICT_MAP 转换重新从 remoteDictService 拉取最新数据。
     *
     * @param dictType 指定类型清除；传 null 则清除全部缓存
     */
    @Override
    public void evictDictCache(String dictType) {
        if (dictType == null) {
            dictCache.clear();
            log.info("[DictProvider] 已清除全部字典缓存");
        } else {
            dictCache.remove(dictType);
            log.info("[DictProvider] 已清除字典缓存 dictType={}", dictType);
        }
    }

    @Override
    public List<VehicleTemplate> selectVehicleTemplateList(VehicleTemplate template) {
        return templateMapper.selectVehicleTemplateList(template);
    }

    @Override
    public List<VehicleTemplate> selectVehicleTemplateExpiringList() {
        return templateMapper.selectExpiringTemplates(null);
    }

    @Override
    public List<VehicleTemplate> selectVehicleTemplateEffectingList() {
        return templateMapper.selectEffectingTemplates();
    }

    @Override
    public VehicleTemplate selectVehicleTemplateById(Long templateId) {
        VehicleTemplate template = templateMapper.selectVehicleTemplateById(templateId);
        if (template != null) {
            template.setMaterialList(materialMapper.selectByTemplateId(templateId));
            String[] tvv = template.getTvv().split(",");
            template.setType(tvv[0]);
            template.setVariant(tvv[1]);
            template.setVersionNo(tvv[2]);

            // 解析 json 的每个 key，关联 vehicle_attribute 字典，
            // 查出对应的 otherLabel 和 otherLabelSystem 并挂载到实体
            String jsonStr = template.getJson();
            if (StringUtils.isNotBlank(jsonStr)) {
                try {
                    Map<String, Object> jsonMap = objectMapper.readValue(
                            jsonStr,
                            new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});

                    if (!jsonMap.isEmpty()) {
                        // 通过 Feign 获取 vehicle_attribute 全量字典
                        R<List<SysDictData>> dictResult =remoteDictService.getDictDataByType("vehicle_attribute");

                        if (dictResult != null && dictResult.getData() != null) {
                            // 转为 Map<dictLabel, SysDictData>，O(1) 查找
                            Map<String, SysDictData> dictLabelMap = dictResult.getData().stream()
                                    .filter(d -> StringUtils.isNotBlank(d.getDictLabel()))
                                    .collect(Collectors.toMap(
                                            SysDictData::getDictLabel,
                                            d -> d,
                                            (existing, replacement) -> existing
                                    ));

                            // 遍历 json 的每个 key，匹配字典，组装结果
                            Map<String, Map<String, String>> jsonDictMap = new LinkedHashMap<>();
                            for (String key : jsonMap.keySet()) {
                                SysDictData dictData = dictLabelMap.get(key);
                                Map<String, String> labels = new HashMap<>();
                                labels.put("otherLabel",       dictData != null ? dictData.getOtherLabel()       : null);
                                labels.put("otherLabelSystem", dictData != null ? dictData.getOtherLabelSystem() : null);
                                jsonDictMap.put(key, labels);
                            }
                            template.setOtherSystem(jsonDictMap);
                        }
                    }
                } catch (Exception e) {
                    log.warn("VehicleTemplate json 字典匹配失败, templateId={}", templateId, e);
                }
            }
        }
        return template;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int importVehicleTemplate(MultipartFile file) throws Exception {
        // TODO: 实现文件解析逻辑
        String json = parseFileToJson(file);
        VehicleTemplate t = new VehicleTemplate();
        t.setUuid(UUID.randomUUID().toString());
        t.setJson(json);
        t.setStatus("1");
        t.setValidateResult("1");
        t.setCreateTime(DateUtils.getNowDate());
        return templateMapper.insertVehicleTemplate(t);
    }

    private String parseFileToJson(MultipartFile file) throws Exception {
        return new String(file.getBytes());
    }

    @Override
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public int insertVehicleTemplate(VehicleTemplate template) {
        template.setUuid(UUID.randomUUID().toString());
        template.setVersion("1.0");
        template.setStatus("1");
        template.setValidateResult("1");
        template.setValidateTime(null);
        template.setValidateMsg(null);
        template.setCreateBy(SecurityUtils.getUsername());
        template.setCreateTime(DateUtils.getNowDate());
        template.setIsLast(1);
        if (!StringUtils.isBlank(template.getType()) && !StringUtils.isBlank(template.getVariant()) && !StringUtils.isBlank(template.getVersion())) {
            template.setTvv(template.getType() + "," + template.getVariant() + "," + template.getVersionNo());
        }
        int row = templateMapper.insertVehicleTemplate(template);
        batchValidate(template.getTemplateId());
        return row;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateVehicleTemplate(VehicleTemplate template) {
        VehicleTemplate existTemplate = templateMapper.selectVehicleByUuid(template.getUuid());
        String templateVersion = existTemplate.getVersion();
        if (templateVersion == null) {
            templateVersion = "1.0";
        } else {
            templateVersion = String.valueOf(new BigDecimal(templateVersion).add(new BigDecimal(1)));
        }
        template.setUuid(existTemplate.getUuid());
        template.setTemplateId(null);
        template.setStatus(existTemplate.getStatus());
        template.setVersion(templateVersion);
        template.setCreateBy(SecurityUtils.getUsername());
        template.setCreateTime(DateUtils.getNowDate());
        template.setTvv(template.getType() + "," + template.getVariant() + "," + template.getVersionNo());
        template.setValidateResult("1");
        template.setValidateTime(null);
        template.setValidateMsg(null);
        templateMapper.updateAllTemplateNotIsLast(template.getUuid());
        int rows = templateMapper.insertVehicleTemplate(template);
        if (rows > 0) {
            // 用 uuid 触发，而不是 templateId
            firstVehicleCheckService.handleAfterTemplateModified(template.getUuid());
        }
        return rows;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteVehicleTemplateByIds(Long[] templateIds) {
        List<Map<String, Object>> result = templateMapper.selectVehicleCountByTemplateIds(templateIds);

        // 按 templateId 分组，过滤掉 vin 为 null 的行（无关联车辆的模板）
        Map<Long, List<Map<String, Object>>> groupedByTemplate = result.stream()
                .filter(map -> map.get("vin") != null)
                .collect(Collectors.groupingBy(map -> ((Number) map.get("templateId")).longValue()));

        // 有关联车辆的 templateId 集合
        Set<Long> templateIdsWithVehicle = groupedByTemplate.keySet();

        // 拼接有关联车辆的提示信息
        if (!templateIdsWithVehicle.isEmpty()) {
            String messages = groupedByTemplate.values().stream()
                    .map(rows -> {
                        Map<String, Object> first = rows.get(0);
                        String wvtaCocNo     = String.valueOf(first.get("wvtaCocNo"));
                        String cocTemplateNo = String.valueOf(first.get("cocTemplateNo"));
                        String version       = String.valueOf(first.get("version"));
                        String vins          = rows.stream()
                                .map(r -> String.valueOf(r.get("vin")))
                                .collect(Collectors.joining("、"));
                        return String.format("wvtaCocNo为%s，COCNo为%s，版本号为%s的模版存在关联车辆，VIN为%s，删除失败",
                                wvtaCocNo, cocTemplateNo, version, vins);
                    })
                    .collect(Collectors.joining("\n"));
            throw new ServiceException(messages);
        }

        // 过滤出没有关联车辆的 templateId，只删除这部分
        Long[] deletableIds = Arrays.stream(templateIds)
                .filter(id -> !templateIdsWithVehicle.contains(id))
                .toArray(Long[]::new);

        materialMapper.deleteByTemplateIds(deletableIds);
        return templateMapper.deleteVehicleTemplateByIds(deletableIds);
    }

    @Override
    public int updateStatus(Long templateId, String status) {
        VehicleTemplate template = templateMapper.selectVehicleTemplateById(templateId);
        if (template == null) {
            throw new RuntimeException("该车辆模版不存在");
        }
        if (status.equals(template.getStatus())) {
            return 1;
        }
        if (template.getOverdueDate() != null && status.equals("0") && new Date().after(template.getOverdueDate())) {
            throw new RuntimeException("已超过失效时间，操作无法执行。如需启用该模版请先修改失效时间后重试");
        }
        return templateMapper.updateStatus(templateId, status);
    }

    @Override
    public List<ValidationReport> batchValidate(Long... templateIds) {
        if (templateIds == null || templateIds.length == 0) {
            return Collections.emptyList();
        }

        List<ValidationReport> reports = new ArrayList<>();
        List<VehicleTemplate> updateList = new ArrayList<>();
        List<AbnormalClassify> abnormalClassifies = new ArrayList<>();
        AbnormalClassify abnormalClassify;

        List<VehicleTemplate> templates = new LinkedList<>();
        List<Long> notExistIds = new LinkedList<>();

        for (Long templateId : templateIds) {
            VehicleTemplate vehicleTemplate = templateMapper.selectVehicleTemplateById(templateId);
            if (vehicleTemplate == null) {
                log.warn("模板不存在, templateId = {}", templateId);
                notExistIds.add(templateId);
                continue;
            }
            templates.add(vehicleTemplate);
        }
        if (!notExistIds.isEmpty()) {
            StringBuilder msg = new StringBuilder("模版");
            for (int i = 0; i < notExistIds.size(); i++) {
                msg.append(notExistIds.get(i));
                if (i != notExistIds.size() - 1) {
                    msg.append(",");
                }
            }
            msg.append("不存在");
            throw new ServiceException(msg.toString());
        }
        for (VehicleTemplate template : templates) {
            try {
                // 执行校验
                ValidationReport report = vehicleValidationService.validate(
                        template.getJson(),
                        template.getVehicleType(),
                        null
                );

                for (FieldValidationResult fieldValidationResult: report.getFieldResults()) {
                    for (RuleViolation ruleViolation: fieldValidationResult.getViolations()) {
                        abnormalClassify = new AbnormalClassify();
                        abnormalClassify.setEntryId(String.valueOf(template.getTemplateId()));
                        abnormalClassify.setEntryType("Vehicle Template");
                        abnormalClassify.setRuleType(RuleItemType.getRuleType(ruleViolation.getRuleType()));
                        abnormalClassifies.add(abnormalClassify);
                    }
                }

                reports.add(report);

                // 组装回写数据
                VehicleTemplate update = new VehicleTemplate();
                update.setTemplateId(template.getTemplateId());
                update.setValidateResult(report.isAllValid() ? "1" : "2");
                try {
                    update.setValidateMsg(objectMapper.writeValueAsString(report.getFailedFields()));
                } catch (Exception e) {
                    update.setValidateMsg("序列化失败");
                }
                updateList.add(update);

                Map<String, String> params = new HashMap<>();
                params.put("wvtaCocNo", template.getWvtaCocNo());
                params.put("cocTemplateNo", template.getCocTemplateNo());
                params.put("modelNo", template.getModelNo());
                params.put("vehicleType", template.getVehicleType());
                params.put("validationResult", report.isAllValid() ? "1" : "2");
                SysNotice sysNotice = new SysNotice();
                sysNotice.setModel(SysNoticeModel.VEHICLE_TEMPLATE.getModel());
                sysNotice.setQueryParams(JSON.toJSONString(params));
                sysNotice.setIsRead(false);
                sysNotice.setNoticeType("1");
                sysNotice.setNoticeTitle("车辆信息模版校验完成通知");
                String msg =
                        "WVTA编号 " +
                                template.getWvtaCocNo() +
                                " 、COC编号 "+
                                template.getCocTemplateNo() +
                                " 、版本 "+
                                template.getVersion() +
                                " 的校验结果为: " +
                                (report.isAllValid() ? "通过" : "失败");
                sysNotice.setNoticeContent(msg);
                sysNotice.setCreateBy("自动提醒");
                sysNotice.setCreateTime(new Date());
                sysNotice.setSorts(Arrays.asList(8, 9));
                remoteNoticeService.innerAdd(sysNotice);
            } catch (Exception e) {
                log.error("校验异常, templateId={}", template.getTemplateId(), e);
                reports.add(ValidationReport.builder()
                        .allValid(false)
                        .error("校验异常：" + e.getMessage()).build());
            }
        }

        if (!abnormalClassifies.isEmpty()) {
            abnormalClassifyMapper.batchInsert(abnormalClassifies);
        }

        // 批量回写校验结果
        if (!updateList.isEmpty()) {
            templateMapper.batchUpdateValidateResult(updateList);
        }
        return reports;
    }

    // === 物料号维护 ===
    @Override
    public List<VehicleTemplateMaterial> selectMaterialByTemplateId(Long templateId) {
        return materialMapper.selectByTemplateId(templateId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int saveMaterialList(Long templateId, List<VehicleTemplateMaterial> materialList) {
        materialMapper.deleteByTemplateId(templateId);
        if (materialList == null || materialList.isEmpty()) return 0;
        materialList.forEach(m -> {
            m.setTemplateId(templateId);
            m.setCreateTime(DateUtils.getNowDate());
        });
        return materialMapper.batchInsert(materialList);
    }

    @Override
    public Flux<ServerSentEvent<String>> importPdf(MultipartFile file) {
        String taskId = UUID.randomUUID().toString();
        Sinks.Many<ServerSentEvent<String>> sink = Sinks.many().unicast().onBackpressureBuffer();
        sinks.put(taskId, sink);

        try {
            byte[] fileBytes = file.getBytes();
            String fileName = file.getOriginalFilename();

            try {
                String filePath = remoteFileService.upload(file).getData().getUrl();
                sendProgress(taskId, new HashMap<String, Object>() {{
                    put("process", 0);
                    put("message", "文件上传中...");
                    put("filePath", filePath);
                }}); ;
                log.info("文件保存成功, taskId={}, filePath={}", taskId, filePath);
            } catch (Exception e) {
                log.error("文件保存失败, taskId={}", taskId, e);
                return null;
            }

            executor.execute(() -> {
                try {
                    // 先发一条初始消息
                    sendProgress(taskId, new HashMap<String, Object>() {{
                        put("progress", 10);
                        put("message", "文件上传中...");
                    }}) ;

                    RestTemplate restTemplate = new RestTemplate();
                    MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
                    body.add("file", new ByteArrayResource(fileBytes) {
                        @Override
                        public String getFilename() { return fileName; }
                    });
                    body.add("callback_url", callbackUrl);
                    body.add("task_id", taskId);

                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentType(MediaType.MULTIPART_FORM_DATA);

                    restTemplate.postForEntity(pythonUrl,new HttpEntity<>(body, headers), String.class);

                    sendProgress(taskId, new HashMap<String, Object>() {{
                        put("progress", 20);
                        put("message", "Python处理中...");
                    }});

                } catch (Exception e) {
                    log.error("发送到Python失败", e);
                    sendError(taskId, new HashMap<String, Object>() {{put("message", e.getMessage());}});
                }
            });

        } catch (IOException e) {
            log.error("读取文件失败", e);
            sendError(taskId, new HashMap<String, Object>() {{put("message", "读取文件失败: " + e.getMessage());}});
        }

        // 返回 Flux，连接断开时自动清理
        return sink.asFlux()
                .doOnCancel(() -> {
                    log.info("客户端断开连接, taskId: {}", taskId);
                    sinks.remove(taskId);
                })
                .doOnComplete(() -> {
                    log.info("SSE完成, taskId: {}", taskId);
                    sinks.remove(taskId);
                });
    }

    @Override
    public Flux<ServerSentEvent<String>> importExcel(MultipartFile file) {
        String taskId = UUID.randomUUID().toString();
        Sinks.Many<ServerSentEvent<String>> sink = Sinks.many().unicast().onBackpressureBuffer();
        sinks.put(taskId, sink);

        try {
            List<VehicleTemplate> vehicleTemplates = excelUtil.importExcel(file.getInputStream(), "vehicle_template", VehicleTemplate.class, 1);
            List<SysDictData> vehicleModels = remoteDictService.getDictDataByType("vehicle_model").getData();
            // 建立 label -> dictCode 映射，避免双重循环
            Map<String, String> labelToCodeMap = vehicleModels.stream()
                    .collect(Collectors.toMap(
                            SysDictData::getDictLabel,
                            SysDictData::getDictValue,
                            (k1, k2) -> k1
                    ));
            List<SysDictData> vehicleAttribute = remoteDictService.getDictDataByType("vehicle_attribute").getData();
            Map<String, String> keyMap = vehicleAttribute.stream()
                    .filter(item -> item.getKeyMap() != null)
                    .collect(Collectors.toMap(
                            SysDictData::getDictLabel,
                            SysDictData::getKeyMap,
                            (k1, k2) -> k1
                    ));

            // 遍历替换 vehicleType
            vehicleTemplates.forEach(template -> {
                String vehicleType = template.getVehicleType();
                if (vehicleType != null && labelToCodeMap.containsKey(vehicleType)) {
                    template.setVehicleType(labelToCodeMap.get(vehicleType));
                } else {
                    log.warn("vehicleType [{}] 未在字典 vehicle_model 中找到对应 dictCode", vehicleType);
                }
                template.setUuid(UUID.randomUUID().toString());
                template.setVersion("1.0");
                template.setStatus("1");
                template.setValidateResult("1");
                template.setCreateBy(SecurityUtils.getUsername());
                template.setCreateTime(DateUtils.getNowDate());
                // Excel 解析后立即对 json 做字段映射，模板保存映射后的值
                // VehicleInfo 后续直接复用此 json，无需再次转换
                String mappedJson = jsonConvertFromTemplateJson(template.getJson());
                template.setJson(mappedJson);
                Map<String, String> jsonMap = JSONObject.parseObject(mappedJson, new TypeReference<Map<String, String>>() {});
                template.setTvv(jsonMap.get("Type") + "," + jsonMap.get("Variant") + "," + jsonMap.get("Version"));
                templateMapper.insertVehicleTemplate(template);
            });
        } catch (Exception e) {
            log.error("文件导入失败, taskId={}", taskId, e);
            return null;
        }

        return sink.asFlux()
                .doOnCancel(() -> {
                    log.info("客户端断开连接, taskId: {}", taskId);
                    sinks.remove(taskId);
                })
                .doOnComplete(() -> {
                    log.info("SSE完成, taskId: {}", taskId);
                    sinks.remove(taskId);
                });
    }

    /**
     * OCR识别进度回调
     */
    public void sendProgress(String taskId, Map<String, Object> data) {
        Sinks.Many<ServerSentEvent<String>> sink = sinks.get(taskId);
        if (sink == null) {
            log.warn("sink不存在, taskId: {}", taskId);
            return;
        }
        try {
            data = new HashMap<>(data);
            data.put("type", "progress");
            String json = objectMapper.writeValueAsString(data);

            ServerSentEvent<String> event = ServerSentEvent.<String>builder()
                    .event("progress")
                    .data(json)
                    .build();

            // 发射事件
            Sinks.EmitResult result = sink.tryEmitNext(event);
            log.info("发送进度, taskId: {}, result: {}, data: {}", taskId, result, json);

        } catch (Exception e) {
            log.error("发送进度失败", e);
        }
    }

    /**
     * OCR识别完成回调
     */
    public void sendComplete(String taskId, Map<String, Object> data) {
        Sinks.Many<ServerSentEvent<String>> sink = sinks.get(taskId);
        if (sink == null) return;
        try {
            data = new HashMap<>(data);
            data.put("type", "complete");
            String json = objectMapper.writeValueAsString(data);

            ServerSentEvent<String> event = ServerSentEvent.<String>builder()
                    .event("complete")
                    .data(json)
                    .build();

            sink.tryEmitNext(event);
            sink.tryEmitComplete();
            sinks.remove(taskId);

        } catch (Exception e) {
            log.error("发送完成失败", e);
            sink.tryEmitError(e);
            sinks.remove(taskId);
        }
    }

    /**
     * OCR识别错误回调
     */
    public void sendError(String taskId, Map<String, Object> data) {
        Sinks.Many<ServerSentEvent<String>> sink = sinks.get(taskId);
        if (sink == null) return;
        try {
            data = new HashMap<>(data);
            data.put("type", "error");
            String json = objectMapper.writeValueAsString(data);

            ServerSentEvent<String> event = ServerSentEvent.<String>builder()
                    .event("error")
                    .data(json)
                    .build();

            sink.tryEmitNext(event);
            sink.tryEmitComplete();
            sinks.remove(taskId);

        } catch (Exception e) {
            log.error("发送错误失败", e);
            sink.tryEmitError(e);
            sinks.remove(taskId);
        }
    }

    public List<VehicleTemplate> selectVehicleTemplateOption() {
        return templateMapper.selectVehicleTemplateOption();
    }

    @Override
    public List<VehicleTemplate> historyVersion(VehicleTemplate template) {
        Long templateId = template.getTemplateId();
        VehicleTemplate vehicleTemplate = templateMapper.selectVehicleTemplateById(templateId);
        VehicleTemplate query = new VehicleTemplate();
        query.setIsLast(0);
        query.setUuid(vehicleTemplate.getUuid());
        return templateMapper.selectVehicleTemplateList(query);
    }

    /**
     * 将模板原始 JSON 字符串按 vehicle_attribute 字典规则做字段映射，返回映射后的 JSON 字符串。
     * 在 Excel 导入解析完成后立即调用，使模板保存的就是映射后的值；
     * VehicleInfo 后续直接复用此 json，无需再次转换。
     *
     * @param templateJson 原始 JSON 字符串（映射前）
     * @return 映射后的 JSON 字符串
     */
    public String jsonConvertFromTemplateJson(String templateJson) {
        Map<String, Object> map = null;
        if (StringUtils.isNotBlank(templateJson)) {
            try {
                map = objectMapper.readValue(templateJson,
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                throw new RuntimeException("模板 JSON 解析失败: " + e.getMessage());
            }
        }
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

                    // 含 \n 的值按行拆分，每行分别匹配链中对应 dictLabel
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
                        // 单链但多行：整体传入链处理，\n 替换成 | 以匹配 eCoC 多值格式
                        List<SysDictData> singleChain = chains.get(0);
                        String converted = applyChain(rawValue, singleChain, fieldName);
                        String targetLabel = singleChain.get(singleChain.size() - 1).getDictLabel();
                        if (converted != null && converted.contains("\n")) {
                            converted = converted.replace("\n", "|");
                        }
                        if (StringUtils.isNotBlank(converted)) {
                            result.put(targetLabel, converted);
                        }
                    } else {
                        // 单行单链
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
                // 多 key_map 链里每一段都独立写到自己的 dictLabel
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
                                continue;
                            }
                            if (stepped != null) {
                                converted = stepped;
                            } else {
                                log.warn("[jsonConvertFromTemplateJson] 多key_map链转换返回 null，dict_code={}, keyMap={}",
                                        rule.getDictCode(), rule.getKeyMap());
                            }
                        }
                        converted = StringUtils.isNotBlank(converted) ? converted : rawValue;
                        converted = "N/A".equals(converted) ? "" : converted;
                        if (StringUtils.isNotBlank(converted)) {
                            result.put(rule.getDictLabel(), converted);
                        }
                    }
                }

                finalMap = result;
            }
        }

        try {
            return objectMapper.writeValueAsString(finalMap != null ? finalMap : new LinkedHashMap<>());
        } catch (Exception e) {
            throw new RuntimeException("模板 JSON 映射后序列化失败: " + e.getMessage());
        }
    }

    /**
     * 对单条链执行链式转换，返回最终值。
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
                log.warn("[applyChain] value_map 链式第 {} 步返回 null，终止。dict_code={}, fieldName={}",
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
