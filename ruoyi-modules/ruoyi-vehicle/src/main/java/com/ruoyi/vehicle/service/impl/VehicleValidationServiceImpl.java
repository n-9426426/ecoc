package com.ruoyi.vehicle.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.core.enums.RuleItemType;
import com.ruoyi.common.core.executor.FinalRuleExecutor;
import com.ruoyi.common.core.model.FieldValidationResult;
import com.ruoyi.common.core.model.RuleItem;
import com.ruoyi.common.core.model.RuleViolation;
import com.ruoyi.common.core.model.ValidationReport;
import com.ruoyi.common.core.parser.FinalRuleParser;
import com.ruoyi.common.core.parser.ValueMappingParser;
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
import java.util.stream.Collectors;

/**
 * 车辆信息校验 ServiceImpl
 * 基于 FinalRuleParser + FinalRuleExecutor 实现完整规则校验
 * 重构要点：
 * - jsonKey 直接匹配 SysDictData.keyMap（不再解析 dict_code）
 * - 一次性查询字典数据，构建本地索引
 * - 上下文字段名使用 dict_label（非原始 key）
 * - 支持 vehicleCategory/stageOfCompletion 条件匹配（通配符）
 * - 支持 DICT_MAP 值域校验：当 value_map 含 DICT_MAP 时，校验字段值是否在
 *   value_connection 合并映射表的目标值集合中
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

    @Override
    public ValidationReport validateByVehicleId(Long vehicleId) {
        VehicleInfo vehicleInfo = vehicleInfoMapper.selectVehicleInfoById(vehicleId);
        if (vehicleInfo == null) {
            log.warn("未找到车辆信息, vehicleId={}", vehicleId);
            return ValidationReport.fail("未找到车辆信息, vehicleId=" + vehicleId);
        }

        String vehicleCategory = extractVehicleCategoryFromJson(vehicleInfo.getJson());
        String stageOfCompletion = extractStageOfCompletionFromJson(vehicleInfo.getJson());
        return validate(vehicleInfo.getJson(), vehicleCategory, stageOfCompletion);
    }

    @Override
    public ValidationReport validate(String jsonStr, String vehicleCategory, String stageOfCompletion) {
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
        Map<String, List<Map<String, Object>>> listFields = VehicleFieldParser.parseListFieldsFromMap(jsonMap, remoteDictService);

        // 3. 构建上下文（字段名已转换为 dict_label）
        Map<String, Object> context = buildContext(jsonMap, listFields, vehicleCategory, stageOfCompletion);

        // 4. 遍历每个字段逐一校验
        for (Map.Entry<String, Object> entry : jsonMap.entrySet()) {
            String jsonKey = entry.getKey();
            Object value = entry.getValue();

            FieldValidationResult result = validateSingleField(jsonKey, value, context, vehicleCategory, stageOfCompletion);
            if (result != null) {
                report.addFieldResult(result);
            }
        }

        report.setAllValid(report.getFieldResults().stream().allMatch(FieldValidationResult::isValid));
        log.info("校验完成, vehicleCategory={}, stageOfCompletion={}, allValid={}",
                vehicleCategory, stageOfCompletion, report.isAllValid());

        return report;
    }

    // ==========================================
    // 私有方法 - 字段校验
    // ==========================================

    private FieldValidationResult validateSingleField(
            String jsonKey,
            Object value,
            Map<String, Object> context,
            String vehicleCategory,
            String stageOfCompletion) {

        SysDictData dictData = queryDictData(jsonKey, vehicleCategory, stageOfCompletion);
        if (dictData == null) {
            log.debug("未找到字典数据, jsonKey={}, vehicleCategory={}, stageOfCompletion={}",
                    jsonKey, vehicleCategory, stageOfCompletion);
            return null;
        }

        // ① 执行 rule / rangeRule 规则校验（原有逻辑）
        List<RuleItem> rules = FinalRuleParser.parseRules(dictData.getRule(), dictData.getRangeRule());
        FieldValidationResult result = null;
        if (!rules.isEmpty()) {
            // ⚠️ 注意：此处传入的是原始 jsonKey，但 context 中字段名已是 dict_label
            // FinalRuleExecutor 内部会从 context 查 dict_label 字段（正确）
            result = FinalRuleExecutor.execute(jsonKey, value, rules, context);
        }

        // ② 若 value_map 含 DICT_MAP，额外校验字段值是否在目标值集合内
        FieldValidationResult dictMapResult = validateDictMapValue(jsonKey, value, dictData);
        if (dictMapResult != null) {
            if (result == null) {
                result = dictMapResult;
            } else {
                // 将 DICT_MAP 违规合并到已有结果中
                result.getViolations().addAll(dictMapResult.getViolations());
                if (!dictMapResult.isValid()) {
                    result.setValid(false);
                }
            }
        }

        return result;
    }

    // ==========================================
    // 私有方法 - DICT_MAP 值域校验
    // ==========================================

    /**
     * 当 value_map 含 DICT_MAP 时，校验字段值是否在 value_connection 合并映射表的
     * <b>目标值集合</b>（即映射后的值，如 en、bg、cs 等）中。
     *
     * <p>判断逻辑：
     * <ol>
     *   <li>value_map 为空 或 不含 "DICT_MAP" 关键字 → 跳过，返回 null</li>
     *   <li>value_connection 为空 → 跳过（无法校验），返回 null</li>
     *   <li>字段值为空 → 跳过（由 VALUE IS PRESENT 规则负责必填校验），返回 null</li>
     *   <li>字段值（去除首尾空格后）在目标值集合中 → 通过，返回 null</li>
     *   <li>否则 → 返回包含违规信息的 FieldValidationResult</li>
     * </ol>
     *
     * @param jsonKey  字段 jsonKey（仅用于日志与报告展示）
     * @param value    字段实际值
     * @param dictData 字典数据（含 valueMap、valueConnection）
     * @return 校验不通过时返回带违规的结果，通过或跳过时返回 null
     */
    private FieldValidationResult validateDictMapValue(String jsonKey, Object value, SysDictData dictData) {
        // 1. value_map 不含 DICT_MAP，跳过
        if (!containsDictMap(dictData.getValueMap())) {
            return null;
        }

        // 2. value_connection 为空，无法校验，跳过
        String valueConnectionJson = dictData.getValueConnection();
        if (valueConnectionJson == null || valueConnectionJson.trim().isEmpty()) {
            log.debug("DICT_MAP 校验跳过（value_connection 为空）, jsonKey={}", jsonKey);
            return null;
        }

        // 3. 字段值为空，跳过（由必填规则负责）
        if (value == null || value.toString().trim().isEmpty()) {
            return null;
        }

        // 4. 解析 value_connection，得到 {原始值 → 目标值} 的合并映射表
        Map<String, String> mergedDictMap = ValueMappingParser.mergeValueConnection(valueConnectionJson);
        if (mergedDictMap.isEmpty()) {
            log.warn("DICT_MAP 校验跳过（value_connection 解析结果为空）, jsonKey={}", jsonKey);
            return null;
        }

        // 5. 提取目标值集合（映射后的合法值，如 en、bg、AA、HYDRL 等）
        Set<String> allowedValues = new HashSet<>(mergedDictMap.values());

        // 6. 校验当前字段值（支持多值字段，以 | 或 ; 分隔）
        String rawStr = value.toString().trim();
        String[] parts = FinalRuleParser.splitMultiValue(rawStr);

        List<RuleViolation> violations = new ArrayList<>();
        for (String part : parts) {
            String trimmed = part.trim();
            if (!allowedValues.contains(trimmed)) {
                String suffix = parts.length > 1
                        ? " [子值='" + trimmed + "'，原始值='" + rawStr + "']"
                        : "";
                violations.add(RuleViolation.builder()
                        .fieldName(jsonKey)
                        .actualValue(value == null ? null : String.valueOf(value))
                        .ruleType(RuleItemType.VALUE_IN)
                        .ruleTypeLabel("DICT_MAP值域校验")
                        .rawRule("DICT_MAP")
                        .messageEn("Value '" + trimmed + "' is not in the allowed target value set of DICT_MAP"
                                + suffix)
                        .messageZh("字段值 '" + trimmed + "' 不在 DICT_MAP 目标值集合中，"
                                + "允许的值为: " + formatAllowedValues(allowedValues) + suffix)
                        .build());
            }
        }

        if (violations.isEmpty()) {
            return null;
        }

        return FieldValidationResult.builder()
                .fieldName(jsonKey)
                .value(value)
                .valid(false)
                .violations(violations)
                .build();
    }

    /**
     * 判断 value_map 是否含有 DICT_MAP 步骤。
     * 支持以下几种形式：
     * <ul>
     *   <li>{@code DICT_MAP}                  — 直接等于</li>
     *   <li>{@code PIPE:DIRECT|DICT_MAP}       — 管道末尾含 DICT_MAP</li>
     *   <li>{@code PIPE:EXTRACT_PATTERN:...|DICT_MAP} — 管道中间含 DICT_MAP</li>
     * </ul>
     */
    private boolean containsDictMap(String valueMap) {
        if (valueMap == null || valueMap.trim().isEmpty()) {
            return false;
        }
        // 大小写不敏感地检测管道步骤或整体是否等于 DICT_MAP
        String upper = valueMap.trim().toUpperCase();
        // 匹配整体等于 DICT_MAP，或作为 PIPE 中某一段出现（以 | 分隔）
        return upper.equals("DICT_MAP")
                || upper.contains("|DICT_MAP")
                || upper.startsWith("DICT_MAP|");
    }

    /**
     * 将允许值集合格式化为可读字符串（排序后展示，避免每次输出顺序不一致）。
     */
    private String formatAllowedValues(Set<String> allowedValues) {
        List<String> sorted = new ArrayList<>(allowedValues);
        Collections.sort(sorted);
        return sorted.toString();
    }

    // ==========================================
    // 私有方法 - 上下文构建（关键：key 转 dict_label）
    // ==========================================

    private Map<String, Object> buildContext(
            Map<String, Object> jsonMap,
            Map<String, List<Map<String, Object>>> listFields,
            String vehicleCategory,
            String stageOfCompletion) {

        Map<String, Object> context = new HashMap<>();

        // ✅ 将 jsonMap 的 key（如 "20.98.12"）映射为 dict_label（如 "BrakedAxleIndicator"）
        Map<String, SysDictData> keyMapIndex = buildKeyMapIndex();
        for (Map.Entry<String, Object> entry : jsonMap.entrySet()) {
            String jsonKey = entry.getKey();
            Object value = entry.getValue();

            String fieldName = resolveDictLabel(jsonKey, keyMapIndex);
            context.put(fieldName, value);
        }

        // 注入列表字段（VehicleFieldParser 已转为 dict_label，无需重复转换）
        if (listFields != null) {
            context.putAll(listFields);
        }

        // 注入上下文变量
        context.put("VehicleCategory", vehicleCategory);
        context.put("StageOfCompletion", stageOfCompletion);

        // 注入虚拟变量（@FullyElectricVehicle / @NotFullyElectricVehicle / @HybridVehicle）
        Object energySourceVal = context.get("EnergySource");
        String energySource = energySourceVal != null ? energySourceVal.toString().trim() : "";

        Object pureElectricVal = context.get("PureElectricVehicleIndicator");
        String pureElectric = pureElectricVal != null ? pureElectricVal.toString().trim() : "";

        // 纯电动：EnergySource=95 或 PureElectricVehicleIndicator=Y
        boolean isFullyElectric = "95".equals(energySource) || "Y".equals(pureElectric);

        // 混动：ClassHybridVehicle 有值且非纯电
        Object hybridVal = context.get("ClassHybridVehicle");
        String hybridClass = hybridVal != null ? hybridVal.toString().trim() : "";
        boolean isHybrid = !hybridClass.isEmpty() && !isFullyElectric;

        // null 表示条件不满足，executor 的 isAbsent 会判定为缺失
        context.put("FullyElectricVehicle",    isFullyElectric ? "Y" : null);
        context.put("NotFullyElectricVehicle", !isFullyElectric ? "Y" : null);
        context.put("HybridVehicle",           isHybrid ? "Y" : null);

        return context;
    }

    // ==========================================
    // 私有方法 - 字典查询（核心重构）
    // ==========================================

    /**
     * 根据 jsonKey + vehicleCategory + stageOfCompletion 查询字典数据
     * 匹配顺序：
     * 1. 精确匹配 keyMap
     * 2. 通配匹配（vehicleCategory/stageOfCompletion）
     * 3. dictLabel 匹配（兜底）
     */
    private SysDictData queryDictData(String jsonKey, String vehicleCategory, String stageOfCompletion) {
        try {
            List<SysDictData> allDict = remoteDictService.getDictDataByType("vehicle_attribute").getData();
            if (allDict == null || allDict.isEmpty()) {
                return null;
            }

            // 构建 keyMap -> List<SysDictData> 索引
            Map<String, List<SysDictData>> keyMapMap = allDict.stream()
                    .filter(d -> d.getKeyMap() != null && !d.getKeyMap().trim().isEmpty())
                    .collect(Collectors.groupingBy(SysDictData::getKeyMap));

            // 1. 精确匹配 keyMap
            List<SysDictData> candidates = keyMapMap.get(jsonKey);
            if (candidates != null && !candidates.isEmpty()) {
                return findBestMatch(candidates, vehicleCategory, stageOfCompletion);
            }

            // 2. dictLabel 匹配（兜底，兼容旧数据）
            for (SysDictData d : allDict) {
                if (d.getDictLabel() != null && d.getDictLabel().equals(jsonKey)) {
                    return d;
                }
            }

            return null;
        } catch (Exception e) {
            log.error("查询字典数据失败", e);
            return null;
        }
    }

    /**
     * 从多个候选中选择最佳匹配（按 vehicleCategory/stageOfCompletion 匹配度打分）
     */
    private SysDictData findBestMatch(List<SysDictData> candidates, String vehicleCategory, String stageOfCompletion) {
        SysDictData best = null;
        int bestScore = -1;

        for (SysDictData d : candidates) {
            int score = 0;
            if (matchesWildcard(d.getDictTypeAffiliationStr(), vehicleCategory)) score += 2;
//            if (matchesWildcard(d.getStageOfCompletion(), stageOfCompletion)) score += 1;

            if (score > bestScore) {
                bestScore = score;
                best = d;
            }
        }

        return best;
    }

    /**
     * 通配符匹配：支持 "*" 和 "prefix*"
     */
    private boolean matchesWildcard(String pattern, String value) {
        if (pattern == null || pattern.trim().isEmpty() || "*".equals(pattern.trim())) {
            return true;
        }
        if (value == null) return false;
        pattern = pattern.trim();
        value = value.trim();
        if (pattern.endsWith("*")) {
            return value.startsWith(pattern.substring(0, pattern.length() - 1));
        }
        return pattern.equals(value);
    }

    // ==========================================
    // 私有方法 - 工具方法（复用 VehicleFieldParser 逻辑）
    // ==========================================

    private Map<String, SysDictData> buildKeyMapIndex() {
        try {
            List<SysDictData> all = remoteDictService.getDictDataByType("vehicle_attribute").getData();
            return all == null ? new HashMap<>() : all.stream()
                    .filter(d -> d.getKeyMap() != null && !d.getKeyMap().isEmpty())
                    .collect(Collectors.toMap(SysDictData::getKeyMap, d -> d, (e1, e2) -> e1));
        } catch (Exception e) {
            log.error("构建 keyMap 索引失败", e);
            return new HashMap<>();
        }
    }

    private String resolveDictLabel(String key, Map<String, SysDictData> keyMapIndex) {
        SysDictData d = keyMapIndex.get(key);
        return d != null && d.getDictLabel() != null ? d.getDictLabel() : key;
    }

    private Map<String, Object> parseJson(String jsonStr) {
        try {
            return objectMapper.readValue(jsonStr, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.error("JSON 解析失败: {}", e.getMessage(), e);
            return null;
        }
    }

    // 从 JSON 字符串中提取 vehicleCategory 和 stageOfCompletion（避免重复解析）
    private String extractVehicleCategoryFromJson(String jsonStr) {
        if (jsonStr == null) return null;
        try {
            Map<String, Object> map = parseJson(jsonStr);
            if (map == null) return null;
            Object v = map.get("VehicleCategory");
            return v == null ? null : v.toString();
        } catch (Exception e) {
            log.warn("提取 VehicleCategory 失败", e);
            return null;
        }
    }

    private String extractStageOfCompletionFromJson(String jsonStr) {
        if (jsonStr == null) return null;
        try {
            Map<String, Object> map = parseJson(jsonStr);
            if (map == null) return null;
            Object v = map.get("StageOfCompletion");
            return v == null ? null : v.toString();
        } catch (Exception e) {
            log.warn("提取 stageOfCompletion 失败", e);
            return null;
        }
    }
}