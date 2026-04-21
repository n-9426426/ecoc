package com.ruoyi.vehicle.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.core.executor.FinalRuleExecutor;
import com.ruoyi.common.core.model.FieldValidationResult;
import com.ruoyi.common.core.model.RuleItem;
import com.ruoyi.common.core.model.ValidationReport;
import com.ruoyi.common.core.parser.FinalRuleParser;
import com.ruoyi.system.api.RemoteDictService;
import com.ruoyi.system.api.domain.SysDictData;
import com.ruoyi.vehicle.domain.VehicleInfo;
import com.ruoyi.vehicle.mapper.VehicleInfoMapper;
import com.ruoyi.vehicle.service.IVehicleValidationService;
import com.ruoyi.vehicle.utils.VehicleFieldParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 车辆信息校验 ServiceImpl
 * 基于 FinalRuleParser + FinalRuleExecutor 实现完整规则校验
 */
@Slf4j
@Service("vehicleValidationService")
public class VehicleValidationServiceImpl implements IVehicleValidationService {

    @Autowired
    private VehicleInfoMapper vehicleInfoMapper;

    @Autowired
    private RemoteDictService remoteDictService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ==========================================
    // 接口实现
    // ==========================================

    /**
     * 根据 vehicleId 从数据库读取 JSON 并校验
     */
    @Override
    public ValidationReport validateByVehicleId(Long vehicleId) {
        VehicleInfo vehicleInfo = vehicleInfoMapper.selectVehicleInfoById(vehicleId);
        if (vehicleInfo == null) {
            log.warn("未找到车辆信息, vehicleId={}", vehicleId);
            return ValidationReport.fail("未找到车辆信息, vehicleId=" + vehicleId);
        }

        String vehicleCategory = extractVehicleCategory(vehicleInfo);
        String stageOfCompletion = extractStageOfCompletion(vehicleInfo);
        return validate(vehicleInfo.getJson(), vehicleCategory, stageOfCompletion);
    }

    /**
     * 校验车辆信息 JSON 中的所有字段
     */
    @Override
    public ValidationReport validate(
            String jsonStr,
            String vehicleCategory,
            String stageOfCompletion) {

        ValidationReport report = ValidationReport.builder()
                .vehicleCategory(vehicleCategory)
                .stageOfCompletion(stageOfCompletion)
                .allValid(true)
                .fieldResults(new ArrayList<>())
                .build();

        if (jsonStr == null || jsonStr.trim().isEmpty()) {
            log.warn("JSON 为空，跳过校验");
            return report;
        }

        // 1. 解析 JSON
        Map<String, Object> jsonMap = parseJson(jsonStr);
        if (jsonMap == null) {
            return ValidationReport.fail("JSON 格式错误");
        }

        // 2. 解析列表字段（axleList、bodyworkList 等）
        Map<String, List<Map<String, Object>>> listFields =
                VehicleFieldParser.parseListFieldsFromMap(jsonMap, remoteDictService);

        // 3. 构建上下文（所有字段值 + 车型 + 阶段 + 列表字段）
        Map<String, Object> context = buildContext(jsonMap, listFields, vehicleCategory, stageOfCompletion);

        // 4. 遍历每个字段逐一校验
        for (Map.Entry<String, Object> entry : jsonMap.entrySet()) {
            FieldValidationResult result = validateSingleField(
                    entry.getKey(),
                    entry.getValue(),
                    context,
                    vehicleCategory,
                    stageOfCompletion);

            if (result != null) {
                report.addFieldResult(result);
            }
        }

        log.info("校验完成, vehicleCategory={}, stageOfCompletion={}, allValid={}",
                vehicleCategory, stageOfCompletion, report.isAllValid());

        return report;
    }

    // ==========================================
    // 私有方法 - 字段校验
    // ==========================================

    /**
     * 校验单个字段
     */
    private FieldValidationResult validateSingleField(
            String jsonKey,
            Object value,
            Map<String, Object> context,
            String vehicleCategory,
            String stageOfCompletion) {

        // 1. 提取 dictCode
        Long dictCode = extractDictCode(jsonKey);
        if (dictCode == null) {
            log.debug("无法解析 dictCode, key={}", jsonKey);
            return null;
        }

        // 2. 查询字典数据（含 rule、rangeRule、vehicleCategory、stageOfCompletion 过滤）
        SysDictData dictData = queryDictData(dictCode, vehicleCategory, stageOfCompletion);
        if (dictData == null) {
            log.debug("未找到字典数据, dictCode={}, vehicleCategory={}, stageOfCompletion={}",
                    dictCode, vehicleCategory, stageOfCompletion);
            return null;
        }

        // 3. 解析规则
        List<RuleItem> rules = FinalRuleParser.parseRules(
                dictData.getRule(),
                dictData.getRangeRule());

        if (rules.isEmpty()) {
            return null;
        }

        // 4. 执行校验
        return FinalRuleExecutor.execute(jsonKey, value, rules, context);
    }

    // ==========================================
    // 私有方法 - 上下文构建
    // ==========================================

    /**
     * 构建校验上下文
     * 包含：所有普通字段值 + 列表字段 + vehicleCategory + stageOfCompletion
     */
    private Map<String, Object> buildContext(
            Map<String, Object> jsonMap,
            Map<String, List<Map<String, Object>>> listFields,
            String vehicleCategory,
            String stageOfCompletion) {

        Map<String, Object> context = new HashMap<>(jsonMap);

        // 注入列表字段（供 COUNT / SUM 聚合规则使用）
        if (listFields != null) {
            context.putAll(listFields);
        }

        // 注入车型和阶段（供条件规则使用）
        context.put("vehicleCategory", vehicleCategory);
        context.put("stageOfCompletion", stageOfCompletion);

        return context;
    }

    // ==========================================
    // 私有方法 - 字典查询
    // ==========================================

    /**
     * 根据 dictCode + vehicleCategory + stageOfCompletion 查询字典数据
     * 优先精确匹配，其次模糊匹配（vehicleCategory 或 stageOfCompletion 为空）
     */
    private SysDictData queryDictData(
            Long dictCode,
            String vehicleCategory,
            String stageOfCompletion) {
        try {
            // todo 校验实现
            List<SysDictData> list = Collections.emptyList();//remoteDictService.getDictDataByType(dictCode).getData();
            if (list == null || list.isEmpty()) return null;

            // 精确匹配
            for (SysDictData d : list) {
//                if (matches(d.getVehicleCategory(), vehicleCategory)
//                        && matches(d.getStageOfCompletion(), stageOfCompletion)) {
//                    return d;
//                }
            }

            // 降级：仅匹配 vehicleCategory
            for (SysDictData d : list) {
//                if (matches(d.getVehicleCategory(), vehicleCategory)) {
//                    return d;
//                }
            }

            // 降级：返回第一条
            return list.get(0);

        } catch (Exception e) {
            log.error("查询字典数据失败, dictCode={}, error={}", dictCode, e.getMessage(), e);
            return null;
        }
    }

    /**
     * 字段匹配（null 或空表示通配）
     */
    private boolean matches(String dictValue, String targetValue) {
        if (dictValue == null || dictValue.trim().isEmpty()) return true;
        return dictValue.trim().equals(targetValue);
    }

    // ==========================================
    // 私有方法 - JSON 解析
    // ==========================================

    private Map<String, Object> parseJson(String jsonStr) {
        try {
            return objectMapper.readValue(jsonStr, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.error("JSON 解析失败: {}", e.getMessage(), e);
            return null;
        }
    }

    // ==========================================
    // 私有方法 - 字段提取
    // ==========================================

    /**
     * 从 jsonKey 中提取 dictCode
     * 约定格式：field_{dictCode} 或 直接为数字字符串
     */
    private Long extractDictCode(String jsonKey) {
        if (jsonKey == null) return null;
        try {
            // 格式1：直接是数字
            return Long.parseLong(jsonKey);
        } catch (NumberFormatException e) {
            // 格式2：field_123456
            String[] parts = jsonKey.split("_");
            String last = parts[parts.length - 1];
            try {
                return Long.parseLong(last);
            } catch (NumberFormatException ex) {
                return null;
            }
        }
    }

    /**
     * 从 VehicleInfo 中提取 vehicleCategory
     */
    private String extractVehicleCategory(VehicleInfo vehicleInfo) {
        if (vehicleInfo == null) return null;
        try {
            Map<String, Object> jsonMap = parseJson(vehicleInfo.getJson());
            if (jsonMap == null) return null;
            Object val = jsonMap.get("vehicleCategory");
            return val == null ? null : val.toString();
        } catch (Exception e) {
            log.warn("提取 vehicleCategory 失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 从 VehicleInfo 中提取 stageOfCompletion
     */
    private String extractStageOfCompletion(VehicleInfo vehicleInfo) {
        if (vehicleInfo == null) return null;
        try {
            Map<String, Object> jsonMap = parseJson(vehicleInfo.getJson());
            if (jsonMap == null) return null;
            Object val = jsonMap.get("stageOfCompletion");
            return val == null ? null : val.toString();
        } catch (Exception e) {
            log.warn("提取 stageOfCompletion 失败: {}", e.getMessage());
            return null;
        }
    }
}