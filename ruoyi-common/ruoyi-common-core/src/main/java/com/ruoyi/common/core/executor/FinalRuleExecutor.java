package com.ruoyi.common.core.executor;

import com.ruoyi.common.core.enums.CompareOperator;
import com.ruoyi.common.core.model.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 规则执行器
 * 负责对单个字段执行所有 RuleItem 校验，返回 FieldValidationResult
 */
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
            String strVal;
            switch (rule.getType()) {
                case NULL: return null;
                // 规则解析失败时直接转化为校验违规，消息已在解析阶段写好
                case PARSE_ERROR:
                    return buildViolation(rule, fieldName, actualValue,
                            rule.getErrorMessageEn(),
                            rule.getErrorMessageZh());

                case VALUE_IS_PRESENT:
                    return buildViolation(rule, fieldName, actualValue, "Field is required", "必填字段不能为空");

                case VALUE_IS_ABSENT:
                    return buildViolation(rule, fieldName, actualValue, "Field must be absent", "该字段在此场景下必须为空");

                case VALUE_IN:
                    strVal = String.valueOf(actualValue);
                    if (rule.getEnumValues() == null || !rule.getEnumValues().contains(strVal)) {
                        return buildViolation(rule, fieldName, actualValue,
                                "Value not in allowed list: " + rule.getEnumValues(), "值不在允许的枚举列表中: " + rule.getEnumValues());
                    }

                case VALUE_REGEX:
                    strVal = String.valueOf(actualValue);
                    if (!Pattern.matches(rule.getRegexPattern(), strVal)) {
                        return buildViolation(rule, fieldName, actualValue,
                                "Value does not match pattern: " + rule.getRegexPattern(), "值不符合正则格式: " + rule.getRegexPattern());
                    }

                case VALUE_COMPARE:
                    if (!compareValue(actualValue, rule.getCompareValue(), rule.getOperator())) {
                        return buildViolation(rule, fieldName, actualValue,
                                "Value compare failed: " + rule.getOperator() + " " + rule.getCompareValue(),
                                "数值比较不通过: " + rule.getOperator() + " " + rule.getCompareValue());
                    }

                case MANDATORY_IF_ANY:
                    return checkMandatoryIfAny(fieldName, actualValue, rule, context);

                case MANDATORY_IF_ALL:
                    return checkMandatoryIfAll(fieldName, actualValue, rule, context);

                case MANDATORY_IF:
                    if (rule.getRefFieldName() != null) {
                        Object refValue = context.get(rule.getRefFieldName());
                        boolean refAbsent = isAbsent(refValue);
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

                case VALUE_COUNT_REF:
                    return checkValueCountRef(fieldName, actualValue, rule, context);

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
                    // 未知规则类型：同样封装为报告，不打 log
                    return buildViolation(rule, fieldName, actualValue,
                            "Unknown rule type: " + rule.getType(),
                            "未知规则类型: " + rule.getType());
            }
        } catch (Exception e) {
            // 执行期异常：封装为报告，不打 log
            return buildViolation(rule, fieldName, actualValue,
                    "Rule execution error: " + e.getMessage() + " [raw=" + rule.getRawRule() + "]",
                    "规则执行异常: " + e.getMessage() + " [原始规则=" + rule.getRawRule() + "]");
        }

        return null;
    }

    // ==========================================
    // 条件必填 / 条件禁填
    // ==========================================

    private static RuleViolation checkMandatoryIfAny(String fieldName, Object actualValue, RuleItem rule, Map<String, Object> context) {
        ConditionChain chain = rule.getConditionChain();
        if (chain == null) return null;
        if (chain.evaluate(context) && isAbsent(actualValue)) {
            return buildViolation(rule, fieldName, actualValue,
                    "Field is required when any condition is met",
                    "任一条件满足时该字段为必填");
        }
        return null;
    }

    private static RuleViolation checkMandatoryIfAll(String fieldName, Object actualValue, RuleItem rule, Map<String, Object> context) {
        ConditionChain chain = rule.getConditionChain();
        if (chain == null) return null;
        if (chain.evaluate(context) && isAbsent(actualValue)) {
            return buildViolation(rule, fieldName, actualValue,
                    "Field is required when all conditions are met",
                    "所有条件满足时该字段为必填");
        }
        return null;
    }

    private static RuleViolation checkForbiddenIfAll(String fieldName, Object actualValue, RuleItem rule, Map<String, Object> context) {
        ConditionChain chain = rule.getConditionChain();
        if (chain == null) return null;
        if (chain.evaluate(context) && !isAbsent(actualValue)) {
            return buildViolation(rule, fieldName, actualValue,
                    "Field must be absent when all conditions are met",
                    "所有条件满足时该字段必须为空");
        }
        return null;
    }

    private static RuleViolation checkForbiddenIfAny(String fieldName, Object actualValue, RuleItem rule, Map<String, Object> context) {
        if (rule.getConditionChain() == null) {
            return null;
        }
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

    private static RuleViolation checkValueCountRef(String fieldName, Object actualValue, RuleItem rule, Map<String, Object> context) {
        String refField = rule.getRefFieldName();
        if (refField == null) {
            return buildViolation(rule, fieldName, actualValue,
                    "refFieldName is null",
                    "规则缺少引用字段名");
        }

        Object refObj = context.get(refField);
        int refCount;
        if (refObj instanceof List) {
            refCount = ((List<?>) refObj).size();
        } else if (refObj == null) {
            refCount = 0;
        } else {
            // 非 List 类型：视为单个元素，计数为 1
            refCount = 1;
        }

        double actualNum;
        try {
            actualNum = toDouble(actualValue);
        } catch (Exception e) {
            return buildViolation(rule, fieldName, actualValue,
                    "VALUE = COUNT(@" + refField + ") check failed: current value is not numeric",
                    "当前字段值无法转换为数字，无法与 COUNT(@" + refField + ") 比较");
        }

        if ((int) actualNum != refCount) {
            return buildViolation(rule, fieldName, actualValue,
                    "Value " + (int) actualNum + " != COUNT(@" + refField + ") which is " + refCount,
                    "字段值 " + (int) actualNum + " 与 @" + refField + " 的列表数量 " + refCount + " 不一致");
        }
        return null;
    }

    private static RuleViolation checkCountAggregate(String fieldName, Object actualValue, RuleItem rule, Map<String, Object> context) {
        AggregateFunction af = rule.getAggregateFunction();
        Object listObj = context.get(af.getListField());
        if (!(listObj instanceof List)) return null;

        List<?> list = (List<?>) listObj;
        ConditionExpression condExpr = ConditionExpression.parse(af.getCondition());
        long count = list.stream()
                .filter(item -> {
                    if (!(item instanceof Map)) return false;
                    @SuppressWarnings("unchecked")
                    Map<String, Object> itemMap = (Map<String, Object>) item;
                    if (condExpr == null) return true;
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

    private static RuleViolation checkSumAggregate(String fieldName, Object actualValue, RuleItem rule, Map<String, Object> context) {
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

    private static RuleViolation checkNestedCondition(String fieldName, Object actualValue, RuleItem rule, Map<String, Object> context) {
        NestedConditionRule nested = rule.getNestedCondition();
        if (nested == null) return null;

        boolean anyMet = nested.getAnyChain() == null || nested.getAnyChain().evaluate(context);
        boolean allMet = nested.getAllChain() == null || nested.getAllChain().evaluate(context);

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
                String strVal = String.valueOf(actualValue);
                if (!Pattern.matches(nested.getCompareValue(), strVal)) {
                    return buildViolation(rule, fieldName, actualValue,
                            "Value does not match pattern (nested): " + nested.getCompareValue(),
                            "嵌套条件满足时值不符合正则格式: " + nested.getCompareValue());
                }
                break;
            default:
                // 未知嵌套操作符：封装为报告，不打 log
                return buildViolation(rule, fieldName, actualValue,
                        "Unknown nested condition operator: " + nested.getOperator(),
                        "嵌套条件未知操作符: " + nested.getOperator());
        }
        return null;
    }

    // ==========================================
    // 范围校验
    // ==========================================

    private static RuleViolation checkNumericRange(String fieldName, Object actualValue, RuleItem rule) {
        try {
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
                    "Value cannot be parsed as a number",
                    "数值范围校验时值无法转换为数字");
        }
    }

    private static RuleViolation checkLengthRange(String fieldName, Object actualValue, RuleItem rule) {
        int len = String.valueOf(actualValue).length();
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

    private static RuleViolation checkTotalDigits(String fieldName, Object actualValue, RuleItem rule) {
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
            return buildViolation(rule, fieldName, actualValue,
                    "Value cannot be parsed as a number for totalDigits check",
                    "totalDigits 校验时值无法转换为数字");
        }
        return null;
    }

    private static RuleViolation checkFractionDigits(String fieldName, Object actualValue, RuleItem rule) {
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
            return buildViolation(rule, fieldName, actualValue,
                    "Value cannot be parsed as a number for fractionDigits check",
                    "fractionDigits 校验时值无法转换为数字");
        }
        return null;
    }

    // ==========================================
    // 工具方法
    // ==========================================

    private static boolean compareValue(Object actual, String expected, String operator) {
        try {
            double actualD = toDouble(actual);
            double expectedD = Double.parseDouble(expected);
            return CompareOperator.fromSymbol(operator).apply(actualD, expectedD);
        } catch (NumberFormatException e) {
            return CompareOperator.fromSymbol(operator).applyString(String.valueOf(actual), expected);
        }
    }

    public static boolean isAbsent(Object value) {
        if (value == null) return true;
        if (value instanceof String) return ((String) value).trim().isEmpty();
        if (value instanceof Collection) return ((Collection<?>) value).isEmpty();
        if (value instanceof Map) return ((Map<?, ?>) value).isEmpty();
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