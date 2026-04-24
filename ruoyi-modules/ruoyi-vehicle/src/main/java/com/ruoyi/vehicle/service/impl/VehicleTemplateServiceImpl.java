package com.ruoyi.vehicle.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.core.model.ValidationReport;
import com.ruoyi.common.core.utils.DateUtils;
import com.ruoyi.common.core.utils.uuid.UUID;
import com.ruoyi.common.security.utils.SecurityUtils;
import com.ruoyi.system.api.RemoteDictService;
import com.ruoyi.system.api.RemoteFileService;
import com.ruoyi.system.api.domain.SysDictData;
import com.ruoyi.vehicle.domain.VehicleTemplate;
import com.ruoyi.vehicle.domain.VehicleTemplateMaterial;
import com.ruoyi.vehicle.mapper.VehicleTemplateMapper;
import com.ruoyi.vehicle.mapper.VehicleTemplateMaterialMapper;
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

    @Override
    public List<VehicleTemplate> selectVehicleTemplateList(VehicleTemplate template) {
        return templateMapper.selectVehicleTemplateList(template);
    }

    @Override
    public List<VehicleTemplate> selectVehicleTemplateExpiringList() {
        return templateMapper.selectExpiringTemplates();
    }

    @Override
    public VehicleTemplate selectVehicleTemplateById(Long templateId) {
        VehicleTemplate template = templateMapper.selectVehicleTemplateById(templateId);
        if (template != null) {
            template.setMaterialList(materialMapper.selectByTemplateId(templateId));
        }
        return template;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int importVehicleTemplate(MultipartFile file) throws Exception {
        // TODO: 实现文件解析逻辑
        String json = parseFileToJson(file);
        VehicleTemplate t = new VehicleTemplate();
        t.setJson(json);
        t.setStatus("0");
        t.setValidateResult("0");
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
        template.setStatus("0");
        template.setValidateResult("0");
        template.setCreateBy(SecurityUtils.getUsername());
        template.setCreateTime(DateUtils.getNowDate());
        return templateMapper.insertVehicleTemplate(template);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateVehicleTemplate(VehicleTemplate template) {
        String templateVersion = templateMapper.selectVersionByCoc(template.getCocTemplateNo());
        if (templateVersion == null) {
            templateVersion = "1.0";
        } else {
            templateVersion = String.valueOf(new BigDecimal(templateVersion).add(new BigDecimal(1)));
        }
        template.setTemplateId(null);
        template.setVersion(templateVersion);
        template.setCreateBy(SecurityUtils.getUsername());
        template.setCreateTime(DateUtils.getNowDate());
        templateMapper.updateAllTemplateNotIsLast(template.getCocTemplateNo());
        return templateMapper.insertVehicleTemplate(template);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteVehicleTemplateByIds(Long[] templateIds) {
        materialMapper.deleteByTemplateIds(templateIds);
        return templateMapper.deleteVehicleTemplateByIds(templateIds);
    }

    @Override
    public int updateStatus(Long templateId, String status) {
        return templateMapper.updateStatus(templateId, status);
    }

    @Override
    public List<ValidationReport> batchValidate(Long... templateIds) {
        if (templateIds == null || templateIds.length == 0) {
            return Collections.emptyList();
        }

        List<ValidationReport> reports = new ArrayList<>();
        List<VehicleTemplate> updateList = new ArrayList<>();

        for (Long templateId : templateIds) {
            try {
                VehicleTemplate template = templateMapper.selectVehicleTemplateById(templateId);
                if (template == null) {
                    log.warn("模板不存在, templateId={}", templateId);
                    continue;
                }

                // 执行校验
                ValidationReport report = vehicleValidationService.validate(
                        template.getJson(),
                        template.getVehicleType(),
                        null
                );

                reports.add(report);

                // 组装回写数据
                VehicleTemplate update = new VehicleTemplate();
                update.setTemplateId(templateId);
                update.setValidateResult(report.isAllValid() ? "1" : "2");
                try {
                    update.setValidateMsg(objectMapper.writeValueAsString(report.getFailedFields()));
                } catch (Exception e) {
                    update.setValidateMsg("序列化失败");
                }
                updateList.add(update);

            } catch (Exception e) {
                log.error("校验异常, templateId={}", templateId, e);
                reports.add(ValidationReport.builder()
                        .allValid(false)
                        .error("校验异常：" + e.getMessage()).build());
            }
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
            List<VehicleTemplate> vehicleTemplates = excelUtil.importExcel(file.getInputStream(), "vehicle_template", VehicleTemplate.class);
            List<SysDictData> vehicleModels = remoteDictService.getDictDataByType("vehicle_model").getData();
            // 建立 label -> dictCode 映射，避免双重循环
            Map<String, String> labelToCodeMap = vehicleModels.stream()
                    .collect(Collectors.toMap(
                            SysDictData::getDictLabel,
                            SysDictData::getDictValue,
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
                template.setStatus("0");
                template.setValidateResult("0");
                template.setCreateBy(SecurityUtils.getUsername());
                template.setCreateTime(DateUtils.getNowDate());
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
}