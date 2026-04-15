package com.ruoyi.common.core.executor;

import com.ruoyi.common.core.enums.CompareOperator;
import com.ruoyi.common.core.model.*;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 完整规则执行器（支持所有规则类型）
 */
@Slf4j
public class FinalRuleExecutor {

    /**
     * 执行单条规则
     *
     * @param rule          规则项
     * @param currentValue  当前字段的值
     * @param contextFields 上下文字段Map（所有字段的值）
     * @param listFields    列表字段Map（axleList等）
     * @return 违规信息，null表示通过
     */
    public static RuleViolation execute(
            RuleItem rule,
            Object currentValue,
            Map<String, Object> contextFields,
            Map<String, List<Map<String, Object>>> listFields) {

        if (rule == null || rule.getType() == null) {
            return null;
        }

        boolean violated;

        try {
            switch (rule.getType()) {

                //========== 嵌套条件规则 ==========
                case NESTED_CONDITION:
                    violated = checkNestedCondition(rule, currentValue, contextFields, listFields);
                    break;

                // ========== 基础规则 ==========
                case VALUE_IS_PRESENT_IF_FIELD_REF:
                    violated = checkValueIsPresentIfFieldRef(rule, currentValue, contextFields);
                    break;

                case VALUE_IS_PRESENT_IF:
                    violated = checkValueIsPresentIf(rule, currentValue, contextFields);
                    break;

                case VALUE_IS_ABSENT:
                    violated = !isAbsent(currentValue);
                    break;

                case VALUE_IN:
                    violated = checkValueIn(rule, currentValue);
                    break;

                case VALUE_REGEX:
                    violated = checkValueRegex(rule, currentValue);
                    break;

                case VALUE_COMPARE:
                    violated = checkValueCompare(rule, currentValue, contextFields);
                    break;

                // ========== 范围规则 ==========
                case RANGE:
                    violated = checkRange(rule, currentValue);
                    break;

                case NUMERIC_RANGE:
                    violated = checkNumericRange(rule, currentValue);
                    break;

                case LENGTH_RANGE:
                    violated = checkLengthRange(rule, currentValue);
                    break;

                case MAX_LENGTH:
                    violated = checkMaxLength(rule, currentValue);
                    break;

                case MIN_LENGTH:
                    violated = checkMinLength(rule, currentValue);
                    break;

                case TOTAL_DIGITS:
                    violated = checkTotalDigits(rule, currentValue);
                    break;

                case FRACTION_DIGITS:
                    violated = checkFractionDigits(rule, currentValue);
                    break;

                // ========== 条件规则 ==========
                case FORBIDDEN_IF:
                    violated = checkForbiddenIf(rule, currentValue, contextFields);
                    break;

                case MANDATORY_IF:
                    violated = checkMandatoryIf(rule, currentValue, contextFields);
                    break;

                case FIELD_IS_NULL:
                    violated = checkFieldIsNull(rule, contextFields);
                    break;

                case FIELD_IS_ABSENT:
                    violated = checkFieldIsAbsent(rule, contextFields);
                    break;

                // ========== 列表规则 ==========
                case ANY_CONDITION:
                    violated = checkAnyCondition(rule, currentValue, contextFields, listFields);
                    break;

                case ALL_CONDITION:
                    violated = checkAllCondition(rule, listFields);
                    break;

                // ========== 聚合规则 ==========
                case COUNT_AGGREGATE:
                    violated = checkCountAggregate(rule, currentValue, listFields);
                    break;

                case SUM_AGGREGATE:
                    violated = checkSumAggregate(rule, currentValue, listFields);
                    break;

                default:
                    log.warn("未知规则类型: {}", rule.getType());
                    return null;
            }
        } catch (Exception e) {
            log.error("规则执行异常, ruleId={}, type={}", rule.getRuleId(), rule.getType(), e);
            return null;
        }

        if (violated) {
            return buildViolation(rule);
        }
        return null;
    }

    // ==========================================
    // 嵌套条件规则
    // ==========================================

    /**
     * 嵌套条件规则
     *例如: VALUE = /HEV$/ IF ANY EnergySource = '95' IF ANYEngineCapacity IS PRESENT
     * 先评估所有条件链，条件都满足时再执行主规则校验
     */
    @SuppressWarnings("unchecked")
    private static boolean checkNestedCondition(
            RuleItem rule,
            Object currentValue,
            Map<String, Object> contextFields,
            Map<String, List<Map<String, Object>>> listFields) {NestedConditionRule nestedRule = (NestedConditionRule) rule.getCompareValue();
        if (nestedRule == null) return false;

        // 1. 先检查所有条件链是否满足
        List<ConditionChain> chains = nestedRule.getConditionChains();
        if (chains != null) {
            for (ConditionChain chain : chains) {
                boolean conditionMet = evaluateConditionChain(chain, contextFields, listFields);
                if (!conditionMet) {
                    // 条件不满足，不执行主规则，视为不违规
                    return false;
                }
            }
        }

        // 2. 所有条件满足，执行主规则
        switch (nestedRule.getMainRuleType()) {
            case VALUE_REGEX:
                String regex = (String) nestedRule.getMainRuleContent();
                if (isAbsent(currentValue)) return false;
                try {
                    return !Pattern.matches(regex, currentValue.toString());
                } catch (Exception e) {
                    log.error("正则表达式错误: {}", regex, e);
                    return false;
                }

            case VALUE_IN:
                List<String> enumValues = (List<String>) nestedRule.getMainRuleContent();
                if (isAbsent(currentValue)) return false;
                return enumValues == null || !enumValues.contains(currentValue.toString());

            case VALUE_IS_ABSENT:
                return !isAbsent(currentValue);

            default:
                log.warn("嵌套规则不支持的主规则类型: {}", nestedRule.getMainRuleType());
                return false;
        }
    }

    /**
     * 评估单个条件链节点（IF ANY / IF ALL）
     */
    private static boolean evaluateConditionChain(
            ConditionChain chain,
            Map<String, Object> contextFields,
            Map<String, List<Map<String, Object>>> listFields) {

        if (chain == null) return false;

        String conditionType = chain.getConditionType(); // IF_ANY or IF_ALL
        String fieldName = chain.getFieldName();
        String operator = chain.getOperator();
        String value = chain.getValue();

        if ("IF_ANY".equals(conditionType)) {
            // 任意一条匹配即返回true
            // 1. 先从context中查找
            Object ctxValue = contextFields.get(fieldName);
            if (ctxValue != null && evaluateSingleOperator(ctxValue, operator, value)) {
                return true;
            }
            // 2. 再从listFields中查找
            for (List<Map<String, Object>> list : listFields.values()) {
                if (list == null) continue;
                for (Map<String, Object> item : list) {
                    Object actualValue = item.get(fieldName);
                    if (evaluateSingleOperator(actualValue, operator, value)) {
                        return true;
                    }
                }
            }
            return false;

        } else if ("IF_ALL".equals(conditionType)) {
            // 所有条目都满足才返回true
            boolean found = false;
            for (List<Map<String, Object>> list : listFields.values()) {
                if (list == null) continue;
                for (Map<String, Object> item : list) {
                    found = true;
                    Object actualValue = item.get(fieldName);
                    if (!evaluateSingleOperator(actualValue, operator, value)) {
                        return false; // 有一个不满足
                    }
                }
            }
            return found; // 列表非空且全部满足
        }

        return false;
    }

    /**
     * 评估单个值与运算符和期望值的关系
     */
    private static boolean evaluateSingleOperator(
            Object actualValue, String operator, String expectedValue) {
        switch (operator) {
            case "=":
                return !isAbsent(actualValue)&& actualValue.toString().equals(expectedValue);
            case "!=":
                if (isAbsent(actualValue)) return true;
                return !actualValue.toString().equals(expectedValue);
            case "IS_PRESENT":
                return !isAbsent(actualValue);
            case "IS_ABSENT":
                return isAbsent(actualValue);
            case "<=":
                return compareNumeric(actualValue, expectedValue) <= 0;
            case ">=":
                return compareNumeric(actualValue, expectedValue) >= 0;
            case "<":
                return compareNumeric(actualValue, expectedValue) < 0;
            case ">":
                return compareNumeric(actualValue, expectedValue) > 0;
            default:
                log.warn("未知运算符: {}", operator);
                return false;
        }
    }

    // ==========================================
    // 基础规则
    // ==========================================

    /**
     * VALUE IS PRESENT IF @fieldName
     * 当 @fieldName 字段有值时，当前字段必须有值
     */
    private static boolean checkValueIsPresentIfFieldRef(
            RuleItem rule, Object currentValue, Map<String, Object> contextFields) {
        String targetField = rule.getTargetFieldName();
        if (targetField == null) return false;
        Object targetValue = contextFields.get(targetField);
        // 目标字段有值，当前字段必须有值
        if (!isAbsent(targetValue)) {
            return isAbsent(currentValue); // 当前字段为空 → 违规
        }
        return false;
    }

    /**
     * VALUE IS PRESENT IF fieldName IS ABSENT/PRESENT
     * 根据目标字段的存在状态决定当前字段是否必填
     */
    private static boolean checkValueIsPresentIf(
            RuleItem rule, Object currentValue, Map<String, Object> contextFields) {
        String targetField = rule.getTargetFieldName();
        String condition = (String) rule.getCompareValue(); // "ABSENT" or "PRESENT"
        if (targetField == null || condition == null) return false;

        Object targetValue = contextFields.get(targetField);
        boolean targetIsAbsent = isAbsent(targetValue);

        boolean conditionMet;
        if ("ABSENT".equals(condition)) {
            conditionMet = targetIsAbsent;
        } else { // PRESENT
            conditionMet = !targetIsAbsent;
        }

        if (conditionMet) {
            return isAbsent(currentValue); // 条件满足但当前字段为空 → 违规
        }
        return false;
    }

    /**
     * VALUE IN ['AA', 'BB', ...]
     * 当前字段必须在枚举列表中
     */
    private static boolean checkValueIn(RuleItem rule, Object currentValue) {
        if (isAbsent(currentValue)) return false;
        List<String> enumValues = rule.getEnumValues();
        if (enumValues == null || enumValues.isEmpty()) return false;
        return !enumValues.contains(currentValue.toString().trim());
    }

    /**
     * VALUE = /regex/
     * 当前字段必须匹配正则表达式
     */
    private static boolean checkValueRegex(RuleItem rule, Object currentValue) {
        if (isAbsent(currentValue)) return false;
        String pattern = rule.getRegexPattern();
        if (pattern == null || pattern.isEmpty()) return false;
        try {
            return !Pattern.matches(pattern, currentValue.toString());
        } catch (Exception e) {
            log.error("正则匹配异常, pattern={}", pattern, e);
            return false;
        }
    }

    /**
     * VALUE >= 数值 / VALUE = 数值 / VALUE <= 数值 等
     * 支持与上下文中其他字段值比较（当compareValue为字段名时）
     */
    private static boolean checkValueCompare(
            RuleItem rule, Object currentValue, Map<String, Object> contextFields) {
        if (isAbsent(currentValue)) return false;
        CompareOperator op = rule.getCompareOperator();
        Object compareValueObj = rule.getCompareValue();
        if (op == null || compareValueObj == null) return false;

        String compareValueStr = compareValueObj.toString();

        // 判断compareValue是否是上下文中的字段名
        Object resolvedCompare;
        if (contextFields.containsKey(compareValueStr)) {
            resolvedCompare = contextFields.get(compareValueStr);
        } else {
            resolvedCompare = compareValueStr;
        }

        if (isAbsent(resolvedCompare)) return false;

        try {
            double currentDouble = Double.parseDouble(currentValue.toString());
            double compareDouble = Double.parseDouble(resolvedCompare.toString());

            switch (op) {
                case EQ:  return Math.abs(currentDouble - compareDouble) > 1e-9;
                case NEQ: return Math.abs(currentDouble - compareDouble) <= 1e-9;
                case GT:  return currentDouble <= compareDouble;
                case LT:  return currentDouble >= compareDouble;
                case GTE: return currentDouble < compareDouble;
                case LTE: return currentDouble > compareDouble;
                default:return false;
            }
        } catch (NumberFormatException e) {
            // 非数值，做字符串比较
            String currentStr = currentValue.toString();
            String compareStr = resolvedCompare.toString();
            switch (op) {
                case EQ:  return !currentStr.equals(compareStr);
                case NEQ: return currentStr.equals(compareStr);
                default:  return false;
            }
        }
    }

    // ==========================================
    // 范围规则
    // ==========================================

    /**
     * RANGE min TO max
     * 当前字段的数值必须在 [min, max] 范围内
     */
    private static boolean checkRange(RuleItem rule, Object currentValue) {
        if (isAbsent(currentValue)) return false;
        try {
            double val = Double.parseDouble(currentValue.toString());
            Double min = rule.getRangeMin();
            Double max = rule.getRangeMax();
            if (min != null && val < min) return true; // 小于最小值→ 违规
            if (max != null && val > max) return true; // 大于最大值 → 违规
            return false;
        } catch (NumberFormatException e) {
            log.warn("RANGE校验值非数值: {}", currentValue);
            return true; // 非数值视为违规
        }
    }

    /**
     * NUMERIC_RANGE：数值范围校验
     * 对应CSV值范围列格式：min=0.0; max=999999
     *覆盖所有出现的组合：
     *   min=0.0; max=999999
     *   min=0.0; max=99999
     *   min=0.0; max=9999
     *   min=0.0; max=999
     *   min=0.0; max=99
     *   min=0; max=999999999
     *   min=0.0; max=9999.99
     *   min=0.0; max=9999.99999
     *   min=0.0; max=9999999.999
     *   min=0.0; max=999999.999999
     */
    private static boolean checkNumericRange(RuleItem rule, Object currentValue) {
        if (isAbsent(currentValue)) return false;
        try {
            double val = Double.parseDouble(currentValue.toString());
            Double min = rule.getRangeMin();
            Double max = rule.getRangeMax();
            if (min != null && val < min) return true; // 小于最小值 → 违规
            if (max != null && val > max) return true; // 大于最大值 → 违规
            return false;
        } catch (NumberFormatException e) {
            log.warn("NUMERIC_RANGE校验值非数值: {}", currentValue);
            return true; // 非数值视为违规
        }
    }

    /**
     * LENGTH_RANGE：字符串长度范围校验（minLength + maxLength 同时存在）
     * 对应CSV值范围列格式：minLength=1; maxLength=35
     * 覆盖：minLength=1; maxLength=35 / minLength=1; maxLength=17/ minLength=1; maxLength=50
     */
    private static boolean checkLengthRange(RuleItem rule, Object currentValue) {
        if (currentValue == null) return false;
        int len = currentValue.toString().length();
        Integer minLen = rule.getMinLength();
        Integer maxLen = rule.getMaxLength();
        if (minLen != null && len < minLen) return true; // 小于最小长度 → 违规
        if (maxLen != null && len > maxLen) return true; // 超过最大长度 → 违规
        return false;
    }

    /**
     * MAX_LENGTH：仅最大长度校验
     * 对应CSV值范围列格式：maxLength=n
     * 覆盖：maxLength=2 / maxLength=3/ maxLength=4/ maxLength=9
     *maxLength=17 / maxLength=35 / maxLength=50
     */
    private static boolean checkMaxLength(RuleItem rule, Object currentValue) {
        if (currentValue == null) return false;
        int len = currentValue.toString().length();
        Integer maxLen = rule.getMaxLength();
        if (maxLen == null) return false;
        return len > maxLen; // 超过最大长度 → 违规
    }

    /**
     * MIN_LENGTH：仅最小长度校验
     * 对应CSV值范围列格式：minLength=n
     */
    private static boolean checkMinLength(RuleItem rule, Object currentValue) {
        if (currentValue == null) return false;
        int len = currentValue.toString().length();
        Integer minLen = rule.getMinLength();
        if (minLen == null) return false;
        return len < minLen; // 小于最小长度 → 违规
    }

    /**
     * TOTAL_DIGITS：总有效数字位数校验
     * 对应CSV值范围列格式：totalDigits=n
     * 覆盖：totalDigits=3 / totalDigits=4 / totalDigits=5
     *        totalDigits=6 / totalDigits=9/ totalDigits=10/ totalDigits=12
     *
     * 计算规则：去掉小数点、负号后的数字总个数，去掉前导零
     * 示例：
     *   9999.99  → "999999"→去前导0→ 6位✓（totalDigits=6）
     *   0.12345  → "012345"  → 去前导0 → "12345" → 5位 ✓（totalDigits=5）
     *   10000.0  → "100000"  → 6位 ✓
     */
    private static boolean checkTotalDigits(RuleItem rule, Object currentValue) {
        if (isAbsent(currentValue)) return false;
        Integer maxDigits = rule.getTotalDigits();
        if (maxDigits == null) return false;

        // 去掉负号和小数点，提取纯数字
        String digitsOnly = currentValue.toString().replace("-", "").replace(".", "");
        // 去掉前导零（0.123 → 012300→ 1230 → 4位；但"0" 本身保留）
        String significant = digitsOnly.replaceFirst("^0+(?!$)", "");

        if (significant.length() > maxDigits) {
            log.warn("TOTAL_DIGITS校验不通过: 字段值[{}]有效数字位数[{}]超过限制[{}]",
                    currentValue, significant.length(), maxDigits);
            return true; // 超过有效位数 → 违规
        }
        return false;
    }

    /**
     * FRACTION_DIGITS：小数位数校验
     * 对应CSV值范围列格式：fractionDigits=n
     * 覆盖：fractionDigits=2 / fractionDigits=3 / fractionDigits=5/ fractionDigits=6
     *
     * 计算规则：小数点后的数字位数，去掉末尾多余的0
     * 示例：
     *   9999.10000 → 去末尾0 → "1"→ 1位 ✓（fractionDigits=2）
     *   0.99999    → "99999"           → 5位 ✓（fractionDigits=5）
     *   1.0000001→ "0000001"         → 7位 ✗（fractionDigits=6）
     */
    private static boolean checkFractionDigits(RuleItem rule, Object currentValue) {
        if (isAbsent(currentValue)) return false;
        Integer maxFraction = rule.getFractionDigits();
        if (maxFraction == null) return false;

        String strVal = currentValue.toString();
        int dotIndex = strVal.indexOf('.');

        // 无小数点，小数位数为0，直接通过
        if (dotIndex< 0) return false;

        // 截取小数部分并去掉末尾0
        String fractPart = strVal.substring(dotIndex + 1).replaceAll("0+$", "");

        if (fractPart.length() > maxFraction) {
            log.warn("FRACTION_DIGITS校验不通过: 字段值[{}]小数位数[{}]超过限制[{}]",
                    currentValue, fractPart.length(), maxFraction);
            return true; // 超过小数位数 → 违规
        }
        return false;
    }

    // ==========================================
    // 条件规则
    // ==========================================

    /**
     * FORBIDDEN IF 条件
     * 条件满足时，当前字段必须为空
     */
    private static boolean checkForbiddenIf(
            RuleItem rule, Object currentValue, Map<String, Object> contextFields) {
        String conditionStr = (String) rule.getCompareValue();
        if (conditionStr == null) return false;
        boolean conditionMet = evaluateConditionExpression(conditionStr, contextFields);
        if (conditionMet) {
            return !isAbsent(currentValue); // 条件满足但当前字段有值 → 违规
        }
        return false;
    }

    /**
     * MANDATORY IF 条件
     * 条件满足时，当前字段必须有值
     */
    private static boolean checkMandatoryIf(
            RuleItem rule, Object currentValue, Map<String, Object> contextFields) {
        String conditionStr = (String) rule.getCompareValue();
        if (conditionStr == null) return false;
        boolean conditionMet = evaluateConditionExpression(conditionStr, contextFields);
        if (conditionMet) {
            return isAbsent(currentValue); // 条件满足但当前字段为空 → 违规
        }
        return false;
    }

    /**
     * fieldName IS NULL / IS NOT NULL
     * 校验指定字段是否为NULL
     */
    private static boolean checkFieldIsNull(
            RuleItem rule, Map<String, Object> contextFields) {
        String targetField = rule.getTargetFieldName();
        if (targetField == null) return false;
        Object targetValue = contextFields.get(targetField);
        boolean isNull = (targetValue == null);
        // compareValue: true → 期望IS NULL，false → 期望IS NOT NULL
        boolean expectNull = Boolean.TRUE.equals(rule.getCompareValue());
        return expectNull != isNull;
    }

    /**
     * fieldName IS ABSENT / IS PRESENT
     * 校验指定字段是否存在（有值）
     */
    private static boolean checkFieldIsAbsent(
            RuleItem rule, Map<String, Object> contextFields) {
        String targetField = rule.getTargetFieldName();
        if (targetField == null) return false;
        Object targetValue = contextFields.get(targetField);
        boolean isAbsent = isAbsent(targetValue);
        String expectState = (String) rule.getCompareValue(); // "ABSENT" or "PRESENT"
        if ("ABSENT".equals(expectState)) {
            return !isAbsent; // 期望ABSENT但有值 → 违规
        } else {
            return isAbsent; // 期望PRESENT但为空 → 违规
        }
    }

    // ==========================================
    // 列表规则
    // ==========================================

    /**
     * ANY fieldName = value / ANY fieldName IS PRESENT 等
     * 列表中至少有一条记录满足条件
     * 同时支持：VALUE IS PRESENT IF ANY fieldName IS PRESENT
     */
    @SuppressWarnings("unchecked")
    private static boolean checkAnyCondition(
            RuleItem rule,
            Object currentValue,
            Map<String, Object> contextFields,
            Map<String, List<Map<String, Object>>> listFields) {

        ConditionExpression cond = (ConditionExpression) rule.getCompareValue();
        if (cond == null) return false;

        String fieldName = cond.getFieldName();
        String operator = cond.getOperator();
        Object expectedValue = cond.getValue();

        boolean anyMatch = false;

        //遍历所有列表字段
        for (List<Map<String, Object>> list : listFields.values()) {
            if (list == null) continue;
            for (Map<String, Object> item : list) {
                Object actualValue = item.get(fieldName);
                boolean match = evaluateSingleOperator(
                        actualValue,
                        operator,
                        expectedValue != null ? expectedValue.toString() : null);
                if (match) {
                    anyMatch = true;
                    break;
                }
            }
            if (anyMatch) break;
        }

        // 如果是VALUE IS PRESENT IF ANY ... 语义
        // anyMatch=true → 当前字段必须有值
        if ("VALUE_IS_PRESENT_IF_ANY".equals(rule.getRuleId()) || anyMatch) {
            if (anyMatch) {
                return isAbsent(currentValue);
            }
        }

        // 普通 ANY 校验：必须有任意一条满足
        return !anyMatch;
    }

    /**
     * ALL fieldName IS ABSENT/PRESENT 等
     * 列表中所有记录都必须满足条件
     */
    @SuppressWarnings("unchecked")
    private static boolean checkAllCondition(
            RuleItem rule, Map<String, List<Map<String, Object>>> listFields) {

        ConditionExpression cond = (ConditionExpression) rule.getCompareValue();
        if (cond == null) return false;

        String fieldName = cond.getFieldName();
        String operator = cond.getOperator();
        Object expectedValue = cond.getValue();

        boolean hasItems = false;

        for (List<Map<String, Object>> list : listFields.values()) {
            if (list == null || list.isEmpty()) continue;
            for (Map<String, Object> item : list) {
                hasItems = true;
                Object actualValue = item.get(fieldName);
                boolean match = evaluateSingleOperator(
                        actualValue,
                        operator,
                        expectedValue != null ? expectedValue.toString() : null);
                if (!match) {
                    return true; // 有一条不满足 → 违规
                }
            }
        }

        // 列表为空时，视为条件满足（不违规）
        return false;
    }

    // ==========================================
    // 聚合规则
    // ==========================================

    /**
     * COUNT(listField, condition) 运算符 VALUE
     * 统计列表中满足条件的元素个数，与当前字段值比较
     */
    @SuppressWarnings("unchecked")
    private static boolean checkCountAggregate(
            RuleItem rule,
            Object currentValue,
            Map<String, List<Map<String, Object>>> listFields) {

        AggregateFunction aggFunc = (AggregateFunction) rule.getCompareValue();
        if (aggFunc == null) return false;

        String listFieldName = aggFunc.getListFieldName();
        ConditionExpression filterCond = aggFunc.getFilterCondition();
        String operator = aggFunc.getCompareOperator();

        // 获取对应的列表
        List<Map<String, Object>> list = listFields.get(listFieldName);
        if (list == null || list.isEmpty()) {
            // 空列表 count=0，与VALUE比较
            return compareAggregateResult(0, operator, currentValue);
        }

        // 统计满足条件的数量
        int count = 0;
        for (Map<String, Object> item : list) {
            if (item == null) continue;
            if (filterCond == null) {
                count++; // 无过滤条件，全部计数
                continue;
            }
            Object actualValue = item.get(filterCond.getFieldName());
            boolean match = evaluateSingleOperator(
                    actualValue,
                    filterCond.getOperator(),
                    filterCond.getValue() != null ? filterCond.getValue().toString() : null);
            if (match) count++;
        }

        return compareAggregateResult(count, operator, currentValue);
    }

    /**
     * SUM(listField, fieldName) 运算符 VALUE
     * 对列表中指定字段求和，与当前字段值比较
     */
    @SuppressWarnings("unchecked")
    private static boolean checkSumAggregate(
            RuleItem rule,
            Object currentValue,
            Map<String, List<Map<String, Object>>> listFields) {

        AggregateFunction aggFunc = (AggregateFunction) rule.getCompareValue();
        if (aggFunc == null) return false;

        String listFieldName = aggFunc.getListFieldName();
        String targetFieldName = aggFunc.getTargetFieldName();
        String operator = aggFunc.getCompareOperator();

        // 获取对应的列表
        List<Map<String, Object>> list = listFields.get(listFieldName);
        if (list == null || list.isEmpty()) {
            return compareAggregateResult(0.0, operator, currentValue);
        }

        // 求和
        double sum = 0.0;
        for (Map<String, Object> item : list) {
            if (item == null) continue;
            Object value = item.get(targetFieldName);
            if (isAbsent(value)) continue;
            try {
                sum += Double.parseDouble(value.toString());
            } catch (NumberFormatException e) {
                log.warn("SUM聚合：字段 {} 值 {} 非数值，跳过", targetFieldName, value);
            }
        }

        return compareAggregateResult(sum, operator, currentValue);
    }

    // ==========================================
    // 条件表达式评估器
    // ==========================================

    /**
     * 评估条件表达式字符串
     * 支持: AND、OR、IN [...]、=、!=、<=、>=、<、>
     * 例如: vehicleCategory IN [Mx, Nx] AND stageOfCompletion = C
     */
    private static boolean evaluateConditionExpression(
            String conditionStr, Map<String, Object> contextFields) {
        if (conditionStr == null || conditionStr.trim().isEmpty()) return false;

        conditionStr = conditionStr.trim();

        // 处理 OR（优先级低于AND，先拆OR）
        if (containsLogicKeyword(conditionStr, "OR")) {
            String[] parts = splitByLogicKeyword(conditionStr, "OR");
            for (String part : parts) {
                if (evaluateConditionExpression(part.trim(), contextFields)) {
                    return true;
                }
            }
            return false;
        }

        // 处理 AND
        if (containsLogicKeyword(conditionStr, "AND")) {
            String[] parts = splitByLogicKeyword(conditionStr, "AND");
            for (String part : parts) {
                if (!evaluateConditionExpression(part.trim(), contextFields)) {
                    return false;
                }
            }
            return true;
        }

        // 处理 IN [...] 条件
        // 例如: vehicleCategory IN [Mx, Nx, Ox]
        if (conditionStr.toUpperCase().contains(" IN [")) {
            return evaluateInCondition(conditionStr, contextFields);
        }

        // 处理 IS ABSENT / IS PRESENT
        if (conditionStr.toUpperCase().contains(" IS ABSENT")) {
            String fieldName = conditionStr.substring(0, conditionStr.toUpperCase().indexOf(" IS ABSENT")).trim();
            return isAbsent(contextFields.get(fieldName));
        }
        if (conditionStr.toUpperCase().contains(" IS PRESENT")) {
            String fieldName = conditionStr.substring(0, conditionStr.toUpperCase().indexOf(" IS PRESENT")).trim();
            return !isAbsent(contextFields.get(fieldName));
        }

        // 处理 IS NULL / IS NOT NULL
        if (conditionStr.toUpperCase().contains(" IS NOT NULL")) {
            String fieldName = conditionStr.substring(0, conditionStr.toUpperCase().indexOf(" IS NOT NULL")).trim();
            return contextFields.get(fieldName) != null;
        }
        if (conditionStr.toUpperCase().contains(" IS NULL")) {
            String fieldName = conditionStr.substring(0, conditionStr.toUpperCase().indexOf(" IS NULL")).trim();
            return contextFields.get(fieldName) == null;
        }

        // 处理比较运算符（按长度优先匹配，避免 <= 被< 截断）
        for (String op : new String[]{"!=", "<=", ">=", "<", ">", "="}) {
            int idx = conditionStr.indexOf(op);
            if (idx > 0) {
                String fieldName = conditionStr.substring(0, idx).trim();
                String compareValue = conditionStr.substring(idx + op.length()).trim()
                        .replaceAll("^['\"]|['\"]$", ""); // 去引号
                Object fieldValue = contextFields.get(fieldName);
                if (fieldValue == null) return false;
                return evaluateSingleOperator(fieldValue, op, compareValue);
            }
        }

        log.warn("无法解析条件表达式: {}", conditionStr);
        return false;
    }

    /**
     * 评估 IN 条件
     * 例如: vehicleCategory IN [Mx, Nx, Ox]
     * 支持前缀匹配（Mx 匹配 M1、M2、M3 等）
     */
    private static boolean evaluateInCondition(
            String conditionStr, Map<String, Object> contextFields) {
        try {
            // 提取字段名
            int inIdx = conditionStr.toUpperCase().indexOf(" IN [");
            String fieldName = conditionStr.substring(0, inIdx).trim();

            // 提取列表内容
            int listStart = conditionStr.indexOf('[');
            int listEnd = conditionStr.indexOf(']');
            if (listStart < 0 || listEnd < 0) return false;

            String listContent = conditionStr.substring(listStart + 1, listEnd);
            String[] allowedValues = listContent.split(",");

            // 获取字段实际值
            Object fieldValue = contextFields.get(fieldName);
            if (fieldValue == null) return false;
            String fieldValueStr = fieldValue.toString().trim();

            // 逐一比较（支持前缀通配符，如 Mx 匹配 M1、M2）
            for (String allowed : allowedValues) {
                String allowedTrimmed = allowed.trim()
                        .replaceAll("^['\"]|['\"]$", ""); // 去引号
                if (allowedTrimmed.endsWith("x")) {
                    // 前缀匹配
                    String prefix = allowedTrimmed.substring(0, allowedTrimmed.length() - 1);
                    if (fieldValueStr.startsWith(prefix)) {
                        return true;
                    }
                } else {
                    if (fieldValueStr.equalsIgnoreCase(allowedTrimmed)) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            log.error("IN条件解析异常: {}", conditionStr, e);
        }
        return false;
    }

    // ==========================================
    // 工具方法
    // ==========================================

    /**
     * 聚合结果与当前字段值比较
     */
    private static boolean compareAggregateResult(
            double aggregateResult, String operator, Object currentValue) {
        if (isAbsent(currentValue)) return false;
        try {
            double value = Double.parseDouble(currentValue.toString());
            switch (operator) {
                case "=":  return Math.abs(aggregateResult - value) > 1e-9;
                case "!=": return Math.abs(aggregateResult - value) <= 1e-9;
                case ">":  return aggregateResult <= value;
                case "<":  return aggregateResult >= value;
                case ">=": return aggregateResult < value;
                case "<=": return aggregateResult > value;
                default:
                    log.warn("未知聚合比较运算符: {}", operator);
                    return false;
            }
        } catch (NumberFormatException e) {
            log.warn("聚合比较：当前值非数值: {}", currentValue);
            return true;
        }
    }

    /**
     * 数值比较（用于运算符评估）
     * 返回:负数=小于, 0=等于, 正数=大于
     */
    private static int compareNumeric(Object actual, String expected) {
        try {
            double a = Double.parseDouble(actual.toString());
            double b = Double.parseDouble(expected);
            return Double.compare(a, b);
        } catch (NumberFormatException e) {
            return actual.toString().compareTo(expected);
        }
    }

    /**
     * 判断条件字符串中是否包含逻辑关键字（AND/OR）
     * 注意：需排除在引号内或括号内的关键字
     */
    private static boolean containsLogicKeyword(String expr, String keyword) {
        // 简单实现：直接判断是否包含 " AND " 或 " OR "
        return expr.toUpperCase().contains(" " + keyword + " ");
    }

    /**
     * 按逻辑关键字拆分条件字符串
     */
    private static String[] splitByLogicKeyword(String expr, String keyword) {
        return expr.split("(?i)\\s+" + keyword + "\\s+");
    }

    /**
     * 判断值是否为空（null或 空字符串）
     */
    private static boolean isAbsent(Object value) {
        if (value == null) return true;
        if (value instanceof String) {
            return ((String) value).trim().isEmpty();
        }
        return false;
    }

    /**
     * 构建违规信息
     */
    private static RuleViolation buildViolation(RuleItem rule) {
        return RuleViolation.builder()
                .ruleId(rule.getRuleId())
                .messageEn(rule.getErrorMessageEn() != null
                        ? rule.getErrorMessageEn()
                        : "Rule violated: " + rule.getRawRule())
                .messageZh(rule.getErrorMessageZh() != null
                        ? rule.getErrorMessageZh()
                        : "校验未通过: " + rule.getRawRule())
                .rawRule(rule.getRawRule())
                .build();
    }
}