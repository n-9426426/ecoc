package com.ruoyi.common.core.executor;

import com.ruoyi.common.core.enums.CompareOperator;
import com.ruoyi.common.core.model.*;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 规则执行器
 * 负责对单个字段执行所有 RuleItem 校验，返回 FieldValidationResult
 */
@Slf4j
public class FinalRuleExecutor {

    /**
     * 执行字段校验
     *
     * @param fieldName   字段名
     * @param actualValue 字段实际值
     * @param rules       规则列表（由 FinalRuleParser 解析）
     * @param context     当前报文上下文（key=字段名, value=字段值）
     * @return FieldValidationResult
     */
    public static FieldValidationResult execute(
            String fieldName,
            Object actualValue,
            List<RuleItem> rules,
            Map<String, Object> context) {

        List<RuleViolation> violations = new ArrayList<>();

        for (RuleItem rule : rules) {
            RuleViolation violation = checkRule(fieldName, actualValue, rule, context);
            if (violation != null) {
                violations.add(violation);
            }
        }

        return FieldValidationResult.builder()
                .fieldName(fieldName)
                .value(actualValue)
                .valid(violations.isEmpty())
                .violations(violations)
                .build();
    }

    // ==========================================
    // 核心分发逻辑
    // ==========================================

    private static RuleViolation checkRule(
            String fieldName,
            Object actualValue,
            RuleItem rule,
            Map<String, Object> context) {

        try {
            switch (rule.getType()) {
                case NULL: return null;
                case VALUE_IS_PRESENT:
                    if (isAbsent(actualValue)) {
                        return buildViolation(rule, fieldName, actualValue,
                                "Field is required", "必填字段不能为空");
                    }
                    break;

                case VALUE_IS_ABSENT:
                    if (!isAbsent(actualValue)) {
                        return buildViolation(rule, fieldName, actualValue,
                                "Field must be absent", "该字段在此场景下必须为空");
                    }
                    break;

                case VALUE_IN:
                    if (!isAbsent(actualValue)) {
                        String strVal = String.valueOf(actualValue);
                        if (rule.getEnumValues() == null || !rule.getEnumValues().contains(strVal)) {
                            return buildViolation(rule, fieldName, actualValue,
                                    "Value not in allowed list: " + rule.getEnumValues(),
                                    "值不在允许的枚举列表中: " + rule.getEnumValues());
                        }
                    }
                    break;

                case VALUE_REGEX:
                    if (!isAbsent(actualValue)) {
                        String strVal = String.valueOf(actualValue);
                        if (!Pattern.matches(rule.getRegexPattern(), strVal)) {
                            return buildViolation(rule, fieldName, actualValue,
                                    "Value does not match pattern: " + rule.getRegexPattern(),
                                    "值不符合正则格式: " + rule.getRegexPattern());
                        }
                    }
                    break;

                case VALUE_COMPARE:
                    if (!isAbsent(actualValue)) {
                        if (!compareValue(actualValue, rule.getCompareValue(), rule.getOperator())) {
                            return buildViolation(rule, fieldName, actualValue,
                                    "Value compare failed: " + rule.getOperator() + " " + rule.getCompareValue(),
                                    "数值比较不通过: " + rule.getOperator() + " " + rule.getCompareValue());
                        }
                    }
                    break;

                case MANDATORY_IF_ANY:
                    return checkMandatoryIfAny(fieldName, actualValue, rule, context);

                case MANDATORY_IF_ALL:
                    return checkMandatoryIfAll(fieldName, actualValue, rule, context);

                case MANDATORY_IF:
                    if (rule.getRefFieldName() != null) {
                        Object refValue = context.get(rule.getRefFieldName());
                        boolean refAbsent = isAbsent(refValue);

                        // PRESENT → 引用字段有值时条件成立；ABSENT → 引用字段为空时条件成立
                        boolean conditionMet = "PRESENT".equals(rule.getRefFieldCondition()) != refAbsent;

                        if (conditionMet && isAbsent(actualValue)) {
                            String state = "PRESENT".equals(rule.getRefFieldCondition()) ? "has value" : "is absent";
                            return buildViolation(rule, fieldName, actualValue,
                                    "Field is required because @" + rule.getRefFieldName() + " " + state,
                                    "@" + rule.getRefFieldName() + " " + ("PRESENT".equals(rule.getRefFieldCondition()) ? "有值" : "为空") + "，当前字段必须填写");
                        }
                        return null;
                    }
                    break;

                case FORBIDDEN_IF_ALL:
                    return checkForbiddenIfAll(fieldName, actualValue, rule, context);

                case FORBIDDEN_IF_ANY:
                    return checkForbiddenIfAny(fieldName, actualValue, rule, context);

                case FORBIDDEN_IF:
                    if (rule.getRefFieldName() != null) {
                        Object refValue = context.get(rule.getRefFieldName());
                        boolean refAbsent = isAbsent(refValue);

                        // 同样逻辑：判断引用字段是否满足条件
                        boolean conditionMet = "PRESENT".equals(rule.getRefFieldCondition()) != refAbsent;

                        if (conditionMet && !isAbsent(actualValue)) {
                            String state = "PRESENT".equals(rule.getRefFieldCondition()) ? "has value" : "is absent";
                            return buildViolation(rule, fieldName, actualValue,
                                    "Field must be absent because @" + rule.getRefFieldName() + " " + state,
                                    "@" + rule.getRefFieldName() + " " + ("PRESENT".equals(rule.getRefFieldCondition()) ? "有值" : "为空") + "，当前字段必须为空");
                        }
                        return null;
                    }
                    break;

                case COUNT_AGGREGATE:
                    return checkCountAggregate(fieldName, actualValue, rule, context);

                case SUM_AGGREGATE:
                    return checkSumAggregate(fieldName, actualValue, rule, context);

                case NESTED_CONDITION:
                    return checkNestedCondition(fieldName, actualValue, rule, context);

                case NUMERIC_RANGE:
                    return checkNumericRange(fieldName, actualValue, rule);

                case LENGTH_RANGE:
                case MIN_LENGTH:
                case MAX_LENGTH:
                    return checkLengthRange(fieldName, actualValue, rule);

                case TOTAL_DIGITS:
                    return checkTotalDigits(fieldName, actualValue, rule);

                case FRACTION_DIGITS:
                    return checkFractionDigits(fieldName, actualValue, rule);

                default:
                    log.warn("未处理的规则类型: {}", rule.getType());
            }
        } catch (Exception e) {
            log.error("规则执行异常, field={}, rule={}, error={}", fieldName, rule.getRawRule(), e.getMessage(), e);
        }

        return null;
    }

    // ==========================================
    // 条件必填 / 条件禁填
    // ==========================================

    private static RuleViolation checkMandatoryIfAny(String fieldName, Object actualValue, RuleItem rule, Map<String, Object> context) {
        ConditionChain chain = rule.getConditionChain();
        if (chain == null) return null;
        // ANY：任一条件满足即触发
        if (chain.evaluate(context) && isAbsent(actualValue)) {
            return buildViolation(rule, fieldName, actualValue,
                    "Field is required when any condition is met",
                    "任一条件满足时该字段为必填");
        }
        return null;
    }

    private static RuleViolation checkMandatoryIfAll(
            String fieldName, Object actualValue, RuleItem rule, Map<String, Object> context) {

        ConditionChain chain = rule.getConditionChain();
        if (chain == null) return null;
        // ALL：全部条件满足才触发
        if (chain.evaluate(context) && isAbsent(actualValue)) {
            return buildViolation(rule, fieldName, actualValue,
                    "Field is required when all conditions are met",
                    "所有条件满足时该字段为必填");
        }
        return null;
    }

    private static RuleViolation checkForbiddenIfAll(
            String fieldName, Object actualValue, RuleItem rule, Map<String, Object> context) {

        ConditionChain chain = rule.getConditionChain();
        if (chain == null) return null;
        if (chain.evaluate(context) && !isAbsent(actualValue)) {
            return buildViolation(rule, fieldName, actualValue,
                    "Field must be absent when all conditions are met",
                    "所有条件满足时该字段必须为空");
        }
        return null;
    }

    private static RuleViolation checkForbiddenIfAny(
            String fieldName, Object actualValue, RuleItem rule, Map<String, Object> context) {

        if (rule.getConditionChain() == null) {
            return null;
        }
        // 所有条件都满足时 → 当前字段必须为空
        if (rule.getConditionChain().evaluate(context)) {
            if (!isAbsent(actualValue)) {
                return buildViolation(rule, fieldName, actualValue,
                        "Field must be absent because ALL conditions are met",
                        "所有条件均满足，当前字段必须为空");
            }
        }
        return null;
    }

    // ==========================================
    // 聚合校验
    // ==========================================

    private static RuleViolation checkCountAggregate(
            String fieldName, Object actualValue, RuleItem rule, Map<String, Object> context) {

        AggregateFunction af = rule.getAggregateFunction();
        Object listObj = context.get(af.getListField());
        if (!(listObj instanceof List)) return null;

        List<?> list = (List<?>) listObj;
        ConditionExpression condExpr = ConditionExpression.parse(af.getCondition());
        long count = list.stream()
                .filter(item -> {
                    if (!(item instanceof Map)) return false;
                    Map<String, Object> itemMap = (Map<String, Object>) item;
                    if (condExpr == null) return true; // 无条件，全部计数
                    return condExpr.evaluate(itemMap);
                })
                .count();

        if (!af.getOperator().apply((double) count, af.getThreshold())) {
            return buildViolation(rule, fieldName, actualValue,
                    "COUNT(" + af.getListField() + ", " + af.getCondition() + ") "
                            + af.getOperator().getSymbol() + " " + af.getThreshold().intValue() + " failed",
                    "列表中满足条件的元素数量不符合要求");
        }
        return null;
    }

    private static RuleViolation checkSumAggregate(
            String fieldName, Object actualValue, RuleItem rule, Map<String, Object> context) {

        AggregateFunction af = rule.getAggregateFunction();
        Object listObj = context.get(af.getListField());
        if (!(listObj instanceof List)) return null;

        List<?> list = (List<?>) listObj;
        double sum = list.stream()
                .filter(item -> item instanceof Map)
                .mapToDouble(item -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> itemMap = (Map<String, Object>) item;
                    Object val = itemMap.get(af.getField());
                    return toDouble(val);
                })
                .sum();

        // threshold 为 null 时引用当前字段值
        double threshold = af.getThreshold() != null
                ? af.getThreshold()
                : toDouble(actualValue);

        if (!af.getOperator().apply(sum, threshold)) {
            return buildViolation(rule, fieldName, actualValue,
                    "SUM(" + af.getListField() + ", " + af.getField() + ") "
                            + af.getOperator().getSymbol() + " " + threshold + " failed",
                    "列表字段求和不符合要求");
        }
        return null;
    }

    // ==========================================
    // 嵌套条件校验
    // ==========================================

    private static RuleViolation checkNestedCondition(
            String fieldName, Object actualValue, RuleItem rule, Map<String, Object> context) {

        NestedConditionRule nested = rule.getNestedCondition();
        if (nested == null) return null;

        // IF ANY 条件链（任意满足）
        boolean anyMet = nested.getAnyChain() == null || nested.getAnyChain().evaluate(context);
        // IF ALL 条件链（全部满足）
        boolean allMet = nested.getAllChain() == null || nested.getAllChain().evaluate(context);

        // 两个条件链都满足时才执行主体规则
        if (!anyMet || !allMet) return null;

        switch (nested.getOperator()) {
            case "IS_PRESENT":
                if (isAbsent(actualValue)) {
                    return buildViolation(rule, fieldName, actualValue,
                            "Field is required (nested condition)",
                            "嵌套条件满足时该字段为必填");
                }
                break;
            case "IS_ABSENT":
                if (!isAbsent(actualValue)) {
                    return buildViolation(rule, fieldName, actualValue,
                            "Field must be absent (nested condition)",
                            "嵌套条件满足时该字段必须为空");
                }
                break;
            case "REGEX":
                if (!isAbsent(actualValue)) {
                    String strVal = String.valueOf(actualValue);
                    if (!Pattern.matches(nested.getCompareValue(), strVal)) {
                        return buildViolation(rule, fieldName, actualValue,
                                "Value does not match pattern (nested): " + nested.getCompareValue(),
                                "嵌套条件满足时值不符合正则格式: " + nested.getCompareValue());
                    }
                }
                break;
            default:
                log.warn("嵌套条件未知操作符: {}", nested.getOperator());
        }
        return null;
    }

    // ==========================================
    // 范围校验
    // ==========================================

    private static RuleViolation checkNumericRange(
            String fieldName, Object actualValue, RuleItem rule) {
        try {
            if (isAbsent(actualValue)) return null;
            double val = toDouble(actualValue);

            Double min = rule.getRangeMin();
            Double max = rule.getRangeMax();

            if (min != null && min.compareTo(val) > 0) {
                return buildViolation(rule, fieldName, actualValue,
                        "Value " + val + " is less than min " + min,
                        "值 " + val + " 小于最小值 " + min);
            }
            if (max != null && max.compareTo(val) < 0) {
                return buildViolation(rule, fieldName, actualValue,
                        "Value " + val + " exceeds max " + max,
                        "值 " + val + " 超过最大值 " + max);
            }
            return null;
        } catch (Exception e) {
            return buildViolation(rule, fieldName, actualValue,
                    "Value is not Value",
                    "totalDigits 校验时值无法转换为数字");
        }
    }

    private static RuleViolation checkLengthRange(
            String fieldName, Object actualValue, RuleItem rule) {

        if (isAbsent(actualValue)) return null;
        int len = String.valueOf(actualValue).length();

        // 从 rawRule 中取 minLength / maxLength（已存入 rangeMin/rangeMax）
        Integer minLen = rule.getMinLength();
        Integer maxLen = rule.getMaxLength();

        if (minLen != null && minLen.compareTo(len) > 0) {
            return buildViolation(rule, fieldName, actualValue,
                    "Length " + len + " is less than minLength " + minLen,
                    "字符串长度 " + len + " 小于最小长度 " + minLen);
        }
        if (maxLen != null && maxLen.compareTo(len) < 0) {
            return buildViolation(rule, fieldName, actualValue,
                    "Length " + len + " exceeds maxLength " + maxLen,
                    "字符串长度 " + len + " 超过最大长度 " + maxLen);
        }
        return null;
    }

    private static RuleViolation checkTotalDigits(
            String fieldName, Object actualValue, RuleItem rule) {

        if (isAbsent(actualValue)) return null;
        try {
            BigDecimal bd = new BigDecimal(String.valueOf(actualValue)).stripTrailingZeros();
            int totalDigits = bd.precision();
            int maxDigits = rule.getTotalDigits() != null ? rule.getTotalDigits() : Integer.MAX_VALUE;
            if (totalDigits > maxDigits) {
                return buildViolation(rule, fieldName, actualValue,
                        "Total digits " + totalDigits + " exceeds " + maxDigits,
                        "有效数字位数 " + totalDigits + " 超过限制 " + maxDigits);
            }
        } catch (NumberFormatException e) {
            log.warn("totalDigits 校验时值无法转换为数字: {}", actualValue);
            return buildViolation(rule, fieldName, actualValue,
                    "Value is not Value",
                    "totalDigits 校验时值无法转换为数字");
        }
        return null;
    }

    private static RuleViolation checkFractionDigits(
            String fieldName, Object actualValue, RuleItem rule) {

        if (isAbsent(actualValue)) return null;
        try {
            BigDecimal bd = new BigDecimal(String.valueOf(actualValue));
            int scale = Math.max(bd.scale(), 0);
            int maxScale = rule.getFractionDigits() != null ? rule.getFractionDigits() : Integer.MAX_VALUE;
            if (scale > maxScale) {
                return buildViolation(rule, fieldName, actualValue,
                        "Fraction digits " + scale + " exceeds " + maxScale,
                        "小数位数 " + scale + " 超过限制 " + maxScale);
            }
        } catch (NumberFormatException e) {
            log.warn("fractionDigits 校验时值无法转换为数字: {}", actualValue);
            return buildViolation(rule, fieldName, actualValue,
                    "Value is not Value",
                    "totalDigits 校验时值无法转换为数字");
        }
        return null;
    }

    // ==========================================
    // 工具方法
    // ==========================================

    /**
     * 数值比较（支持字符串和数字类型）
     */
    private static boolean compareValue(Object actual, String expected, String operator) {
        try {
            double actualD = toDouble(actual);
            double expectedD = Double.parseDouble(expected);
            return CompareOperator.fromSymbol(operator).apply(actualD, expectedD);
        } catch (NumberFormatException e) {
            // 降级为字符串比较（仅支持 = 和 !=）
            return CompareOperator.fromSymbol(operator)
                    .applyString(String.valueOf(actual), expected);
        }
    }

    public static boolean isAbsent(Object value) {
//        if (value == null) return true;
//        if (value instanceof String) return ((String) value).trim().isEmpty();
//        if (value instanceof Collection) return ((Collection<?>) value).isEmpty();
//        if (value instanceof Map) return ((Map<?, ?>) value).isEmpty();
        return false;
    }

    private static double toDouble(Object value) {
        if (value == null) return 0.0;
        if (value instanceof Number) return ((Number) value).doubleValue();
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private static RuleViolation buildViolation(
            RuleItem rule, String fieldName, Object actualValue,
            String messageEn, String messageZh) {

        return RuleViolation.builder()
                .ruleId(rule.getRuleId())
                .fieldName(fieldName)
                .actualValue(actualValue == null ? null : String.valueOf(actualValue))
                .messageEn(rule.getErrorMessageEn() != null ? rule.getErrorMessageEn() : messageEn)
                .messageZh(rule.getErrorMessageZh() != null ? rule.getErrorMessageZh() : messageZh)
                .rawRule(rule.getRawRule())
                .ruleType(rule.getType())
                .build();
    }
}