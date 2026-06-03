package com.ruoyi.vehicle.service.impl;

import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.common.core.utils.StringUtils;
import com.ruoyi.common.core.utils.uuid.UUID;
import com.ruoyi.common.security.utils.SecurityUtils;
import com.ruoyi.system.api.model.LoginUser;
import com.ruoyi.vehicle.domain.Material;
import com.ruoyi.vehicle.domain.MaterialHistory;
import com.ruoyi.vehicle.domain.VehicleTemplate;
import com.ruoyi.vehicle.mapper.MaterialHistoryMapper;
import com.ruoyi.vehicle.mapper.MaterialMapper;
import com.ruoyi.vehicle.mapper.VehicleTemplateMapper;
import com.ruoyi.vehicle.service.IMaterialService;
import com.ruoyi.vehicle.utils.ExcelUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 整车物料 Service 业务层实现
 *
 * @author ruoyi
 */
@Slf4j
@Service
public class MaterialServiceImpl implements IMaterialService {

    @Autowired
    private ExcelUtil excelUtil;

    @Autowired
    private MaterialMapper materialMapper;

    @Autowired
    private MaterialHistoryMapper materialHistoryMapper;

    @Autowired
    private VehicleTemplateMapper vehicleTemplateMapper;

    /** SSE sink 注册表，taskId -> sink */
    private final Map<String, Sinks.Many<ServerSentEvent<String>>> sinks = new ConcurrentHashMap<>();

    /**
     * 查询整车物料
     */
    @Override
    public Material selectMaterialById(Long id) {
        Material material = materialMapper.selectMaterialById(id);
        List<VehicleTemplate> vehicleTemplates = vehicleTemplateMapper.selectVehicleTemplateIdByCondition(
                null, material.getBrand(), material.getWeight(), material.getSaleName(), material.getTire(), material.getTvv()
        );
        material.setVehicleTemplates(vehicleTemplates);
        material.setMaterialHistories(materialHistoryMapper.selectByMaterialId(id, material.getVersion()));
        return material;
    }

    /**
     * 查询整车物料列表
     */
    @Override
    public List<Material> selectMaterialList(Material material) {
        return materialMapper.selectMaterialList(material);
    }

    /**
     * 新增整车物料
     */
    @Override
    public int insertMaterial(Material material) {
        Material existMaterial = materialMapper.selectByMaterialNo(material.getMaterialNo());
        if (existMaterial != null) {
            throw new RuntimeException("该物料号已经定义过版本，无法继续定义");
        }
        LoginUser loginUser = SecurityUtils.getLoginUser();
        material.setCreateBy(loginUser.getUsername());
        material.setCreateTime(new Date());
        material.setRemark(StringUtils.isBlank(material.getSwitchRemark()) ? null : material.getSwitchRemark());
        List<VehicleTemplate> templates = vehicleTemplateMapper.selectVehicleTemplateIdByCondition(null,
                material.getBrand(), material.getWeight(), material.getSaleName(), material.getTire(), material.getTvv()
        );
        if (templates.isEmpty()) {
            return materialMapper.insertMaterial(material);
        }
        Map<String, VehicleTemplate> templateMap = templates.stream()
                .collect(Collectors.toMap(
                        VehicleTemplate::getUuid,
                        t -> t,
                        (existing, replacement) -> existing
                ));
//        if (templateMap.size() > 1) {
//            throw new RuntimeException("请输入正确的数据以匹配模版");
//        }
        templateMap = templates.stream()
                .sorted(Comparator.comparing(
                        t -> new BigDecimal(t.getVersion()),
                        Comparator.reverseOrder()
                ))
                .collect(Collectors.toMap(
                        VehicleTemplate::getVersion,
                        t -> t,
                        (existing, replacement) -> existing,
                        LinkedHashMap::new
                ));
        Map.Entry<String, VehicleTemplate> firstEntry = templateMap.entrySet().iterator().next();
        String version = firstEntry.getKey();
        VehicleTemplate template = firstEntry.getValue();
        material.setVersion(template.getVersion());
        material.setVehicleTemplateId(template.getTemplateId());
        return materialMapper.insertMaterial(material);
    }

    /**
     * 修改整车物料
     */
    @Override
    public int updateMaterial(Material material) {
        Material query = new Material();
        query.setMaterialNo(material.getMaterialNo());
        List<VehicleTemplate> templates = vehicleTemplateMapper.selectVehicleTemplateIdByCondition(null,
                material.getBrand(), material.getWeight(), material.getSaleName(), material.getTire(), material.getTvv()
        );
        if (templates.isEmpty()) {
            throw new RuntimeException("没有匹配的模版");
        }
        Map<String, VehicleTemplate> templateMap = templates.stream()
                .collect(Collectors.toMap(
                        VehicleTemplate::getUuid,
                        t -> t,
                        (existing, replacement) -> existing
                ));
//        if (templateMap.size() > 1) {
//            throw new RuntimeException("请输入正确的数据以匹配模版");
//        }
        Map<String, VehicleTemplate> map = templates.stream()
                .collect(Collectors.toMap(
                        VehicleTemplate::getVersion,
                        t -> t,
                        (existing, replacement) -> existing  // version重复时保留第一个
                ));
        VehicleTemplate template = map.get(material.getNewVersion());
        if (template == null) {
            throw new RuntimeException("该模版不存在");
        }
        material.setVersion(template.getVersion());
        material.setVehicleTemplateId(template.getTemplateId());
        LoginUser loginUser = SecurityUtils.getLoginUser();
        material.setUpdateBy(loginUser.getUsername());
        material.setUpdateTime(new Date());
        int row = materialMapper.updateMaterial(material);
        if (!material.getVersion().equals(material.getNewVersion())) {
            MaterialHistory history = new MaterialHistory();
            history.setMaterialId(material.getId());
            history.setOldVersion(material.getVersion());
            history.setNewVersion(material.getNewVersion());
            history.setChangeTime(new Date());
            history.setOperator(loginUser.getUsername());
            history.setRemark(material.getSwitchRemark());
            materialHistoryMapper.insert(history);
        }
        return row;
    }

    /**
     * 批量删除整车物料
     */
    @Override
    public int deleteMaterialByIds(Long[] ids) {
        return materialMapper.deleteMaterialByIds(ids);
    }

    // ===================== SSE 异步导入（与 VehicleTemplate 保持一致）=====================

    /**
     * 第一步：提交导入任务，在请求线程预读文件内容和用户信息，
     * 提交异步任务后立即返回 taskId，前端通过 getImportFlux 订阅进度。
     */
    @Override
    public String submitImportTask(MultipartFile file, boolean updateSupport) {
        String taskId = UUID.randomUUID().toString();
        Sinks.Many<ServerSentEvent<String>> sink = Sinks.many().unicast().onBackpressureBuffer();
        sinks.put(taskId, sink);

        byte[] fileBytes;
        try {
            fileBytes = file.getBytes();
        } catch (Exception e) {
            sinks.remove(taskId);
            throw new ServiceException("文件读取失败: " + e.getMessage());
        }
        String currentUser = SecurityUtils.getUsername();
        String lang = excelUtil.resolveCurrentLang();

        CompletableFuture.runAsync(() -> doImportMaterial(taskId, fileBytes, currentUser, lang, updateSupport));
        return taskId;
    }

    /**
     * 第二步：订阅导入进度 SSE 流。
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
     * 实际导入逻辑，在异步线程中执行，逐行推送 SSE 事件。
     * 事件类型：
     *   progress → {"row":N,"total":T,"status":"success"/"update"/"skip"/"fail","reason":"..."}
     *   complete → {"successCount":N,"updateCount":U,"skipCount":S,"failCount":F,"errorDetails":["..."]}
     *   error    → {"message":"..."}
     */
    private void doImportMaterial(String taskId, byte[] fileBytes,
                                  String createBy, String lang, boolean updateSupport) {
        Sinks.Many<ServerSentEvent<String>> sink = sinks.get(taskId);
        if (sink == null) return;

        int successCount = 0;
        int updateCount  = 0;
        int skipCount    = 0;
        int failCount    = 0;
        List<String> errorDetails = new ArrayList<>();

        try {
            List<Material> materialList = excelUtil.importExcel(
                    new ByteArrayInputStream(fileBytes),
                    "material",
                    Material.class,
                    lang,
                    2   // 跳过说明行和示例行，数据从第4行开始
            );

            if (materialList.isEmpty()) {
                pushEvent(sink, "error", "{\"message\":\"未解析到有效数据，请检查文件内容是否从第4行开始填写\"}");
                sink.tryEmitComplete();
                sinks.remove(taskId);
                return;
            }

            int total = materialList.size();

            for (int i = 0; i < total; i++) {
                Material material = materialList.get(i);
                int rowNum = i + 4; // 列头=1，说明=2，示例=3，数据从4开始

                List<String> missingFields = new ArrayList<>();

                if (StringUtils.isBlank(material.getMaterialNo()))  missingFields.add("Material No");
                if (StringUtils.isBlank(material.getTvv()))         missingFields.add("tvv");
                if (StringUtils.isBlank(material.getFactoryCode())) missingFields.add("Factory Code");

                if (!missingFields.isEmpty()) {
                    skipCount++;
                    String reason = String.join("、", missingFields) + " 不能为空";
                    errorDetails.add("第" + rowNum + "行：" + reason);
                    pushEvent(sink, "progress", String.format(
                            "{\"row\":%d,\"total\":%d,\"status\":\"skip\",\"reason\":\"%s\"}",
                            rowNum, total, escapeJson(reason)));
                    continue;
                }

                try {
                    material.setCreateBy(createBy);
                    Material existing = materialMapper.selectByMaterialNo(material.getMaterialNo());

                    if (existing == null) {
                        // 新增
                        insertMaterial(material);
                        successCount++;
                        pushEvent(sink, "progress", String.format(
                                "{\"row\":%d,\"total\":%d,\"status\":\"success\"}", rowNum, total));

                    } else if (updateSupport) {
                        // 覆盖更新
                        material.setId(existing.getId());
                        material.setUpdateBy(createBy);
                        material.setUpdateTime(new Date());
                        materialMapper.updateMaterial(material);
                        updateCount++;
                        pushEvent(sink, "progress", String.format(
                                "{\"row\":%d,\"total\":%d,\"status\":\"update\"}", rowNum, total));

                    } else {
                        // 不允许更新，跳过
                        skipCount++;
                        String reason = "物料号[" + material.getMaterialNo() + "]已存在";
                        errorDetails.add("第" + rowNum + "行：" + reason);
                        pushEvent(sink, "progress", String.format(
                                "{\"row\":%d,\"total\":%d,\"status\":\"skip\",\"reason\":\"%s\"}",
                                rowNum, total, escapeJson(reason)));
                    }

                } catch (Exception e) {
                    failCount++;
                    String reason = e.getMessage();
                    log.error("导入第{}行失败，material_no={}，原因：{}", rowNum, material.getMaterialNo(), reason);
                    errorDetails.add("第" + rowNum + "行：" + reason);
                    pushEvent(sink, "progress", String.format(
                            "{\"row\":%d,\"total\":%d,\"status\":\"fail\",\"reason\":\"%s\"}",
                            rowNum, total, escapeJson(reason)));
                }
            }

        } catch (Exception e) {
            log.error("物料导入文件解析失败, taskId={}", taskId, e);
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
                "{\"successCount\":%d,\"updateCount\":%d,\"skipCount\":%d,\"failCount\":%d,\"errorDetails\":%s}",
                successCount, updateCount, skipCount, failCount, detailJson));

        sink.tryEmitComplete();
        sinks.remove(taskId);
    }

    // ===================== SSE 工具方法 =====================

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
}