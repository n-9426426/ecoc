package com.ruoyi.vehicle.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.core.executor.FinalRuleExecutor;
import com.ruoyi.common.core.model.FieldValidationResult;
import com.ruoyi.common.core.model.RuleItem;
import com.ruoyi.common.core.model.RuleViolation;
import com.ruoyi.common.core.model.ValidationReport;
import com.ruoyi.common.core.parser.FinalRuleParser;
import com.ruoyi.system.api.RemoteDictService;
import com.ruoyi.system.api.domain.SysDictData;
import com.ruoyi.vehicle.domain.VehicleInfo;
import com.ruoyi.vehicle.mapper.VehicleInfoMapper;
import com.ruoyi.vehicle.service.IVehicleValidationService;
import com.ruoyi.vehicle.utils.VehicleFieldParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 车辆信息校验 ServiceImpl
 */
@Service("vehicleValidationService")
public class VehicleValidationServiceImpl implements IVehicleValidationService {

    private static final Logger log = LoggerFactory.getLogger(VehicleValidationServiceImpl.class);

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
            ValidationReport report = new ValidationReport();
            report.setAllValid(false);
            report.setError("未找到车辆信息, vehicleId=" + vehicleId);
            return report;
        }

        String vehicleCategory = extractVehicleCategory(vehicleInfo);
        String stageOfCompletion = extractStageOfCompletion(vehicleInfo);

        return validate(vehicleInfo.getJson(), vehicleCategory, stageOfCompletion);
    }

    /**
     * 校验车辆信息 JSON中的所有字段
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
                .build();

        if (jsonStr == null || jsonStr.trim().isEmpty()) {
            log.warn("JSON为空，跳过校验");
            return report;
        }

        // 1. 解析 JSON
        Map<String, Object> jsonMap = parseJson(jsonStr);
        if (jsonMap == null) {
            report.setAllValid(false);
            report.setError("JSON格式错误");
            return report;
        }

        // 2. 解析列表字段（axleList, bodyworkList 等）
        Map<String, List<Map<String, Object>>> listFields =
                VehicleFieldParser.parseListFieldsFromMap(jsonMap, remoteDictService);

        // 3. 构建上下文（所有字段值+ 车型+ 阶段）
        Map<String, Object> contextFields =
                buildContextFields(jsonMap, vehicleCategory, stageOfCompletion);

        // 4. 遍历每个字段逐一校验
        List<FieldValidationResult> allResults = new ArrayList<>();
        boolean allValid = true;

        for (Map.Entry<String, Object> entry : jsonMap.entrySet()) {
            String jsonKey = entry.getKey();
            Object value = entry.getValue();

            FieldValidationResult result = validateSingleField(
                    jsonKey, value, contextFields, listFields,
                    vehicleCategory, stageOfCompletion);

            if (result != null) {
                allResults.add(result);
                if (!result.isValid()) {
                    allValid = false;
                }
            }
        }

        report.setAllValid(allValid);
        report.setFieldResults(allResults);

        log.info("校验完成, vehicleCategory={}, stageOfCompletion={}, allValid={}",
                vehicleCategory, stageOfCompletion, allValid);

        return report;
    }

    // ==========================================
    // 私有方法
    // ==========================================

    /**
     * 校验单个字段
     */
    private FieldValidationResult validateSingleField(
            String jsonKey,
            Object value,
            Map<String, Object> contextFields,
            Map<String, List<Map<String, Object>>> listFields,
            String vehicleCategory,
            String stageOfCompletion) {

        // 1. 提取 dict_code
        Long dictCode = extractDictCode(jsonKey);
        if (dictCode == null) {
            log.warn("无法解析 dict_code, key={}", jsonKey);
            return null;
        }

        // 2. 查询字典数据
        SysDictData dictData = remoteDictService.getDataByDictCode(dictCode).getData();
        if (dictData == null) {
            log.warn("未找到字典数据, dict_code={}", dictCode);
            return null;
        }

        String ruleStr  = dictData.getRule();
        String rangeStr = dictData.getRangeRule();

        // 3. 无规则且无值范围，直接通过
        if (isBlank(ruleStr) && isBlank(rangeStr)) {
            return FieldValidationResult.builder()
                    .fieldName(dictData.getDictLabel())
                    .value(value)
                    .valid(true)
                    .violations(Collections.emptyList())
                    .build();
        }

        // 4. 同时解析正式规则 + 值范围规则，合并为一个 RuleItem 列表
        List<RuleItem> rules = FinalRuleParser.parseRulesWithRange(
                ruleStr  != null ? ruleStr  : "",
                rangeStr != null ? rangeStr : ""
        );

        // 5. 执行校验
        List<RuleViolation> violations = executeRules(
                rules, value, contextFields, listFields,
                vehicleCategory, stageOfCompletion);

        return FieldValidationResult.builder()
                .fieldName(dictData.getDictLabel())
                .value(value)
                .valid(violations.isEmpty())
                .violations(violations)
                .build();
    }

    /**
     * 执行规则列表，返回所有违规信息
     */
    private List<RuleViolation> executeRules(
            List<RuleItem> rules,
            Object value,
            Map<String, Object> contextFields,
            Map<String, List<Map<String, Object>>> listFields,
            String vehicleCategory,
            String stageOfCompletion) {

        List<RuleViolation> violations = new ArrayList<>();

        for (RuleItem rule : rules) {
            // 检查规则是否适用于当前车型和阶段
            if (!isRuleApplicable(rule, vehicleCategory, stageOfCompletion)) {
                continue;
            }

            RuleViolation violation = FinalRuleExecutor.execute(rule, value, contextFields, listFields);

            if (violation != null) {
                violations.add(violation);
            }
        }

        return violations;
    }

    /**
     * 检查规则是否适用于当前车型和阶段
     */
    private boolean isRuleApplicable(
            RuleItem rule, String vehicleCategory, String stageOfCompletion) {

        List<String> applicableCategories = rule.getApplicableCategories();

        // 无车型限制，适用所有车型
        if (applicableCategories == null || applicableCategories.isEmpty()) {
            return true;
        }

        return applicableCategories.stream().anyMatch(cat -> {
            // 支持前缀通配符（Mx匹配 M1、M2、M3 等）
            if (cat.endsWith("x")) {
                String prefix = cat.substring(0, cat.length() - 1);
                return vehicleCategory != null && vehicleCategory.startsWith(prefix);
            }
            return cat.equalsIgnoreCase(vehicleCategory);
        });
    }

    /**
     * 构建上下文字段 Map
     * 包含所有 JSON 字段值+ vehicleCategory + stageOfCompletion
     */
    private Map<String, Object> buildContextFields(
            Map<String, Object> jsonMap,
            String vehicleCategory,
            String stageOfCompletion) {

        Map<String, Object> context = new HashMap<>();
        context.put("vehicleCategory", vehicleCategory);
        context.put("stageOfCompletion", stageOfCompletion);

        for (Map.Entry<String, Object> entry : jsonMap.entrySet()) {
            Long dictCode = extractDictCode(entry.getKey());
            if (dictCode == null) continue;

            SysDictData dictData = remoteDictService.getDataByDictCode(dictCode).getData();
            if (dictData != null && dictData.getDictLabel() != null) {
                context.put(dictData.getDictLabel(), entry.getValue());
            }
        }

        return context;
    }

    /**
     * 解析 JSON 字符串为 Map
     */
    private Map<String, Object> parseJson(String jsonStr) {
        try {
            return objectMapper.readValue(jsonStr,
                    new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.error("JSON解析失败: {}", jsonStr, e);
            return null;
        }
    }

    /**
     * 从 JSON key 中提取最后一段数字作为 dict_code
     *例如: "40.41.42" → 42
     */
    private Long extractDictCode(String jsonKey) {
        if (jsonKey == null || jsonKey.isEmpty()) return null;
        String[] parts = jsonKey.split("\\.");
        try {
            return Long.parseLong(parts[parts.length - 1]);
        } catch (NumberFormatException e) {
            log.warn("dict_code 解析失败, key={}", jsonKey);
            return null;
        }
    }

    /**
     * 从 VehicleInfo 中提取车型
     */
    private String extractVehicleCategory(VehicleInfo vehicleInfo) {
        try {
            if (vehicleInfo.getJson() == null) return "";
            Map<String, Object> jsonMap = parseJson(vehicleInfo.getJson());
            if (jsonMap == null) return "";
            for (Map.Entry<String, Object> entry : jsonMap.entrySet()) {
                Long dictCode = extractDictCode(entry.getKey());
                if (dictCode == null) continue;
                SysDictData dictData = remoteDictService.getDataByDictCode(dictCode).getData();
                if (dictData != null && "vehicleCategory".equals(dictData.getDictLabel())) {
                    return entry.getValue() != null ? entry.getValue().toString() : "";
                }
            }
        } catch (Exception e) {
            log.error("提取 vehicleCategory 失败", e);
        }
        return "";
    }

    /**
     * 从 VehicleInfo 中提取完成阶段
     */
    private String extractStageOfCompletion(VehicleInfo vehicleInfo) {
        try {
            if (vehicleInfo.getJson() == null) return "";
            Map<String, Object> jsonMap = parseJson(vehicleInfo.getJson());
            if (jsonMap == null) return "";
            for (Map.Entry<String, Object> entry : jsonMap.entrySet()) {
                Long dictCode = extractDictCode(entry.getKey());
                if (dictCode == null) continue;
                SysDictData dictData = remoteDictService.getDataByDictCode(dictCode).getData();
                if (dictData != null && "stageOfCompletion".equals(dictData.getDictLabel())) {
                    return entry.getValue() != null ? entry.getValue().toString() : "";
                }
            }
        } catch (Exception e) {
            log.error("提取 stageOfCompletion 失败", e);
        }
        return "";
    }

    /**
     * 判断字符串是否为空
     */
    private boolean isBlank(String str) {
        return str == null || str.trim().isEmpty();
    }
}