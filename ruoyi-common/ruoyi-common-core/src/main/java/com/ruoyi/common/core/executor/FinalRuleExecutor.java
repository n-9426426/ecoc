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

                case SUM_AGGREGATE:
                    return checkSumAggregate(fieldName, actualValue, rule, context);

                case NESTED_CONDITION:
                    return checkNestedCondition(fieldName, actualValue, rule, context);

                case VALUE_IS_NUMBERED:
                    return checkValueIsNumbered(fieldName, actualValue, rule, context);

                case VALUE_FIELD_COMPARE:
                    return checkValueFieldCompare(fieldName, actualValue, rule, context);

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
    // 列表连续编号校验
    // ==========================================

    /**
     * VALUE_IS_NUMBERED：校验列表中当前字段的值必须从 1 开始连续编号（1, 2, 3 … N），不允许重复或跳号。
     *
     * <p>上下文约定：context 中以 listFieldName 为 key 存放 {@code List<Map<String,Object>>}，
     * 每个 Map 对应列表中的一行，其中以校验字段名（fieldName）为 key 存放该行的序号值。
     *
     * <p>示例规则：{@code ManufacturerTable=>VALUE IS NUMBERED}
     * 对应 context key = "ManufacturerTable"，每行 Map 包含 "ManufacturerStageNumber" 字段。
     */
    private static RuleViolation checkValueIsNumbered(
            String fieldName, Object actualValue, RuleItem rule, Map<String, Object> context) {

        String listField = rule.getRefFieldName();
        if (listField == null || listField.isEmpty()) {
            return buildViolation(rule, fieldName, actualValue,
                    "VALUE_IS_NUMBERED: listFieldName is null",
                    "VALUE_IS_NUMBERED 规则缺少列表字段名");
        }

        Object listObj = context.get(listField);
        if (!(listObj instanceof List)) {
            // 列表不存在或为空时跳过校验（存在性由其他规则保证）
            return null;
        }

        List<?> list = (List<?>) listObj;
        if (list.isEmpty()) {
            return null;
        }

        // 收集列表中每行的序号值
        java.util.List<Integer> numbers = new java.util.ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> row = (Map<String, Object>) item;
            Object val = row.get(fieldName);
            if (val == null) {
                return buildViolation(rule, fieldName, actualValue,
                        "VALUE IS NUMBERED failed: field '" + fieldName + "' is missing in one or more rows of " + listField,
                        "连续编号校验失败：列表 " + listField + " 中存在缺少字段 " + fieldName + " 的行");
            }
            try {
                numbers.add((int) Double.parseDouble(val.toString()));
            } catch (NumberFormatException e) {
                return buildViolation(rule, fieldName, actualValue,
                        "VALUE IS NUMBERED failed: value '" + val + "' in " + listField + " is not a valid integer",
                        "连续编号校验失败：列表 " + listField + " 中值 " + val + " 不是有效整数");
            }
        }

        // 排序后校验是否从 1 开始连续
        java.util.Collections.sort(numbers);
        for (int i = 0; i < numbers.size(); i++) {
            int expected = i + 1;
            if (numbers.get(i) != expected) {
                return buildViolation(rule, fieldName, actualValue,
                        "VALUE IS NUMBERED failed: expected sequence 1.." + numbers.size()
                                + " but found " + numbers,
                        "连续编号校验失败：期望序列 1.." + numbers.size() + "，实际为 " + numbers);
            }
        }
        return null;
    }

    // ==========================================
    // 跨字段值比较校验
    // ==========================================

    /**
     * VALUE_FIELD_COMPARE：将当前字段值与 context 中另一字段做数值比较。
     *
     * <p>示例规则：{@code VALUE != @Version}、{@code VALUE < @TechnicallyPermissibleMaximumLadenMass}
     *
     * <p>仅支持数值比较（=, !=, >, <, >=, <=）。若任一侧无法转换为数字则降级为字符串
     * EQ/NEQ 比较；其他运算符降级失败时返回违规。
     */
    private static RuleViolation checkValueFieldCompare(
            String fieldName, Object actualValue, RuleItem rule, Map<String, Object> context) {

        String targetField = rule.getRefFieldName();
        if (targetField == null || targetField.isEmpty()) {
            return buildViolation(rule, fieldName, actualValue,
                    "VALUE_FIELD_COMPARE: compareFieldName is null",
                    "VALUE_FIELD_COMPARE 规则缺少目标字段名");
        }

        Object targetValue = context.get(targetField);

        // 目标字段为空时跳过（由其他规则保证目标字段必填）
        if (isAbsent(targetValue)) {
            return null;
        }

        CompareOperator op;
        try {
            op = CompareOperator.fromSymbol(rule.getOperator());
        } catch (IllegalArgumentException e) {
            return buildViolation(rule, fieldName, actualValue,
                    "VALUE_FIELD_COMPARE: unknown operator '" + rule.getOperator() + "'",
                    "VALUE_FIELD_COMPARE 未知运算符: " + rule.getOperator());
        }

        // 优先尝试数值比较
        try {
            double actual = toDouble(actualValue);
            double target = toDouble(targetValue);
            if (!op.apply(actual, target)) {
                return buildViolation(rule, fieldName, actualValue,
                        "Value " + actualValue + " " + op.getSymbol() + " @" + targetField
                                + "(" + targetValue + ") failed",
                        "字段值 " + actualValue + " 与 @" + targetField
                                + "(" + targetValue + ") 比较不通过（" + op.getSymbol() + "）");
            }
            return null;
        } catch (Exception e) {
            // 数值转换失败，降级字符串 EQ/NEQ
            String actualStr = actualValue == null ? null : actualValue.toString();
            String targetStr = targetValue.toString();
            boolean result;
            try {
                result = op.applyString(actualStr, targetStr);
            } catch (IllegalArgumentException ex) {
                return buildViolation(rule, fieldName, actualValue,
                        "VALUE_FIELD_COMPARE: cannot compare non-numeric values with operator "
                                + op.getSymbol(),
                        "VALUE_FIELD_COMPARE：非数值字段不支持运算符 " + op.getSymbol());
            }
            if (!result) {
                return buildViolation(rule, fieldName, actualValue,
                        "Value " + actualValue + " " + op.getSymbol()
                                + " @" + targetField + "(" + targetValue + ") failed",
                        "字段值 " + actualValue + " 与 @" + targetField
                                + "(" + targetValue + ") 比较不通过（" + op.getSymbol() + "）");
            }
            return null;
        }
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
                .ruleTypeLabel(com.ruoyi.common.core.enums.RuleItemType.getRuleType(rule.getType()))
                .build();
    }
}