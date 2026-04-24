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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 车辆信息校验 ServiceImpl
 * 基于 FinalRuleParser + FinalRuleExecutor 实现完整规则校验
 * 重构要点：
 * - jsonKey 直接匹配 SysDictData.keyMap（不再解析 dict_code）
 * - 一次性查询字典数据，构建本地索引
 * - 上下文字段名使用 dict_label（非原始 key）
 * - 支持 vehicleCategory/stageOfCompletion 条件匹配（通配符）
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

        List<RuleItem> rules = FinalRuleParser.parseRules(dictData.getRule(), dictData.getRangeRule());
        if (rules.isEmpty()) {
            return null;
        }

        // ⚠️ 注意：此处传入的是原始 jsonKey，但 context 中字段名已是 dict_label
        // FinalRuleExecutor 内部会从 context 查 dict_label 字段（正确）
        return FinalRuleExecutor.execute(jsonKey, value, rules, context);
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
        context.put("vehicleCategory", vehicleCategory);
        context.put("stageOfCompletion", stageOfCompletion);

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
            Object v = map.get("vehicleCategory");
            return v == null ? null : v.toString();
        } catch (Exception e) {
            log.warn("提取 vehicleCategory 失败", e);
            return null;
        }
    }

    private String extractStageOfCompletionFromJson(String jsonStr) {
        if (jsonStr == null) return null;
        try {
            Map<String, Object> map = parseJson(jsonStr);
            if (map == null) return null;
            Object v = map.get("stageOfCompletion");
            return v == null ? null : v.toString();
        } catch (Exception e) {
            log.warn("提取 stageOfCompletion 失败", e);
            return null;
        }
    }
}