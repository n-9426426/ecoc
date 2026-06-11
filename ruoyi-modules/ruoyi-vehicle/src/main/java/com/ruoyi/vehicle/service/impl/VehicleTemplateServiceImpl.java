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
import com.ruoyi.vehicle.domain.Material;
import com.ruoyi.vehicle.domain.VehicleTemplate;
import com.ruoyi.vehicle.domain.vo.VehicleJsonKeyVo;
import com.ruoyi.vehicle.mapper.AbnormalClassifyMapper;
import com.ruoyi.vehicle.mapper.MaterialMapper;
import com.ruoyi.vehicle.mapper.VehicleTemplateMapper;
import com.ruoyi.vehicle.service.IFirstVehicleCheckService;
import com.ruoyi.vehicle.service.IVehicleTemplateService;
import com.ruoyi.vehicle.service.IVehicleValidationService;
import com.ruoyi.vehicle.utils.ExcelUtil;
import org.apache.poi.ss.usermodel.*;
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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.CompletableFuture;
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

    @Autowired
    private MaterialMapper materialMapper;

    @Override
    public List<VehicleTemplate> selectVehicleTemplateList(VehicleTemplate template) {
        template.setIsLast(1);
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
            String[] tvv = template.getTvv().split(",");
            if (tvv.length > 2) {
                template.setType(tvv[0]);
                template.setVariant(tvv[1]);
                template.setVersionNo(tvv[2]);
            }
            template.setTvv(template.getTvv().replace(",", ""));

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
                        R<List<SysDictData>> dictResult = remoteDictService.getDictDataByType("vehicle_attribute");

                        if (dictResult != null && dictResult.getData() != null) {
                            // 同一 dict_label 可能对应多条记录（每条记录有独立的 original_system 和 key_map）
                            // 使用 groupingBy 收集同一 dict_label 下的所有 SysDictData
                            Map<String, List<SysDictData>> dictLabelMap = dictResult.getData().stream()
                                    .filter(d -> StringUtils.isNotBlank(d.getDictLabel()))
                                    .collect(Collectors.groupingBy(SysDictData::getDictLabel));

                            // 遍历 json 的每个 key，匹配字典，组装结果
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
        template.setVersion(StringUtils.isBlank(template.getVersion()) ? "1.0" : template.getVersion());
        template.setStatus(StringUtils.isBlank(template.getStatus()) ? "1" : template.getStatus());
        template.setValidateResult(StringUtils.isBlank(template.getValidateResult()) ? "1" : template.getValidateResult());
        template.setValidateTime(null);
        template.setValidateMsg(null);
        template.setCreateBy(SecurityUtils.getUsername());
        template.setCreateTime(DateUtils.getNowDate());
        template.setIsLast(1);
        String mappedJson = jsonConvertFromTemplateJson(template.getJson());
        template.setJson(mappedJson);
        Map<String, String> jsonMap = JSONObject.parseObject(
                mappedJson, new TypeReference<Map<String, String>>() {});
        if (jsonMap.get("Type") == null && jsonMap.get("Variant") == null && jsonMap.get("Version") == null) {
            template.setTvv(StringUtils.isBlank(template.getTvv()) ? "" : template.getTvv());
        } else {
            template.setTvv(jsonMap.get("Type") + "," + jsonMap.get("Variant") + "," + jsonMap.get("Version"));
        }
        int row = templateMapper.insertVehicleTemplate(template);
        batchValidate(template.getTemplateId());
        return row;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateVehicleTemplate(VehicleTemplate template) {
        VehicleTemplate existTemplate = templateMapper.selectVehicleByUuid(template.getUuid());
        if (existTemplate == null) {
            throw new RuntimeException("该模版不存在，无法更新");
        }
        String templateVersion = existTemplate.getVersion();
        if (template.getVersion() == null) {
            if (templateVersion == null) {
                templateVersion = "1.0";
            } else {
                templateVersion = String.valueOf(new BigDecimal(templateVersion).add(new BigDecimal(1)));
            }
        } else {
            templateVersion = template.getVersion();
        }
        template.setUuid(existTemplate.getUuid());
        template.setTemplateId(null);
        template.setStatus(existTemplate.getStatus());
        template.setVersion(templateVersion);
        template.setCreateBy(SecurityUtils.getUsername());
        template.setCreateTime(DateUtils.getNowDate());
        template.setTvv(template.getType() + "," + template.getVariant() + "," + template.getVersionNo());
        template.setValidateResult(StringUtils.isBlank(template.getValidateResult()) ? "1" : template.getValidateResult());
        template.setValidateTime(null);
        template.setValidateMsg(null);
        String mappedJson = jsonConvertFromTemplateJson(template.getJson());
        template.setJson(mappedJson);
        Map<String, String> jsonMap = JSONObject.parseObject(
                mappedJson, new TypeReference<Map<String, String>>() {});
        template.setTvv(jsonMap.get("Type") + "," + jsonMap.get("Variant") + "," + jsonMap.get("Version"));
        template.setIsLast(1);
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
        List<Map<String, Object>> vehicles = templateMapper.selectVehicleCountByTemplateIds(templateIds);
        // 按 templateId 分组，过滤掉 vin 为 null 的行（无关联车辆的模板）
        Map<Long, List<Map<String, Object>>> groupedByTemplate = vehicles.stream()
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

        Material query = new Material();
        query.setVehicleTemplateIds(Arrays.asList(templateIds));
        List<Material> materials = materialMapper.selectMaterialList(query);
        // 按 templateId 分组
        Map<Long, List<Material>> groupedByMaterial = materials.stream()
                .collect(Collectors.groupingBy(material -> ((Number) material.getVehicleTemplateId()).longValue()));
        // 有关联物料号的 templateId 集合
        Set<Long> templateIdsWithMaterial = groupedByMaterial.keySet();
        // 拼接有关联物料号的提示信息
        if (!templateIdsWithMaterial.isEmpty()) {
            String messages = groupedByMaterial.values().stream()
                    .map(rows -> {
                        List<Material> first = rows;
                        String wvtaCocNo     = String.valueOf(rows.get(0).getWvtaCocNo());
                        String cocTemplateNo = String.valueOf(rows.get(0).getCocTemplateNo());
                        String version       = String.valueOf(rows.get(0).getVersion());
                        String materialNos          = rows.stream()
                                .map(Material::getMaterialNo)
                                .collect(Collectors.joining("、"));
                        return String.format("wvtaCocNo为%s，COCNo为%s，版本号为%s的模版存在关联物料号，物料号为%s，删除失败",
                                wvtaCocNo, cocTemplateNo, version, materialNos);
                    })
                    .collect(Collectors.joining("\n"));
            throw new ServiceException(messages);
        }

        // 过滤出没有关联车辆的 templateId，只删除这部分
        Long[] deletableIds = Arrays.stream(templateIds)
                .filter(id -> !templateIdsWithVehicle.contains(id))
                .toArray(Long[]::new);

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
                params.put("id", String.valueOf(template.getTemplateId()));
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
                pushEvent(sink, "error", String.format(
                        "{\"message\":\"文件保存失败: %s\"}", escapeJson(e.getMessage())));
                sink.tryEmitComplete();
                sinks.remove(taskId);
                return sink.asFlux();
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
    public String submitImportTask(MultipartFile file) {
        String taskId = UUID.randomUUID().toString();
        Sinks.Many<ServerSentEvent<String>> sink = Sinks.many().unicast().onBackpressureBuffer();
        sinks.put(taskId, sink);

        // 文件内容需要在异步线程前读出，避免请求结束后流关闭
        byte[] fileBytes;
        try {
            fileBytes = file.getBytes();
        } catch (Exception e) {
            sinks.remove(taskId);
            throw new ServiceException("文件读取失败: " + e.getMessage());
        }
        String currentUser = SecurityUtils.getUsername();
        // 在请求线程提前解析语言，避免异步线程中 RequestContextHolder 为 null
        String lang = excelUtil.resolveCurrentLang();
        CompletableFuture.runAsync(() -> doImport(taskId, fileBytes, currentUser, lang));

        return taskId;
    }

    @Override
    public Flux<ServerSentEvent<String>> getImportFlux(String taskId) {
        Sinks.Many<ServerSentEvent<String>> sink = sinks.get(taskId);
        if (sink == null) {
            // taskId 不存在或已过期，直接返回一个 error 事件后结束
            return Flux.just(ServerSentEvent.<String>builder()
                    .event("error")
                    .data("{\"message\":\"任务不存在或已过期\"}")
                    .build());
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
     * 实际导入逻辑，在异步线程中执行
     */
    private void doImport(String taskId, byte[] fileBytes, String createBy, String lang) {
        Sinks.Many<ServerSentEvent<String>> sink = sinks.get(taskId);
        if (sink == null) return;

        int successCount = 0;
        int failCount = 0;
        List<String> errorDetails = new ArrayList<>();

        try {
            List<VehicleTemplate> vehicleTemplates = excelUtil.importExcel(
                    new ByteArrayInputStream(fileBytes), "vehicle_template", VehicleTemplate.class, lang, 3);

            Map<String, String> labelToCodeMap = remoteDictService
                    .getDictDataByType("vehicle_model").getData().stream()
                    .collect(Collectors.toMap(
                            SysDictData::getDictLabel,
                            SysDictData::getDictValue,
                            (k1, k2) -> k1));

            // vehicle_attribute 字典：dictLabel -> keyMap，用于校验非配置列头的映射关系
            Map<String, String> keyMapToLabelMap = remoteDictService
                    .getDictDataByType("vehicle_attribute").getData().stream()
                    .filter(item -> item.getKeyMap() != null)
                    .collect(Collectors.toMap(
                            SysDictData::getKeyMap,
                            SysDictData::getDictLabel,
                            (k1, k2) -> k1));

            Set<String> skipHeaders = remoteDictService
                    .getDictDataByType("excel_skip_column").getData().stream()
                    .map(SysDictData::getDictValue)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            if (!vehicleTemplates.isEmpty()) {
                String firstJson = vehicleTemplates.get(0).getJson();
                if (firstJson != null && !firstJson.trim().isEmpty()) {
                    Map<String, String> firstJsonMap = JSONObject.parseObject(
                            firstJson, new TypeReference<Map<String, String>>() {});
                    List<String> unmappedHeaders = firstJsonMap.keySet().stream()
                            .filter(header -> !skipHeaders.contains(header))        // 白名单内直接跳过
                            .filter(header -> !keyMapToLabelMap.containsKey(header)) // 再校验是否有映射
                            .collect(Collectors.toList());
                    if (!unmappedHeaders.isEmpty()) {
                        String errorMsg = "导入终止：以下列头在 数据字段 中未找到映射关系，请补充配置后重试："
                                + String.join("、", unmappedHeaders);
                        log.warn("导入预校验失败, taskId={}, 未映射列头={}", taskId, unmappedHeaders);
                        pushEvent(sink, "error", String.format(
                                "{\"message\":\"%s\"}", escapeJson(errorMsg)));
                        sink.tryEmitComplete();
                        sinks.remove(taskId);
                        return;
                    }
                }
            }
            // ===================== 预校验结束 =====================

            int total = vehicleTemplates.size();

            for (int i = 0; i < total; i++) {
                VehicleTemplate template = vehicleTemplates.get(i);
                int rowNum = i + 2;

                List<String> missingFields = new ArrayList<>();
                if (StringUtils.isBlank(template.getCocTemplateNo())) missingFields.add("COC Template No.");
                if (StringUtils.isBlank(template.getWvtaCocNo()))     missingFields.add("WVTA-COC No.");
                if (StringUtils.isBlank(template.getVersion()))       missingFields.add("Version");
                if (StringUtils.isBlank(template.getVehicleType()))   missingFields.add("Vehicle category");
                if (template.getEffectiveDate() == null)              missingFields.add("Effective date");

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
                    String vehicleType = template.getVehicleType();
                    if (vehicleType == null || !labelToCodeMap.containsKey(vehicleType)) {
                        throw new IllegalArgumentException(
                                "vehicleType [" + vehicleType + "] 未在字典 vehicle_model 中找到对应 dictCode");
                    }
                    template.setVehicleType(labelToCodeMap.get(vehicleType));
                    template.setUuid(UUID.randomUUID().toString());
                    String statusRaw = template.getStatus();
                    if ("No".equalsIgnoreCase(statusRaw)) {
                        template.setStatus("1");
                    } else if ("Yes".equalsIgnoreCase(statusRaw)) {
                        template.setStatus("0");
                    } else {
                        template.setStatus("1"); // 默认兜底
                    }
                    String validateRaw = template.getValidateResult();
                    if (validateRaw == null
                            || validateRaw.trim().isEmpty()
                            || "N/A".equalsIgnoreCase(validateRaw.trim())
                            || "NULL".equalsIgnoreCase(validateRaw.trim())
                            || "No".equalsIgnoreCase(validateRaw.trim())) {
                        template.setValidateResult("0");
                    } else if ("Yes".equalsIgnoreCase(validateRaw.trim())) {
                        template.setValidateResult("1");
                    } else {
                        template.setValidateResult("0"); // 未知值兜底
                    }
                    String generateRaw = template.getGenerateAffirmRaw();
                    if ("Yes".equalsIgnoreCase(generateRaw)) {
                        template.setGenerateAffirm(1);
                    } else if ("No".equalsIgnoreCase(generateRaw)) {
                        template.setGenerateAffirm(0);
                    } else {
                        template.setGenerateAffirm(0);
                    }
                    String uploadRaw = template.getUploadAffirmRaw();
                    if ("Yes".equalsIgnoreCase(uploadRaw)) {
                        template.setUploadAffirm(1);
                    } else if ("No".equalsIgnoreCase(uploadRaw)) {
                        template.setUploadAffirm(0);
                    } else {
                        template.setUploadAffirm(0);
                    }
                    template.setCreateBy(createBy);
                    template.setCreateTime(DateUtils.getNowDate());

                    String mappedJson = jsonConvertFromTemplateJson(template.getJson());
                    template.setJson(filterJsonByVehicleAttribute(mappedJson));
                    Map<String, String> jsonMap = JSONObject.parseObject(
                            mappedJson, new TypeReference<Map<String, String>>() {});
                    template.setTvv(jsonMap.get("Type") + "," + jsonMap.get("Variant") + "," + jsonMap.get("Version"));

                    templateMapper.insertVehicleTemplate(template);
                    successCount++;

                    pushEvent(sink, "progress", String.format(
                            "{\"row\":%d,\"total\":%d,\"status\":\"success\"}", rowNum, total));

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

        String detailJson = errorDetails.stream()
                .map(s -> "\"" + escapeJson(s) + "\"")
                .collect(Collectors.joining(",", "[", "]"));

        pushEvent(sink, "complete", String.format(
                "{\"successCount\":%d,\"failCount\":%d,\"errorDetails\":%s}",
                successCount, failCount, detailJson));

        sink.tryEmitComplete();
        sinks.remove(taskId);
    }

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
    public List<VehicleTemplate> historyVehicleTemplate(VehicleTemplate template) {
        Long templateId = template.getTemplateId();
        VehicleTemplate vehicleTemplate = templateMapper.selectVehicleTemplateById(templateId);

        // 1. 获取历史记录列表
        VehicleTemplate query = new VehicleTemplate();
        query.setIsLast(0);
        query.setUuid(vehicleTemplate.getUuid());
        List<VehicleTemplate> vehicleTemplateList = templateMapper.selectVehicleTemplateList(query);

        ObjectMapper objectMapper = new ObjectMapper();

        try {
            // 2. 解析基准（isLast=1）JSON
            Map<String, Object> baseMap = objectMapper.readValue(
                    vehicleTemplate.getJson(),
                    objectMapper.getTypeFactory().constructMapType(HashMap.class, String.class, Object.class)
            );

            // 3. 解析所有历史记录的 JSON
            List<Map<String, Object>> historyMapList = new ArrayList<>();
            for (VehicleTemplate vt : vehicleTemplateList) {
                Map<String, Object> jsonMap = objectMapper.readValue(
                        vt.getJson(),
                        objectMapper.getTypeFactory().constructMapType(HashMap.class, String.class, Object.class)
                );
                historyMapList.add(jsonMap);
            }

            // 4. 处理历史记录的 diffFields（与基准不同的字段，存自己的值）
            for (int i = 0; i < vehicleTemplateList.size(); i++) {
                VehicleTemplate vt = vehicleTemplateList.get(i);
                Map<String, Object> currMap = historyMapList.get(i);
                Map<String, Object> diffFields = new HashMap<>();

                for (String key : baseMap.keySet()) {
                    Object baseVal = baseMap.get(key);
                    Object currVal = currMap.get(key);
                    if (!Objects.equals(normalizeValue(baseVal), normalizeValue(currVal))) {
                        diffFields.put(key, currVal);
                    }
                }
                // 历史记录中有但基准没有的 key
                for (String key : currMap.keySet()) {
                    if (!baseMap.containsKey(key)) {
                        diffFields.put(key, currMap.get(key));
                    }
                }

                vt.setDiffFields(diffFields);
            }

            // 5. 处理基准（isLast=1）的 diffFields
            //    找出基准中：在任意历史记录里值不同或不存在的字段
            Map<String, Object> baseDiffFields = new HashMap<>();
            for (String key : baseMap.keySet()) {
                Object baseVal = baseMap.get(key);
                boolean isDiff = historyMapList.stream().anyMatch(histMap -> {
                    Object histVal = histMap.get(key);
                    return !Objects.equals(normalizeValue(baseVal), normalizeValue(histVal));
                });
                if (isDiff) {
                    baseDiffFields.put(key, baseVal);
                }
            }
            vehicleTemplate.setDiffFields(baseDiffFields);

            // 6. 将基准插入列表一起返回
            vehicleTemplateList.add(vehicleTemplate);

        } catch (Exception e) {
            log.error("解析 json 失败, templateId: {}", vehicleTemplate.getTemplateId(), e);
        }

        return vehicleTemplateList;
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

                // DEBUG: print a sample of records to see actual originalSystem values
                log.info("[jsonConvert DEBUG] dictDataList size={}", dictDataList.size());
                dictDataList.stream()
                        .filter(d -> d.getKeyMap() != null && (
                                d.getKeyMap().contains("0.2.a") ||
                                        d.getKeyMap().contains("Type") ||
                                        d.getKeyMap().contains("Commercial") ||
                                        d.getKeyMap().contains("customerNo")))
                        .forEach(d -> log.info("[jsonConvert DEBUG] record: dictCode={}, label={}, keyMap={}, originalSystem=[{}], uuid={}",
                                d.getDictCode(), d.getDictLabel(), d.getKeyMap(),
                                d.getOriginalSystem(), d.getUuid()));

                // ── 第一步：按 uuid 分组，无 uuid 的每条独立 ──────────────────
                Map<String, List<SysDictData>> uuidGroups = new LinkedHashMap<>();
                for (SysDictData rule : dictDataList) {
                    String groupKey = StringUtils.isNotBlank(rule.getUuid())
                            ? rule.getUuid()
                            : "$$solo$$" + rule.getDictCode();
                    uuidGroups.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(rule);
                }

                // ── 第二步：识别单 key_map 链 vs 多 key_map 链 ────────────────
                // 对于 COC 模版导入，keyMap 索引只取 originalSystem 以 "COC模版_" 开头的那条记录，
                // 以确保用 COC Excel 的列头去匹配，而不是 MES 或其他系统的列头。
                Map<String, List<List<SysDictData>>> keyToChains = new LinkedHashMap<>();
                Map<String, List<SysDictData>> multiKeyChainMap = new LinkedHashMap<>();

                for (Map.Entry<String, List<SysDictData>> groupEntry : uuidGroups.entrySet()) {
                    List<SysDictData> chain = groupEntry.getValue();
                    chain.sort(Comparator.comparingLong(SysDictData::getDictCode));

                    // 在 chain 中找出 originalSystem 以 "COC模版_" 开头的记录，取其 keyMap 作为索引
                    long distinctKeyMapCount = chain.stream()
                            .filter(d -> StringUtils.isNotBlank(d.getOriginalSystem())
                                    && d.getOriginalSystem().startsWith("COC模版_"))
                            .map(SysDictData::getKeyMap)
                            .filter(StringUtils::isNotBlank)
                            .distinct()
                            .count();

                    if (distinctKeyMapCount > 1) {
                        multiKeyChainMap.put(groupEntry.getKey(), chain);
                    } else {
                        String keyMap = chain.stream()
                                .filter(d -> StringUtils.isNotBlank(d.getOriginalSystem())
                                        && d.getOriginalSystem().startsWith("COC模版_"))
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
                            if (ValueMappingParser.EMPTY_SENTINEL.equals(converted)) {
                                result.put(targetLabel, null);
                            } else if (StringUtils.isNotBlank(converted)) {
                                result.put(targetLabel, converted);
                            }
                        }
                    } else if (isMultiLine && chains.size() == 1) {
                        // 单链但多行：整体传入链处理，\n 替换成 | 以匹配 eCoC 多值格式
                        List<SysDictData> singleChain = chains.get(0);
                        String converted = applyChain(rawValue, singleChain, fieldName);
                        String targetLabel = singleChain.get(singleChain.size() - 1).getDictLabel();
                        if (ValueMappingParser.EMPTY_SENTINEL.equals(converted)) {
                            result.put(targetLabel, null);
                        } else if (StringUtils.isNotBlank(converted)) {
                            result.put(targetLabel, converted);
                        }
                    } else {
                        // 单行单链
                        for (List<SysDictData> singleChain : chains) {
                            String converted = applyChain(rawValue, singleChain, fieldName);
                            String targetLabel = singleChain.get(singleChain.size() - 1).getDictLabel();
                            if (ValueMappingParser.EMPTY_SENTINEL.equals(converted)) {
                                result.put(targetLabel, null);
                            } else if (StringUtils.isNotBlank(converted)) {
                                result.put(targetLabel, converted);
                            }
                        }
                    }
                }

                // ── 第四步：执行多 key_map 链 ────────────────────────────────
                for (List<SysDictData> multiChain : multiKeyChainMap.values()) {
                    multiChain.sort(Comparator.comparingLong(SysDictData::getDictCode));

                    // 从链中读取 GROUP_JOIN_SEP 声明，解析输出分隔符，默认逗号
                    String[] groupSeps = multiChain.stream()
                            .map(SysDictData::getValueMap)
                            .map(ValueMappingParser::extractGroupJoinSep)
                            .filter(Objects::nonNull)
                            .findFirst()
                            .orElse(null);

                    String outSep = (groupSeps != null) ? groupSeps[1] : ",";

                    for (SysDictData rule : multiChain) {
                        if (StringUtils.isBlank(rule.getDictLabel())) continue;

                        // GROUP_JOIN_SEP 记录仅作配置声明，不参与值转换，跳过
                        if (StringUtils.isNotBlank(rule.getValueMap())
                                && rule.getValueMap().trim().toUpperCase().startsWith("GROUP_JOIN_SEP")) {
                            continue;
                        }

                        Object rawObj = StringUtils.isNotBlank(rule.getKeyMap())
                                ? map.get(rule.getKeyMap()) : null;
                        String rawValue = rawObj == null ? null : String.valueOf(rawObj);
                        String converted = rawValue;

                        if (StringUtils.isNotBlank(rule.getValueMap())) {
                            // 统一走 convertWithDictMap，行为与 applyChain 保持一致：
                            //   · 普通规则 → 内部委托给 convert()，行为不变
                            //   · DICT_MAP / PIPE:...|DICT_MAP → 解析 value_connection 后执行映射
                            Map<String, String> mergedMap =
                                    ValueMappingParser.mergeValueConnection(rule.getValueConnection());
                            String stepped =
                                    ValueMappingParser.convertWithDictMap(rawValue, rule.getValueMap(), mergedMap);

                            if (ValueMappingParser.EMPTY_SENTINEL.equals(stepped)) {
                                if (!result.containsKey(rule.getDictLabel())) {
                                    result.put(rule.getDictLabel(), null);
                                }
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
                            String label = rule.getDictLabel();
                            Object existing = result.get(label);
                            if (existing != null && StringUtils.isNotBlank(String.valueOf(existing))) {
                                result.put(label, existing + outSep + converted);
                            } else {
                                result.put(label, converted);
                            }
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

    @Override
    public List<Map<String, Object>> selectVehicleTemplateIdByCondition(String materialNo, String brand, String weight, String saleName, String tire, String tvv) {
        List<VehicleTemplate> templates = templateMapper.selectVehicleTemplateIdByCondition(materialNo, brand, weight, saleName, tire, tvv);
        if (templates.isEmpty()) {
            throw new RuntimeException("无法匹配任何可用模板");
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (VehicleTemplate template : templates) {
            Map<String, Object> templateMap = new HashMap<>();
            templateMap.put("vehicleTemplateId", template.getTemplateId());
            templateMap.put("version", template.getVersion());
            templateMap.put("tvv", template.getTvv());
            templateMap.put("cocTemplateNo", template.getCocTemplateNo());
            templateMap.put("wvtaCocNo", template.getWvtaCocNo());
            result.add(templateMap);
        }
        return result;
    }

    @Override
    public Map<String, String> getTemplateParams() {
        Map<String, String> params = new LinkedHashMap<>();
        String filePath = "/assets/COC导入模版.xlsx";
        DataFormatter formatter = new DataFormatter();

        try (InputStream is = getClass().getResourceAsStream(filePath)) {
            if (is == null) {
                throw new RuntimeException("模板文件未找到: " + filePath);
            }

            try (Workbook workbook = WorkbookFactory.create(is)) {
                Sheet sheet = workbook.getSheetAt(0);
                Row headerRow = sheet.getRow(0);

                if (headerRow != null) {
                    for (Cell cell : headerRow) {
                        if (cell.getColumnIndex() < 13) continue;
                        String header = formatter.formatCellValue(cell);
                        if (!header.isEmpty()) {
                            params.put(header, null);
                        }
                    }
                }
            }

        } catch (IOException e) {
            throw new RuntimeException("读取 Excel 模板文件失败: " + filePath, e);
        }
        return params;
    }

    @Override
    public List<VehicleJsonKeyVo> listJsonKeysByVehicleTemplateIds(String vehicleModel) {
        List<SysDictData> dictDataList = Collections.emptyList();
        try {
            List<SysDictData> vehicleModelSysDictData = remoteDictService.getDictDataByType("vehicle_model").getData();
            Long dictId = vehicleModelSysDictData.stream()
                    .filter(dict -> vehicleModel.equals(dict.getDictLabel()) || vehicleModel.equals(dict.getDictValue()))
                    .map(SysDictData::getDictCode)
                    .findFirst()
                    .orElse(null);
            R<List<SysDictData>> dictResult = remoteDictService.getDictDataByType("vehicle_attribute");
            if (dictResult != null && dictResult.getData() != null) {
                dictDataList = new ArrayList<>(dictResult.getData().stream()
                        .filter(d -> d.getDictTypeAffiliation().equals(dictId))
                        .filter(d -> StringUtils.isNotBlank(d.getDictLabel()))
                        .filter(d -> hasCocTemplateKey(d.getOriginalSystem()))
                        .collect(Collectors.toMap(
                                SysDictData::getDictLabel,
                                d -> d,
                                (a, b) -> a.getDictCode() <= b.getDictCode() ? a : b,
                                LinkedHashMap::new
                        ))
                        .values());
            }
        } catch (Exception e) {
            log.warn("获取 vehicle_attribute 字典失败", e);
        }

        if (dictDataList.isEmpty()) {
            return Collections.emptyList();
        }

        return dictDataList.stream()
                .map(d -> new VehicleJsonKeyVo(
                        d.getDictLabel(),
                        d.getOtherLabel(),
                        d.getOtherLabelSystem(),
                        d.getCocOrder()
                ))
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * 对单条链执行链式转换，返回最终值。
     */
    private String applyChain(String rawValue, List<SysDictData> chain, String fieldName) {
        String current = rawValue;
        for (SysDictData rule : chain) {
            if (StringUtils.isBlank(rule.getValueMap())) continue;

            Map<String, String> mergedMap = ValueMappingParser.mergeValueConnection(rule.getValueConnection());
            current = ValueMappingParser.convertWithDictMap(current, rule.getValueMap(), mergedMap);

            if (current == null) {
                log.warn("[applyChain] 转换返回 null, fieldName={}, dictCode={}, valueMap={}",
                        fieldName, rule.getDictCode(), rule.getValueMap());
                return null;
            }
            if (ValueMappingParser.EMPTY_SENTINEL.equals(current)) {
                return ValueMappingParser.EMPTY_SENTINEL;
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
            R<List<SysDictData>> dictResult = remoteDictService.getDictDataByType("vehicle_attribute");
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

    private Object normalizeValue(Object val) {
        if (val == null || "".equals(val)) return null;
        return val;
    }

    /**
     * 判断 original_system 是否以 "COC模版_" 开头
     */
    private boolean hasCocTemplateKey(String originalSystem) {
        if (StringUtils.isBlank(originalSystem)) {
            return false;
        }
        return originalSystem.startsWith("COC模版_");
    }
}