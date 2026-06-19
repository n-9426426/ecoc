package com.ruoyi.vehicle.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.core.utils.StringUtils;
import com.ruoyi.vehicle.domain.Material;
import com.ruoyi.vehicle.domain.VehicleInfo;
import com.ruoyi.vehicle.domain.VehicleTemplate;
import com.ruoyi.vehicle.mapper.MaterialMapper;
import com.ruoyi.vehicle.mapper.VehicleInfoMapper;
import com.ruoyi.vehicle.mapper.VehicleTemplateMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 三表互联回溯补关联服务
 *
 * 职责：当 COC 模版、物料号、车辆信息任意一方先到达时，
 * 后续另一方导入后调用此 Service 完成补关联，
 * 保证不管导入顺序如何，最终三表都能互相关联上。
 *
 * 调用关系：
 *   导入 COC 模版 → retroactiveLinkMaterialsAndVehiclesByTemplate(template)
 *   导入 物料号   → retroactiveLinkVehiclesByMaterial(material, template)
 *   导入 车辆信息 → 无需调用（车辆信息导入时本身已查物料，查不到只是暂时留空，
 *                    等物料号入口的回溯来填即可）
 */
@Service
public class RetroactiveLinkService {

    private static final Logger log = LoggerFactory.getLogger(RetroactiveLinkService.class);

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private MaterialMapper materialMapper;

    @Autowired
    private VehicleInfoMapper vehicleInfoMapper;

    @Autowired
    private VehicleTemplateMapper vehicleTemplateMapper;

    // -----------------------------------------------------------------------
    // 入口1：导入 COC 模版后调用
    // -----------------------------------------------------------------------

    /**
     * 导入 COC 模版后的回溯补关联入口。
     *
     * 逻辑：
     * 1. 用新模版的 tvv 查找 material 表中还没有关联模版的物料号（vehicle_template_id IS NULL）
     * 2. 对每条匹配的物料：更新其 vehicle_template_id / uuid / version
     * 3. 再用每条物料的 material_no 查找 vehicle_info 中还没有关联模版的车辆，补填模版相关字段
     *
     * 整个过程对每条物料/车辆使用独立子事务，一条失败不影响其他条。
     *
     * @param template 刚保存成功的 COC 模版（templateId 已由 DB 回填）
     */
    public void retroactiveLinkMaterialsAndVehiclesByTemplate(VehicleTemplate template) {
        if (template == null || template.getTemplateId() == null) {
            return;
        }

        // tvv 在 material 表存储时已去掉逗号（例如 "A1B1C1"），template.tvv 含逗号（"A1,B1,C1"）
        String tvvForQuery = template.getTvv() == null ? null : template.getTvv().replace(",", "");
        if (StringUtils.isBlank(tvvForQuery)) {
            return;
        }

        List<Material> unlinkedMaterials = materialMapper.selectUnlinkedMaterialsByTvv(tvvForQuery);
        if (unlinkedMaterials == null || unlinkedMaterials.isEmpty()) {
            log.info("[回溯] 导入 COC 模版 templateId={} 后，未发现可补关联的物料号", template.getTemplateId());
            return;
        }

        log.info("[回溯] 导入 COC 模版 templateId={} 后，发现 {} 条待补关联物料号",
                template.getTemplateId(), unlinkedMaterials.size());

        for (Material material : unlinkedMaterials) {
            try {
                doLinkMaterialToTemplate(material, template);
            } catch (Exception e) {
                log.warn("[回溯] 物料号 {} 补关联模版 templateId={} 失败: {}",
                        material.getMaterialNo(), template.getTemplateId(), e.getMessage());
            }
        }
    }

    /**
     * 将单条物料与模版关联，并继续回溯该物料下的车辆信息。
     * 使用 REQUIRES_NEW 保证每条物料独立事务，失败不影响同批次其他物料。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void doLinkMaterialToTemplate(Material material, VehicleTemplate template) {
        // 更新物料的模版关联字段
        Material update = new Material();
        update.setId(material.getId());
        update.setVehicleTemplateId(template.getTemplateId());
        update.setVehicleTemplateUuid(template.getUuid());
        update.setVersion(template.getVersion());
        materialMapper.updateMaterial(update);

        log.info("[回溯] 物料号 {} 成功关联 templateId={}", material.getMaterialNo(), template.getTemplateId());

        // 继续回溯该物料下未关联模版的车辆
        retroactiveLinkVehiclesByMaterial(material, template);
    }

    // -----------------------------------------------------------------------
    // 入口2：导入物料号后调用（物料已保存、templateId 已写入 material）
    // -----------------------------------------------------------------------

    /**
     * 导入物料号后的回溯补关联入口。
     *
     * 逻辑：
     * 1. 如果该物料匹配到了模版（vehicleTemplateId 不为空），则查找
     *    vehicle_info 中 material_no = 该物料号 且 vehicle_template_id 为空的车辆
     * 2. 对每辆车补填模版相关字段（templateId、tvv、wvtaNo、cocTemplateNo、json 等）
     *
     * 调用方（MaterialServiceImpl.insertMaterial）在物料 insert 成功后调用本方法。
     *
     * @param material 刚保存成功的物料（vehicleTemplateId 可能为空，表示未匹配到模版）
     * @param template 匹配到的模版（如果物料未匹配到模版，传 null）
     */
    public void retroactiveLinkVehiclesByMaterial(Material material, VehicleTemplate template) {
        if (material == null || StringUtils.isBlank(material.getMaterialNo())) {
            return;
        }
        if (template == null) {
            // 物料未匹配到模版，车辆暂时无法补关联，等待模版后续导入时触发入口1
            log.info("[回溯] 物料号 {} 未匹配到模版，车辆回溯暂不处理", material.getMaterialNo());
            return;
        }

        List<VehicleInfo> unlinkedVehicles =
                vehicleInfoMapper.selectByMaterialNoWithoutTemplate(material.getMaterialNo());

        if (unlinkedVehicles == null || unlinkedVehicles.isEmpty()) {
            log.info("[回溯] 物料号 {} 下未发现待补关联的车辆信息", material.getMaterialNo());
            return;
        }

        log.info("[回溯] 物料号 {} 下发现 {} 辆待补关联车辆", material.getMaterialNo(), unlinkedVehicles.size());

        for (VehicleInfo vehicle : unlinkedVehicles) {
            try {
                doLinkVehicleToTemplate(vehicle, material, template);
            } catch (Exception e) {
                log.warn("[回溯] 车辆 vehicleId={} vin={} 补关联模版 templateId={} 失败: {}",
                        vehicle.getVehicleId(), vehicle.getVin(), template.getTemplateId(), e.getMessage());
            }
        }
    }

    /**
     * 将单辆车与模版关联，补填所有模版相关字段。
     * 使用 REQUIRES_NEW 保证每辆车独立事务，失败不影响同批次其他车辆。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void doLinkVehicleToTemplate(VehicleInfo vehicle, Material material, VehicleTemplate template) {
        // 构建替换后的 JSON（复用 VehicleInfoServiceImpl.replaceFieldValues 的结果）
        String json = buildJsonForVehicle(template, material);

        vehicleInfoMapper.retroactiveLinkTemplate(
                vehicle.getVehicleId(),
                String.valueOf(template.getTemplateId()),
                template.getTvv() == null ? null : template.getTvv().replace(",", ""),
                template.getWvtaCocNo(),
                json,
                material.getBrand(),
                material.getWeight(),
                material.getSaleName(),
                material.getTire(),
                material.getTireResistanceGrade(),
                material.getVehicleModel(),
                material.getName()
        );

        log.info("[回溯] 车辆 vehicleId={} vin={} 成功关联 templateId={}",
                vehicle.getVehicleId(), vehicle.getVin(), template.getTemplateId());
    }

    // -----------------------------------------------------------------------
    // 内部工具方法
    // -----------------------------------------------------------------------

    /**
     * 基于模版 JSON 和物料字段，执行与 VehicleInfoServiceImpl.replaceFieldValues 等价的字段替换，
     * 返回替换后的 JSON 字符串。
     *
     * 替换规则（与 insertVehicleInfo 中保持完全一致）：
     *   Make          → material.brand
     *   ActualMass    → material.weight
     *   CommercialName→ material.saleName
     *   TyreSize      → material.tire（多段取匹配段）
     *
     * 注意：RollingResistanceClass 的字典转换依赖远程字典服务，此处为保持回溯逻辑轻量，
     * 若 material.tireResistanceGrade 不为空则直接写入原始值，业务上影响较小。
     * 如需完整转换，可在调用方注入 RemoteDictService 后传入转换结果。
     */
    private String buildJsonForVehicle(VehicleTemplate template, Material material) {
        if (StringUtils.isBlank(template.getJson())) {
            return template.getJson();
        }
        try {
            JsonNode rootNode = objectMapper.readTree(template.getJson());
            replaceBasicFields(rootNode, material);
            return objectMapper.writeValueAsString(rootNode);
        } catch (Exception e) {
            log.warn("[回溯] JSON 字段替换失败，使用原始模版 JSON，templateId={}, error={}",
                    template.getTemplateId(), e.getMessage());
            return template.getJson();
        }
    }

    /**
     * 在 JsonNode 上直接替换 Make / ActualMass / CommercialName / TyreSize 四个字段。
     * 与 VehicleInfoServiceImpl.replaceFieldValues 逻辑对齐，但不依赖 RemoteDictService。
     */
    private void replaceBasicFields(JsonNode node, Material material) {
        if (node == null || !node.isObject()) return;

        com.fasterxml.jackson.databind.node.ObjectNode objectNode =
                (com.fasterxml.jackson.databind.node.ObjectNode) node;

        node.fields().forEachRemaining(entry -> {
            String fieldName = entry.getKey();
            JsonNode fieldValue = entry.getValue();

            switch (fieldName) {
                case "Make":
                    if (StringUtils.isNotBlank(material.getBrand())) {
                        objectNode.put(fieldName, material.getBrand());
                    }
                    break;
                case "ActualMass":
                    if (StringUtils.isNotBlank(material.getWeight())) {
                        objectNode.put(fieldName, material.getWeight());
                    }
                    break;
                case "CommercialName":
                    if (StringUtils.isNotBlank(material.getSaleName())) {
                        objectNode.put(fieldName, material.getSaleName());
                    }
                    break;
                case "TyreSize":
                    if (StringUtils.isNotBlank(material.getTire())) {
                        String tyreSizeRaw = fieldValue.asText();
                        if (tyreSizeRaw != null && tyreSizeRaw.contains(";")) {
                            // 多段轮胎，取与 material.tire 匹配的那段
                            String[] tyreSizeParts = tyreSizeRaw.split(";");
                            int matchIndex = -1;
                            for (int i = 0; i < tyreSizeParts.length; i++) {
                                if (tyreSizeParts[i].trim().equals(material.getTire().trim())) {
                                    matchIndex = i;
                                    break;
                                }
                            }
                            if (matchIndex >= 0) {
                                // 同步裁剪轮胎相关字段
                                java.util.List<String> tyreFields = java.util.Arrays.asList(
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
                                        objectNode.put(tyreField, parts[finalMatchIndex].trim());
                                    }
                                }
                            }
                        } else {
                            objectNode.put(fieldName, material.getTire());
                        }
                    }
                    break;
                default:
                    if (fieldValue.isObject()) {
                        replaceBasicFields(fieldValue, material);
                    }
                    break;
            }
        });
    }
}
