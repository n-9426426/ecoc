package com.ruoyi.vehicle.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.common.core.utils.StringUtils;
import com.ruoyi.common.core.utils.uuid.UUID;
import com.ruoyi.common.security.utils.SecurityUtils;
import com.ruoyi.system.api.RemoteDictService;
import com.ruoyi.system.api.domain.SysDictData;
import com.ruoyi.system.api.model.LoginUser;
import com.ruoyi.vehicle.domain.Material;
import com.ruoyi.vehicle.domain.MaterialHistory;
import com.ruoyi.vehicle.domain.VehicleInfo;
import com.ruoyi.vehicle.domain.VehicleTemplate;
import com.ruoyi.vehicle.mapper.MaterialHistoryMapper;
import com.ruoyi.vehicle.mapper.MaterialMapper;
import com.ruoyi.vehicle.mapper.VehicleInfoMapper;
import com.ruoyi.vehicle.mapper.VehicleTemplateMapper;
import com.ruoyi.vehicle.service.IFirstVehicleCheckService;
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

    @Autowired
    private IFirstVehicleCheckService firstVehicleCheckService;

    @Autowired
    private VehicleInfoMapper vehicleInfoMapper;

    @Autowired
    private RemoteDictService remoteDictService;

    /** SSE sink 注册表，taskId -> sink */
    private final Map<String, Sinks.Many<ServerSentEvent<String>>> sinks = new ConcurrentHashMap<>();

    /**
     * 查询整车物料
     */
    @Override
    public Material selectMaterialById(Long id) {
        Material material = materialMapper.selectMaterialById(id);
        if (material == null) {
            throw new ServiceException("该物料号不存在");
        }
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
        LoginUser loginUser = SecurityUtils.getLoginUser();
        return insertMaterial(material, loginUser.getUsername(), false);
    }

    private int insertMaterial(Material material, String createBy, boolean needDictConvert) {
        Material existMaterial = materialMapper.selectByMaterialNo(material.getMaterialNo());
        if (existMaterial != null) {
            throw new RuntimeException("该物料号已经定义过版本，无法继续定义");
        }
        if (needDictConvert) {
            List<SysDictData> sysDictData = remoteDictService.getDictDataByType("vehicle_model").getData();
            for (SysDictData dictData : sysDictData) {
                if (dictData.getDictLabel().equals(material.getVehicleModel())) {
                    material.setVehicleModel(dictData.getDictValue());
                    break;
                }
            }
            if (material.getVehicleModel() == null) {
                throw new RuntimeException("车型代码不存在");
            }

            List<SysDictData> factoryDictData = remoteDictService.getDictDataByType("factory").getData();
            for (SysDictData dictData : factoryDictData) {
                if (dictData.getDictLabel().equals(material.getFactoryCode())) {
                    material.setFactoryCode(dictData.getDictValue());
                    break;
                }
            }
            if (material.getFactoryCode() == null) {
                throw new RuntimeException("工厂代码不存在");
            }

            List<SysDictData> countryDictData = remoteDictService.getDictDataByType("country").getData();
            for (SysDictData dictData : countryDictData) {
                if (dictData.getDictLabel().equals(material.getFactoryCode())) {
                    material.setCountry(dictData.getDictValue());
                    break;
                }
            }
            if (material.getCountry() == null) {
                throw new RuntimeException("国家代码不存在");
            }
        }
        material.setCreateBy(createBy);
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
        try {
            JsonNode templateJson = new ObjectMapper().readTree(template.getJson());

            // Make → brand
            String makeStr = getJsonText(templateJson, "Make");
            List<String> makeList = splitBySemicolon(makeStr);
            if (StringUtils.isBlank(material.getBrand()) && makeList.size() > 1) {
                throw new RuntimeException("Make存在多个值，请指定品牌");
            }

            // ActualMass → weight
            String massStr = getJsonText(templateJson, "ActualMass");
            List<String> massList = splitBySemicolon(massStr);
            if (StringUtils.isBlank(material.getWeight()) && massList.size() > 1) {
                throw new RuntimeException("ActualMass存在多个值，请指定重量");
            }

            // CommercialName → saleName
            String commercialStr = getJsonText(templateJson, "CommercialName");
            List<String> commercialList = splitBySemicolon(commercialStr);
            if (StringUtils.isBlank(material.getSaleName()) && commercialList.size() > 1) {
                throw new RuntimeException("CommercialName存在多个值，请指定销售名称");
            }

            // TyreSize → tire
            String tyreSizeStr = getJsonText(templateJson, "TyreSize");
            List<String> tyreSizeList = splitBySemicolon(tyreSizeStr);
            if (StringUtils.isBlank(material.getTire()) && tyreSizeList.size() > 1) {
                throw new RuntimeException("TyreSize存在多个值，请指定轮胎");
            }

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.warn("insertMaterial: template json 解析失败, templateId={}", template.getTemplateId(), e);
        }
        material.setVersion(template.getVersion());
        material.setVehicleTemplateId(template.getTemplateId());
        applyMaterialAffirm(material);
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
        applyMaterialAffirm(material);
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
                    Map<String, String> vehicleModelLabelToValueMap = remoteDictService
                            .getDictDataByType("vehicle_model")
                            .getData().stream()
                            .collect(Collectors.toMap(
                                    SysDictData::getDictLabel,
                                    SysDictData::getDictValue,
                                    (k1, k2) -> k1));
                    String vehicleModelLabel = material.getVehicleModel();
                    String vehicleModelValue = vehicleModelLabelToValueMap.get(vehicleModelLabel);
                    if (vehicleModelValue == null) {
                        throw new IllegalArgumentException("车型[" + vehicleModelLabel + "]在字典中未找到对应值");
                    }
                    material.setVehicleModel(vehicleModelValue);

                    Map<String, String> factoryLabelToValueMap = remoteDictService
                            .getDictDataByType("factory")
                            .getData().stream()
                            .collect(Collectors.toMap(SysDictData::getDictLabel, SysDictData::getDictValue, (k1, k2) -> k1));
                    String factoryLabel = material.getFactoryCode();
                    String factoryValue = factoryLabelToValueMap.get(factoryLabel);
                    if (factoryValue == null) {
                        throw new IllegalArgumentException("工厂代码[" + factoryLabel + "]在字典中未找到对应值");
                    }
                    material.setFactoryCode(factoryValue);

                    Map<String, String> countryLabelToValueMap = remoteDictService
                            .getDictDataByType("country")
                            .getData().stream()
                            .collect(Collectors.toMap(
                                    SysDictData::getDictLabel,
                                    SysDictData::getDictValue,
                                    (k1, k2) -> k1));
                    String countryLabel = material.getCountry();
                    String countryValue = countryLabelToValueMap.get(countryLabel);
                    if (countryValue == null) {
                        throw new IllegalArgumentException("国家[" + countryLabel + "]在字典中未找到对应值");
                    }
                    material.setCountry(countryValue);

                    Material existing = materialMapper.selectByMaterialNo(material.getMaterialNo());
                    if (existing == null) {
                        // 新增
                        insertMaterial(material, createBy, true);
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

    @Override
    public Material updateMaterialStatus(Long id) {
        Material exist = selectMaterialById(id);
        if (exist == null) {
            throw new RuntimeException("该记录不存在");
        }
        int status = exist.getStatus() == 0 ? 1 : 0;
        Material update = new Material();
        update.setId(id);
        update.setStatus(status);
        update.setUpdateBy(SecurityUtils.getUsername());
        materialMapper.updateMaterialStatus(update);
        return update;
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

    private String getJsonText(JsonNode node, String field) {
        JsonNode target = node.get(field);
        return (target == null || target.isNull()) ? null : target.asText();
    }

    private List<String> splitBySemicolon(String value) {
        if (StringUtils.isBlank(value)) {
            return Collections.emptyList();
        }
        return Arrays.stream(value.split(";"))
                .map(String::trim)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toList());
    }

    private void applyMaterialAffirm(Material material) {
        String materialNo = material.getMaterialNo();
        String templateId = material.getVehicleTemplateId() != null
                ? String.valueOf(material.getVehicleTemplateId()) : null;

        boolean materialSwitchOn = firstVehicleCheckService.isSwitchOn("new_material");
        boolean templateSwitchOn = firstVehicleCheckService.isSwitchOn("new_template");

        // 两个维度各自的判断结果，true 表示"需要待确认（置0）"
        boolean materialNeedsConfirm = false;
        boolean templateNeedsConfirm = false;

        // ── 物料号维度 ────────────────────────────────────────────────────────
        if (StringUtils.isNotBlank(materialNo) && materialSwitchOn) {
            Long earliestMaterialId = vehicleInfoMapper.findEarliestIdByMaterialNo(materialNo);
            if (earliestMaterialId == null) {
                // 物料号首次出现
                materialNeedsConfirm = true;
            } else {
                VehicleInfo earliest = vehicleInfoMapper.selectVehicleInfoById(earliestMaterialId);
                if (earliest != null && Integer.valueOf(0).equals(earliest.getGenerateAffirm())) {
                    // 最早那辆车的生成确认状态仍是待确认
                    materialNeedsConfirm = true;
                }
            }
        }

        // ── 模板维度 ──────────────────────────────────────────────────────────
        if (StringUtils.isNotBlank(templateId) && templateSwitchOn) {
            Long earliestTemplateId = vehicleInfoMapper.findEarliestIdByTemplateId(templateId);
            if (earliestTemplateId == null) {
                // 模板首次出现
                templateNeedsConfirm = true;
            } else {
                VehicleInfo earliest = vehicleInfoMapper.selectVehicleInfoById(earliestTemplateId);
                if (earliest != null && Integer.valueOf(0).equals(earliest.getUploadAffirm())) {
                    // 最早那辆车的上传确认状态仍是待确认
                    templateNeedsConfirm = true;
                }
            }
        }

        // ── 任意一个维度需要待确认，对应字段置 0，否则置 1 ──────────────────
        material.setGenerateAffirm((materialNeedsConfirm || templateNeedsConfirm) ? 0 : 1);
        material.setUploadAffirm((materialNeedsConfirm || templateNeedsConfirm) ? 0 : 1);
    }
}