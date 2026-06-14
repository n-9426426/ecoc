package com.ruoyi.common.core.executor;

import com.ruoyi.common.core.enums.CompareOperator;
import com.ruoyi.common.core.enums.RuleItemType;
import com.ruoyi.common.core.model.*;
import com.ruoyi.common.core.parser.FinalRuleParser;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 规则执行器
 * 负责对单个字段执行所有 RuleItem 校验，返回 FieldValidationResult
 *
 * <h3>多值字段支持</h3>
 * <p>对于 rangeRule 产生的范围类规则（NUMERIC_RANGE / LENGTH_RANGE / MAX_LENGTH /
 * MIN_LENGTH / TOTAL_DIGITS / FRACTION_DIGITS），若字段值包含 {@code ;} 或 {@code |}
 * 分隔符，则先通过 {@link FinalRuleParser#splitMultiValue(String)} 拆分为子值列表，
 * 再对每个子值独立执行校验。违规报告中会明确标注是哪个子值（以及原始完整值）不符合规则。
 *
 * <p>非范围类规则（如 VALUE_IN、VALUE_REGEX 等）不做拆分，仍以原始完整值参与校验。
 *
 * <h3>新增：CONDITIONAL_COUNT_AGGREGATE</h3>
 * <p>对应 {@link RuleItemType#CONDITIONAL_COUNT_AGGREGATE}，执行三步逻辑：
 * <ol>
 *   <li>评估前置字段条件链（{@code conditionChain}，可为 null）——全部满足才继续</li>
 *   <li>从 context 取列表字段，对每行统计满足 WITHIN 枚举值的条目数</li>
 *   <li>COUNT 比较通过 → 检查 VALUE 的存在性（IS_PRESENT / IS_ABSENT）</li>
 * </ol>
 */
public class FinalRuleExecutor {

    /**
     * 范围类规则类型集合，这些规则需要对多值字段逐个子值校验
     */
    private static final java.util.Set<RuleItemType> RANGE_RULE_TYPES = java.util.EnumSet.of(
            RuleItemType.NUMERIC_RANGE,
            RuleItemType.LENGTH_RANGE,
            RuleItemType.MAX_LENGTH,
            RuleItemType.MIN_LENGTH,
            RuleItemType.TOTAL_DIGITS,
            RuleItemType.FRACTION_DIGITS
    );

    /**
     * 执行字段校验
     *
     * @param fieldName   字段名
     * @param actualValue 字段实际值（多值字段可含 | 或 ; 分隔符）
     * @param rules       规则列表（由 FinalRuleParser 解析）
     * @param context     当前报文上下文（key=字段名, value=字段值）
     * @return FieldValidationResult，含所有子值违规明细
     */
    public static FieldValidationResult execute(
            String fieldName,
            Object actualValue,
            List<RuleItem> rules,
            Map<String, Object> context) {

        List<RuleViolation> violations = new ArrayList<>();

        for (RuleItem rule : rules) {
            if (RANGE_RULE_TYPES.contains(rule.getType()) && !isAbsent(actualValue)) {
                // ---- 范围类规则：拆分多值逐一校验 ----
                String rawStr = String.valueOf(actualValue);
                String[] parts = FinalRuleParser.splitMultiValue(rawStr);

                if (parts.length > 1) {
                    for (String part : parts) {
                        RuleViolation v = checkRule(fieldName, part, rule, context);
                        if (v != null) {
                            violations.add(enrichWithSubValue(v, part, rawStr));
                        }
                    }
                } else {
                    RuleViolation v = checkRule(fieldName, actualValue, rule, context);
                    if (v != null) {
                        violations.add(v);
                    }
                }
            } else {
                // ---- 非范围类规则：原有逻辑，整体值参与校验 ----
                RuleViolation violation = checkRule(fieldName, actualValue, rule, context);
                if (violation != null) {
                    violations.add(violation);
                }
            }
        }

        return FieldValidationResult.builder()
                .fieldName(fieldName)
                .value(actualValue)
                .valid(violations.isEmpty())
                .violations(violations)
                .build();
    }

    /**
     * 对子值校验产生的违规报告进行二次加工，在消息中注明具体子值及原始完整值。
     */
    private static RuleViolation enrichWithSubValue(
            RuleViolation original, String subValue, String rawValue) {

        String suffixEn = " [sub-value='" + subValue + "', raw='" + rawValue + "']";
        String suffixZh = " [子值='" + subValue + "'，原始值='" + rawValue + "']";

        return RuleViolation.builder()
                .ruleId(original.getRuleId())
                .fieldName(original.getFieldName())
                .actualValue(original.getActualValue())
                .messageEn(original.getMessageEn() + suffixEn)
                .messageZh(original.getMessageZh() + suffixZh)
                .rawRule(original.getRawRule())
                .ruleType(original.getRuleType())
                .ruleTypeLabel(original.getRuleTypeLabel())
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

                case PARSE_ERROR:
                    return buildViolation(rule, fieldName, actualValue,
                            rule.getErrorMessageEn(),
                            rule.getErrorMessageZh());

                case VALUE_IS_PRESENT:
                    if (isAbsent(actualValue)) {
                        return buildViolation(rule, fieldName, actualValue,
                                "Field is required", "必填字段不能为空");
                    }
                    return null;

                case VALUE_IS_ABSENT:
                    if (!isAbsent(actualValue)) {
                        return buildViolation(rule, fieldName, actualValue,
                                "Field must be absent", "该字段在此场景下必须为空");
                    }
                    return null;

                case VALUE_IN:
                    strVal = String.valueOf(actualValue);
                    if (rule.getEnumValues() == null || !rule.getEnumValues().contains(strVal)) {
                        return buildViolation(rule, fieldName, actualValue,
                                "Value not in allowed list: " + rule.getEnumValues(),
                                "值不在允许的枚举列表中: " + rule.getEnumValues());
                    }
                    return null;

                case VALUE_REGEX:
                    strVal = String.valueOf(actualValue);
                    if (!Pattern.matches(rule.getRegexPattern(), strVal)) {
                        return buildViolation(rule, fieldName, actualValue,
                                "Value does not match pattern: " + rule.getRegexPattern(),
                                "值不符合正则格式: " + rule.getRegexPattern());
                    }
                    return null;

                case VALUE_COMPARE:
                    if (!compareValue(actualValue, rule.getCompareValue(), rule.getOperator())) {
                        return buildViolation(rule, fieldName, actualValue,
                                "Value compare failed: " + rule.getOperator() + " " + rule.getCompareValue(),
                                "数值比较不通过: " + rule.getOperator() + " " + rule.getCompareValue());
                    }
                    return null;

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
                                    "@" + rule.getRefFieldName() + " "
                                            + ("PRESENT".equals(rule.getRefFieldCondition()) ? "有值" : "为空")
                                            + "，当前字段必须填写");
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
                                    "@" + rule.getRefFieldName() + " "
                                            + ("PRESENT".equals(rule.getRefFieldCondition()) ? "有值" : "为空")
                                            + "，当前字段必须为空");
                        }
                        return null;
                    }
                    break;

                case COUNT_AGGREGATE:
                    return checkCountAggregate(fieldName, actualValue, rule, context);

                case COUNT_AGGREGATE_FIELD:
                    return checkCountAggregateField(fieldName, actualValue, rule, context);

                case SUM_EQUALS_FIELDS:
                    return checkSumEqualsFields(fieldName, actualValue, rule, context);

                case SUM_AGGREGATE:
                    return checkSumAggregate(fieldName, actualValue, rule, context);

                case NESTED_CONDITION:
                    return checkNestedCondition(fieldName, actualValue, rule, context);

                case VALUE_IS_NUMBERED:
                    return checkValueIsNumbered(fieldName, actualValue, rule, context);

                case VALUE_FIELD_COMPARE:
                    return checkValueFieldCompare(fieldName, actualValue, rule, context);

                case COUNT_AS_VALUE:
                    return checkCountAsValue(fieldName, actualValue, rule, context);

                // ★ 修复：VALUE = COUNT(@AxleGroup) → 统计 context 中列表的行数，与 actualValue 比较
                case VALUE_COUNT_SIMPLE:
                    return checkValueCountSimple(fieldName, actualValue, rule, context);

                // ★ 修复：VALUE = COUNT(PoweredAxleIndicator IN ['Y']) → 统计满足条件的行数，与 actualValue 比较
                case VALUE_COUNT_WITHIN:
                    return checkValueCountWithin(fieldName, actualValue, rule, context);

                case LIST_COUNT:
                    return checkListCount(fieldName, actualValue, rule, context);

                case LIST_LAST_FORBIDDEN:
                    return checkListLastForbidden(fieldName, actualValue, rule, context);

                case CONDITIONAL_REGEX:
                    return checkConditionalRegex(fieldName, actualValue, rule, context);

                case CONDITIONAL_VALUE_COMPARE:
                    return checkConditionalValueCompare(fieldName, actualValue, rule, context);

                case CONDITIONAL_FIELD_COMPARE:
                    return checkConditionalFieldCompare(fieldName, actualValue, rule, context);

                case VALUE_IN_LIST_FIELD:
                    return checkValueInListField(fieldName, actualValue, rule, context);

                case LIST_UNIQUE:
                    return checkListUnique(fieldName, actualValue, rule, context);

                case GROUPED_LIST_COUNT:
                    return checkGroupedListCount(fieldName, actualValue, rule, context);

                // ★ 新增
                case CONDITIONAL_COUNT_AGGREGATE:
                    return checkConditionalCountAggregate(fieldName, actualValue, rule, context);

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
                    return buildViolation(rule, fieldName, actualValue,
                            "Unknown rule type: " + rule.getType(),
                            "未知规则类型: " + rule.getType());
            }
        } catch (Exception e) {
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
        System.out.println("[DEBUG] checkForbiddenIfAny fieldName=" + fieldName
                + " EnergySource=" + context.get("EnergySource")
                + " EnergySource类型=" + (context.get("EnergySource") == null ? "null" : context.get("EnergySource").getClass().getName())
                + " conditionChain=" + rule.getConditionChain()
                + " evaluate结果=" + rule.getConditionChain().evaluate(context));
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

    /**
     * SUM(@AxleGroup, @TechnicallyPermissibleMassAxle) >= VALUE
     *
     * 跨行聚合：遍历 listField（AxleGroup）所有行，
     * 将每行 field（TechnicallyPermissibleMassAxle）的值累加为全局 sum，
     * 再用 sum 与 actualValue（当前字段值）做比较。
     *
     * 注意：原实现误做"逐行各自 / 拆分求和后与 threshold 比较"，语义完全错误，此处修正。
     */
    private static RuleViolation checkSumAggregate(
            String fieldName, Object actualValue, RuleItem rule, Map<String, Object> context) {

        AggregateFunction af = rule.getAggregateFunction();
        if (af == null) {
            return buildViolation(rule, fieldName, actualValue,
                    "SUM_AGGREGATE: aggregateFunction is null",
                    "SUM_AGGREGATE 规则缺少聚合函数描述");
        }

        Object listObj = context.get(af.getListField());
        if (!(listObj instanceof List)) return null; // 列表不存在，跳过

        List<?> list = (List<?>) listObj;

        // ★ 修复：跨所有行累加，而非逐行独立校验
        double totalSum = 0.0;
        for (Object item : list) {
            if (!(item instanceof Map)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> row = (Map<String, Object>) item;
            Object val = row.get(af.getField());
            if (val == null) continue;
            try {
                totalSum += Double.parseDouble(val.toString().trim());
            } catch (NumberFormatException ignored) {
                // 非数值行跳过，不中断整体求和
            }
        }

        // ★ 修复：与 actualValue（当前字段值）比较，而非与 threshold 比较
        //   规则语义：SUM(...) >= VALUE，即 sum 满足 op actualValue
        double actual;
        try {
            actual = toDouble(actualValue);
        } catch (Exception e) {
            return buildViolation(rule, fieldName, actualValue,
                    "SUM_AGGREGATE: current field value is not numeric: " + actualValue,
                    "SUM_AGGREGATE：当前字段值不是数值：" + actualValue);
        }

        if (!af.getOperator().apply(totalSum, actual)) {
            return buildViolation(rule, fieldName, actualValue,
                    String.format("SUM(%s, %s) = %.1f %s VALUE(%.1f) failed",
                            af.getListField(), af.getField(),
                            totalSum, af.getOperator().getSymbol(), actual),
                    String.format("所有 %s 行的 %s 之和为 %.1f，不满足要求 %s 当前值 %.1f",
                            af.getListField(), af.getField(),
                            totalSum, af.getOperator().getSymbol(), actual));
        }
        return null;
    }

    // ==========================================
    // ★ 新增：CONDITIONAL_COUNT_AGGREGATE 执行逻辑
    // ==========================================

    /**
     * 执行带前置字段条件的 COUNT WITHIN 存在性校验。
     *
     * <p>执行步骤：
     * <ol>
     *   <li><b>Step 1 前置字段条件</b>：若 {@code conditionChain} 不为 null，
     *       则评估所有前置字段条件（全部满足才继续）；为 null 表示无前置条件，直接进入 Step 2。</li>
     *   <li><b>Step 2 COUNT WITHIN</b>：从 context 取列表字段（{@code af.getListField()}），
     *       遍历每行，统计行内字段值（{@code af.getListField()}）命中 {@code af.getEnumValues()}
     *       的行数，与 {@code af.getThreshold()} 用 {@code af.getOperator()} 比较。
     *       COUNT 条件不满足则规则不触发（return null）。</li>
     *   <li><b>Step 3 存在性校验</b>：COUNT 条件满足后，按 {@code rule.getOperator()}
     *       （"IS_PRESENT" 或 "IS_ABSENT"）检查 {@code actualValue}。</li>
     * </ol>
     *
     * <p>context 中列表字段的数据结构约定（与 COUNT_AGGREGATE 相同）：
     * <pre>
     *   context.get("EnergySource") = List&lt;Map&lt;String, Object&gt;&gt;
     *   每行 Map 含 key="EnergySource", value="95" / "5" / ...
     * </pre>
     *
     * <p>典型规则示例：
     * <pre>
     *   // R1 (格式A): 前置字段条件 + COUNT
     *   VALUE IS PRESENT IF @ConsolidatedMaximum30MinutesPower IS ABSENT
     *                     AND COUNT(EnergySource WITHIN ['95']) > 1
     *
     *   // R2 (格式B): 纯 COUNT
     *   VALUE IS ABSENT IF COUNT(EnergySource WITHIN ['95']) < 2
     * </pre>
     *
     * @param fieldName   被校验字段名
     * @param actualValue 被校验字段的实际值
     * @param rule        CONDITIONAL_COUNT_AGGREGATE 类型的 RuleItem
     * @param context     报文上下文（含列表字段及其他字段）
     * @return 违规时返回 {@link RuleViolation}，通过返回 null
     */
    private static RuleViolation checkConditionalCountAggregate(
            String fieldName,
            Object actualValue,
            RuleItem rule,
            Map<String, Object> context) {

        AggregateFunction af = rule.getAggregateFunction();
        if (af == null) {
            return buildViolation(rule, fieldName, actualValue,
                    "CONDITIONAL_COUNT_AGGREGATE: aggregateFunction is null",
                    "CONDITIONAL_COUNT_AGGREGATE 规则缺少聚合函数描述");
        }

        // ---- Step 1：评估前置字段条件链 ----
        // conditionChain 为 null 表示格式B（无前置条件），直接跳过
        ConditionChain preChain = rule.getConditionChain();
        if (preChain != null && !preChain.evaluate(context)) {
            // 前置字段条件不满足，整条规则不触发
            return null;
        }

        // ---- Step 2：COUNT WITHIN 列表计数 ----
        String listFieldName = af.getListField();
        if (listFieldName == null || listFieldName.isEmpty()) {
            return buildViolation(rule, fieldName, actualValue,
                    "CONDITIONAL_COUNT_AGGREGATE: listField is null or empty",
                    "CONDITIONAL_COUNT_AGGREGATE 规则缺少列表字段名");
        }

        Object listObj = context.get(listFieldName);
        if (!(listObj instanceof List)) {
            // 列表字段不存在或不是 List 类型：COUNT = 0，按 0 参与比较
            // 这允许空列表场景下 COUNT < N 正确触发禁填
            long count = 0L;
            if (!af.getOperator().apply((double) count, af.getThreshold())) {
                // COUNT 条件不满足，规则不触发
                return null;
            }
            // COUNT 条件满足（如 0 < 2 时 "IS ABSENT" 应触发）→ 进入 Step 3
            return checkPresenceByOperator(rule, fieldName, actualValue, count);
        }

        List<?> list = (List<?>) listObj;
        List<String> withinVals = af.getEnumValues();

        long count = list.stream()
                .filter(item -> item instanceof Map)
                .filter(item -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> row = (Map<String, Object>) item;
                    // 列表字段名既是 context key 也是行内字段名（EnergySource -> row["EnergySource"]）
                    Object rowVal = row.get(listFieldName);
                    if (rowVal == null) return false;
                    String strVal = rowVal.toString().trim();
                    return withinVals != null && withinVals.contains(strVal);
                })
                .count();

        // ---- Step 3：COUNT 比较 ----
        if (!af.getOperator().apply((double) count, af.getThreshold())) {
            // COUNT 条件不满足，规则不触发
            return null;
        }

        // COUNT 条件满足 → 检查 VALUE 的存在性
        return checkPresenceByOperator(rule, fieldName, actualValue, count);
    }

    /**
     * 根据 rule.getOperator()（"IS_PRESENT" / "IS_ABSENT"）检查 VALUE 的存在性，
     * 构建并返回违规报告（通过返回 null）。
     *
     * @param count 实际 COUNT 值，用于填充违规消息
     */
    private static RuleViolation checkPresenceByOperator(
            RuleItem rule, String fieldName, Object actualValue, long count) {

        AggregateFunction af = rule.getAggregateFunction();
        String countDesc = buildCountDesc(af, count);

        if ("IS_PRESENT".equals(rule.getOperator())) {
            if (isAbsent(actualValue)) {
                // 前置条件 + COUNT 均满足，但 VALUE 为空 → 必填违规
                String preDesc = buildPreCondDesc(rule);
                String fullDesc = preDesc.isEmpty()
                        ? countDesc
                        : preDesc + " AND " + countDesc;
                return buildViolation(rule, fieldName, actualValue,
                        "Field is required: " + fullDesc,
                        "条件满足时该字段为必填: " + fullDesc);
            }
        } else if ("IS_ABSENT".equals(rule.getOperator())) {
            if (!isAbsent(actualValue)) {
                // 前置条件 + COUNT 均满足，但 VALUE 有值 → 禁填违规
                String preDesc = buildPreCondDesc(rule);
                String fullDesc = preDesc.isEmpty()
                        ? countDesc
                        : preDesc + " AND " + countDesc;
                return buildViolation(rule, fieldName, actualValue,
                        "Field must be absent: " + fullDesc,
                        "条件满足时该字段必须为空: " + fullDesc);
            }
        } else {
            return buildViolation(rule, fieldName, actualValue,
                    "CONDITIONAL_COUNT_AGGREGATE: unknown presence operator: " + rule.getOperator(),
                    "CONDITIONAL_COUNT_AGGREGATE 未知存在性操作符: " + rule.getOperator());
        }

        return null;
    }

    /**
     * 构建 COUNT 描述字符串，用于违规消息。
     * 示例：{@code "COUNT(EnergySource WITHIN ['95']) > 1 (actual=2)"}
     */
    private static String buildCountDesc(AggregateFunction af, long actualCount) {
        if (af == null) return "(unknown COUNT)";
        return "COUNT(" + af.getListField()
                + " WITHIN " + af.getEnumValues() + ") "
                + af.getOperator().getSymbol()
                + " " + af.getThreshold().intValue()
                + " (actual=" + actualCount + ")";
    }

    /**
     * 构建前置字段条件的描述字符串，conditionChain 为 null 时返回空字符串。
     */
    private static String buildPreCondDesc(RuleItem rule) {
        // ConditionChain 未提供 toString()，此处返回占位文本
        // 实际项目中可在 ConditionChain 上实现 toDescription() 获得更好的可读性
        return rule.getConditionChain() != null ? "(pre-conditions met)" : "";
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
                return buildViolation(rule, fieldName, actualValue,
                        "Unknown nested condition operator: " + nested.getOperator(),
                        "嵌套条件未知操作符: " + nested.getOperator());
        }
        return null;
    }

    // ==========================================
    // 列表连续编号校验
    // ==========================================

    private static RuleViolation checkValueIsNumbered(
            String fieldName, Object actualValue, RuleItem rule, Map<String, Object> context) {

        String listField = rule.getRefFieldName();
        if (listField == null || listField.isEmpty()) {
            return buildViolation(rule, fieldName, actualValue,
                    "VALUE_IS_NUMBERED: listFieldName is null",
                    "VALUE_IS_NUMBERED 规则缺少列表字段名");
        }

        Object listObj = context.get(listField);
        if (!(listObj instanceof List)) return null;

        List<?> list = (List<?>) listObj;
        if (list.isEmpty()) return null;

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

        java.util.Collections.sort(numbers);
        for (int i = 0; i < numbers.size(); i++) {
            if (numbers.get(i) != i + 1) {
                return buildViolation(rule, fieldName, actualValue,
                        "VALUE IS NUMBERED failed: expected sequence 1.." + numbers.size() + " but found " + numbers,
                        "连续编号校验失败：期望序列 1.." + numbers.size() + "，实际为 " + numbers);
            }
        }
        return null;
    }

    // ==========================================
    // 跨字段值比较校验
    // ==========================================

    private static RuleViolation checkValueFieldCompare(
            String fieldName, Object actualValue, RuleItem rule, Map<String, Object> context) {

        String targetField = rule.getRefFieldName();
        if (targetField == null || targetField.isEmpty()) {
            return buildViolation(rule, fieldName, actualValue,
                    "VALUE_FIELD_COMPARE: compareFieldName is null",
                    "VALUE_FIELD_COMPARE 规则缺少目标字段名");
        }

        Object targetValue = context.get(targetField);
        if (isAbsent(targetValue)) return null;

        CompareOperator op;
        try {
            op = CompareOperator.fromSymbol(rule.getOperator());
        } catch (IllegalArgumentException e) {
            return buildViolation(rule, fieldName, actualValue,
                    "VALUE_FIELD_COMPARE: unknown operator '" + rule.getOperator() + "'",
                    "VALUE_FIELD_COMPARE 未知运算符: " + rule.getOperator());
        }

        try {
            double actual = toDouble(actualValue);
            double target = toDouble(targetValue);
            if (!op.apply(actual, target)) {
                return buildViolation(rule, fieldName, actualValue,
                        "Value " + actualValue + " " + op.getSymbol() + " @" + targetField + "(" + targetValue + ") failed",
                        "字段值 " + actualValue + " 与 @" + targetField + "(" + targetValue + ") 比较不通过（" + op.getSymbol() + "）");
            }
            return null;
        } catch (Exception e) {
            String actualStr = actualValue == null ? null : actualValue.toString();
            String targetStr = targetValue.toString();
            boolean result;
            try {
                result = op.applyString(actualStr, targetStr);
            } catch (IllegalArgumentException ex) {
                return buildViolation(rule, fieldName, actualValue,
                        "VALUE_FIELD_COMPARE: cannot compare non-numeric values with operator " + op.getSymbol(),
                        "VALUE_FIELD_COMPARE：非数值字段不支持运算符 " + op.getSymbol());
            }
            if (!result) {
                return buildViolation(rule, fieldName, actualValue,
                        "Value " + actualValue + " " + op.getSymbol() + " @" + targetField + "(" + targetValue + ") failed",
                        "字段值 " + actualValue + " 与 @" + targetField + "(" + targetValue + ") 比较不通过（" + op.getSymbol() + "）");
            }
            return null;
        }
    }

    // ==========================================
    // COUNT_AS_VALUE / LIST_COUNT 聚合校验
    // ==========================================

    /**
     * VALUE = COUNT(@AxleGroup)
     * 统计 context 中 listField 列表的总行数，与当前字段值比较（必须相等）。
     * 对应规则：R3: VALUE = COUNT(@AxleGroup)
     */
    private static RuleViolation checkValueCountSimple(
            String fieldName, Object actualValue, RuleItem rule, Map<String, Object> context) {

        String listField = rule.getRefFieldName();   // 解析器把 AxleGroup 存入 refFieldName
        if (listField == null || listField.isEmpty()) {
            return buildViolation(rule, fieldName, actualValue,
                    "VALUE_COUNT_SIMPLE: listField is null",
                    "VALUE_COUNT_SIMPLE 规则缺少列表字段名");
        }

        Object listObj = context.get(listField);
        long count = (listObj instanceof List) ? ((List<?>) listObj).size() : 0L;

        double actualDouble = toDouble(actualValue);
        if ((double) count != actualDouble) {
            return buildViolation(rule, fieldName, actualValue,
                    "VALUE = COUNT(" + listField + ") failed: expected " + count
                            + " but field value is " + actualValue,
                    "COUNT(" + listField + ") = " + count + "，与字段值 " + actualValue + " 不符");
        }
        return null;
    }

    /**
     * VALUE = COUNT(@AxleGroup, @PoweredAxleIndicator IN [Y])
     * 统计列表中满足条件的行数，与当前字段值比较（必须相等）。
     * 对应规则：R2: COUNT(@AxleGroup, @PoweredAxleIndicator IN [Y]) = VALUE
     * 注意：该规则语法与 COUNT_AS_VALUE 相同，VALUE_COUNT_WITHIN 对应 VALUE = COUNT(field IN [vals])（无列表参数的简化写法）。
     */
    private static RuleViolation checkValueCountWithin(
            String fieldName, Object actualValue, RuleItem rule, Map<String, Object> context) {

        AggregateFunction af = rule.getAggregateFunction();
        if (af == null) {
            return buildViolation(rule, fieldName, actualValue,
                    "VALUE_COUNT_WITHIN: aggregateFunction is null",
                    "VALUE_COUNT_WITHIN 规则缺少聚合函数描述");
        }

        String listField = af.getListField();
        String condField = af.getField();
        List<String> allowed = af.getEnumValues();

        Object listObj = context.get(listField);
        long count;
        if (listObj instanceof List) {
            List<?> list = (List<?>) listObj;
            count = list.stream()
                    .filter(item -> item instanceof Map)
                    .filter(item -> {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> row = (Map<String, Object>) item;
                        Object val = row.get(condField);
                        if (val == null) return false;
                        String strVal = val.toString().trim();
                        return allowed == null || allowed.contains(strVal);
                    })
                    .count();
        } else {
            count = 0L;
        }

        double actualDouble = toDouble(actualValue);
        if ((double) count != actualDouble) {
            return buildViolation(rule, fieldName, actualValue,
                    "VALUE = COUNT(" + listField + ", " + condField + " IN " + allowed + ") failed: expected "
                            + count + " but field value is " + actualValue,
                    "COUNT(" + listField + ", " + condField + " IN " + allowed + ") = " + count
                            + "，与字段值 " + actualValue + " 不符");
        }
        return null;
    }



    private static RuleViolation checkCountAsValue(
            String fieldName, Object actualValue, RuleItem rule, Map<String, Object> context) {

        AggregateFunction af = rule.getAggregateFunction();
        if (af == null) {
            return buildViolation(rule, fieldName, actualValue,
                    "COUNT_AS_VALUE: aggregateFunction is null",
                    "COUNT_AS_VALUE 规则缺少聚合函数描述");
        }

        Object listObj = context.get(af.getListField());
        if (!(listObj instanceof List)) return null;

        List<?> list = (List<?>) listObj;
        String condField = af.getField();
        List<String> allowed = af.getEnumValues();

        long count;
        if (condField == null || condField.isEmpty()) {
            // ★ 修复：VALUE = COUNT(@AxleGroup) 无条件字段时，直接统计列表总行数
            count = list.stream().filter(item -> item instanceof Map).count();
        } else {
            // 有条件字段时，统计满足条件的行数
            count = list.stream()
                    .filter(item -> item instanceof Map)
                    .filter(item -> {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> row = (Map<String, Object>) item;
                        Object val = row.get(condField);
                        if (val == null) return false;
                        String strVal = val.toString();
                        return allowed == null || allowed.contains(strVal);
                    })
                    .count();
        }

        double actualDouble = toDouble(actualValue);
        if ((double) count != actualDouble) {
            String desc = (condField == null || condField.isEmpty())
                    ? "COUNT(" + af.getListField() + ")"
                    : "COUNT(" + af.getListField() + ", " + condField + " IN " + allowed + ")";
            return buildViolation(rule, fieldName, actualValue,
                    desc + " = " + count + " but field value is " + actualValue,
                    "列表中满足条件的行数为 " + count + "，与字段值 " + actualValue + " 不符");
        }
        return null;
    }

    private static RuleViolation checkListCount(
            String fieldName, Object actualValue, RuleItem rule, Map<String, Object> context) {

        AggregateFunction af = rule.getAggregateFunction();
        if (af == null) {
            return buildViolation(rule, fieldName, actualValue,
                    "LIST_COUNT: aggregateFunction is null",
                    "LIST_COUNT 规则缺少聚合函数描述");
        }

        Object listObj = context.get(af.getListField());
        if (!(listObj instanceof List)) return null;

        List<?> list = (List<?>) listObj;
        List<String> allowed = af.getEnumValues();

        long count = list.stream()
                .filter(item -> item instanceof Map)
                .filter(item -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> row = (Map<String, Object>) item;
                    Object val = row.get(fieldName);
                    if (val == null) return false;
                    String strVal = val.toString();
                    return allowed == null || allowed.contains(strVal);
                })
                .count();

        if (!af.getOperator().apply((double) count, af.getThreshold())) {
            return buildViolation(rule, fieldName, actualValue,
                    af.getListField() + "=>COUNT(VALUE IN " + allowed + ") "
                            + af.getOperator().getSymbol() + " " + af.getThreshold().intValue()
                            + " failed (actual count=" + count + ")",
                    "列表 " + af.getListField() + " 中满足条件的行数为 " + count
                            + "，不满足要求 " + af.getOperator().getSymbol()
                            + " " + af.getThreshold().intValue());
        }
        return null;
    }

    // ==========================================
    // 条件型规则（CONDITIONAL_*）
    // ==========================================

    private static RuleViolation checkConditionalRegex(
            String fieldName, Object actualValue, RuleItem rule, Map<String, Object> context) {

        ConditionChain chain = rule.getConditionChain();
        if (chain == null || !chain.evaluate(context)) return null;
        if (isAbsent(actualValue)) return null;

        String strVal = String.valueOf(actualValue);
        String pattern = rule.getRegexPattern();
        if (pattern == null || !java.util.regex.Pattern.matches(pattern, strVal)) {
            return buildViolation(rule, fieldName, actualValue,
                    "Value does not match pattern: " + pattern + " (condition met)",
                    "条件满足时值不符合正则格式: " + pattern);
        }
        return null;
    }

    private static RuleViolation checkConditionalValueCompare(
            String fieldName, Object actualValue, RuleItem rule, Map<String, Object> context) {

        ConditionChain chain = rule.getConditionChain();
        if (chain == null || !chain.evaluate(context)) return null;
        if (isAbsent(actualValue)) return null;

        if (!compareValue(actualValue, rule.getCompareValue(), rule.getOperator())) {
            return buildViolation(rule, fieldName, actualValue,
                    "Value compare failed (condition met): VALUE " + rule.getOperator() + " " + rule.getCompareValue(),
                    "条件满足时数值比较不通过: VALUE " + rule.getOperator() + " " + rule.getCompareValue());
        }
        return null;
    }

    private static RuleViolation checkConditionalFieldCompare(
            String fieldName, Object actualValue, RuleItem rule, Map<String, Object> context) {

        ConditionChain chain = rule.getConditionChain();
        if (chain == null || !chain.evaluate(context)) return null;

        String targetField = rule.getRefFieldName();
        if (targetField == null || targetField.isEmpty()) {
            return buildViolation(rule, fieldName, actualValue,
                    "CONDITIONAL_FIELD_COMPARE: refFieldName is null",
                    "CONDITIONAL_FIELD_COMPARE 规则缺少目标字段名");
        }

        Object targetValue = context.get(targetField);
        if (isAbsent(targetValue)) return null;

        CompareOperator op;
        try {
            op = CompareOperator.fromSymbol(rule.getOperator());
        } catch (IllegalArgumentException e) {
            return buildViolation(rule, fieldName, actualValue,
                    "CONDITIONAL_FIELD_COMPARE: unknown operator '" + rule.getOperator() + "'",
                    "CONDITIONAL_FIELD_COMPARE 未知运算符: " + rule.getOperator());
        }

        try {
            double actual = toDouble(actualValue);
            double target = toDouble(targetValue);
            if (!op.apply(actual, target)) {
                return buildViolation(rule, fieldName, actualValue,
                        "Value " + actualValue + " " + op.getSymbol() + " @" + targetField + "(" + targetValue + ") failed (condition met)",
                        "条件满足时字段值 " + actualValue + " 与 @" + targetField + "(" + targetValue + ") 比较不通过（" + op.getSymbol() + "）");
            }
            return null;
        } catch (Exception e) {
            String actualStr = actualValue == null ? null : actualValue.toString();
            String targetStr = targetValue.toString();
            boolean result;
            try {
                result = op.applyString(actualStr, targetStr);
            } catch (IllegalArgumentException ex) {
                return buildViolation(rule, fieldName, actualValue,
                        "CONDITIONAL_FIELD_COMPARE: cannot compare non-numeric values with operator " + op.getSymbol(),
                        "CONDITIONAL_FIELD_COMPARE：非数值字段不支持运算符 " + op.getSymbol());
            }
            if (!result) {
                return buildViolation(rule, fieldName, actualValue,
                        "Value " + actualValue + " " + op.getSymbol() + " @" + targetField + "(" + targetValue + ") failed (condition met)",
                        "条件满足时字段值 " + actualValue + " 与 @" + targetField + "(" + targetValue + ") 比较不通过（" + op.getSymbol() + "）");
            }
            return null;
        }
    }

    // ==========================================
    // 列表成员检查 / 唯一性校验
    // ==========================================

    private static RuleViolation checkValueInListField(
            String fieldName, Object actualValue, RuleItem rule, Map<String, Object> context) {

        if (isAbsent(actualValue)) return null;

        String listField = rule.getRefFieldName();
        String targetFieldInRow = rule.getCompareValue();

        if (listField == null || targetFieldInRow == null) {
            return buildViolation(rule, fieldName, actualValue,
                    "VALUE_IN_LIST_FIELD: refFieldName or compareValue is null",
                    "VALUE_IN_LIST_FIELD 规则缺少列表字段名或行字段名");
        }

        Object listObj = context.get(listField);
        if (!(listObj instanceof List)) return null;

        String actualStr = String.valueOf(actualValue);
        boolean found = ((List<?>) listObj).stream()
                .filter(item -> item instanceof Map)
                .anyMatch(item -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> row = (Map<String, Object>) item;
                    Object rowVal = row.get(targetFieldInRow);
                    return rowVal != null && actualStr.equals(rowVal.toString());
                });

        if (!found) {
            return buildViolation(rule, fieldName, actualValue,
                    "Value " + actualValue + " not found in any row of " + listField + "." + targetFieldInRow,
                    "值 " + actualValue + " 不在列表 " + listField + " 的 " + targetFieldInRow + " 字段中");
        }
        return null;
    }

    private static RuleViolation checkListUnique(
            String fieldName, Object actualValue, RuleItem rule, Map<String, Object> context) {

        String listField = rule.getRefFieldName();
        if (listField == null || listField.isEmpty()) {
            return buildViolation(rule, fieldName, actualValue,
                    "LIST_UNIQUE: listFieldName is null",
                    "LIST_UNIQUE 规则缺少列表字段名");
        }

        Object listObj = context.get(listField);
        if (!(listObj instanceof List)) return null;

        List<?> list = (List<?>) listObj;
        java.util.Set<String> seen = new java.util.HashSet<>();
        java.util.List<String> duplicates = new java.util.ArrayList<>();

        for (Object item : list) {
            if (!(item instanceof Map)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> row = (Map<String, Object>) item;
            Object val = row.get(fieldName);
            if (val == null) continue;
            String strVal = val.toString();
            if (!seen.add(strVal)) {
                if (!duplicates.contains(strVal)) duplicates.add(strVal);
            }
        }

        if (!duplicates.isEmpty()) {
            return buildViolation(rule, fieldName, actualValue,
                    "LIST_UNIQUE failed: duplicate values found in " + listField + "." + fieldName + ": " + duplicates,
                    "列表 " + listField + " 中字段 " + fieldName + " 存在重复值: " + duplicates);
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
        if (value instanceof String) {
            String s = ((String) value).trim();
            return s.isEmpty() || "null".equalsIgnoreCase(s);
        }
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

    private static RuleViolation checkCountAggregateField(
            String fieldName, Object actualValue, RuleItem rule, Map<String, Object> context) {

        AggregateFunction af = rule.getAggregateFunction();
        String refFieldName  = rule.getRefFieldName();

        // 从 context 取动态阈值字段
        Object refObj = context.get(refFieldName);
        if (isAbsent(refObj)) return null;   // 阈值字段为空，跳过校验

        double threshold;
        try {
            threshold = toDouble(refObj);
        } catch (Exception e) {
            return buildViolation(rule, fieldName, actualValue,
                    "COUNT_AGGREGATE_FIELD: refField '@" + refFieldName + "' is not numeric",
                    "COUNT_AGGREGATE_FIELD：动态阈值字段 @" + refFieldName + " 不是数值");
        }

        // 从 context 取列表字段
        Object listObj = context.get(af.getListField());
        if (!(listObj instanceof List)) return null;

        List<?> list = (List<?>) listObj;
        ConditionExpression condExpr = ConditionExpression.parse(af.getCondition());
        Matcher inMatcher = Pattern
                .compile("(\\w+)\\s+IN\\s+\\[([^\\]]+)\\]", Pattern.CASE_INSENSITIVE)
                .matcher(af.getCondition() == null ? "" : af.getCondition().trim());

        final String inField;
        final java.util.Set<String> inValues;
        if (inMatcher.matches()) {
            inField = inMatcher.group(1).trim();
            inValues = Arrays.stream(inMatcher.group(2).split(","))
                    .map(s -> s.trim().replaceAll("^['\"]|['\"]$", ""))
                    .collect(java.util.stream.Collectors.toSet());
        } else {
            inField = null;
            inValues = null;
        }

        long count = list.stream()
                .filter(item -> {
                    if (!(item instanceof Map)) return false;
                    @SuppressWarnings("unchecked")
                    Map<String, Object> itemMap = (Map<String, Object>) item;
                    // 优先用 IN 条件过滤
                    if (inField != null) {
                        Object val = itemMap.get(inField);
                        if (val == null) return false;
                        return inValues.contains(val.toString().trim());
                    }
                    // 回退到 ConditionExpression
                    if (condExpr == null) return true;
                    return condExpr.evaluate(itemMap);
                })
                .count();

        if (!af.getOperator().apply((double) count, threshold)) {
            return buildViolation(rule, fieldName, actualValue,
                    "COUNT(" + af.getListField() + ", " + af.getCondition() + ") "
                            + af.getOperator().getSymbol()
                            + " @" + refFieldName + "(" + (long) threshold + ") failed"
                            + " (actual count=" + count + ")",
                    "列表中满足条件的元素数量（" + count + "）与字段 @"
                            + refFieldName + "（" + (long) threshold + "）比较不通过");
        }
        return null;
    }

    private static RuleViolation checkSumEqualsFields(
            String fieldName, Object actualValue, RuleItem rule, Map<String, Object> context) {

        List<String> fields = rule.getEnumValues();
        if (fields == null || fields.isEmpty()) {
            return buildViolation(rule, fieldName, actualValue,
                    "SUM_EQUALS_FIELDS: no field list provided",
                    "SUM_EQUALS_FIELDS 规则缺少字段列表");
        }

        // 缺失字段按 0 计算，不再跳过校验
        double sum = 0.0;
        for (String f : fields) {
            Object val = context.get(f);
            if (isAbsent(val)) continue; // null/空 → 视为 0，直接跳过累加
            try {
                sum += Double.parseDouble(val.toString().trim());
            } catch (NumberFormatException e) {
                return buildViolation(rule, fieldName, actualValue,
                        "SUM_EQUALS_FIELDS: field '" + f + "' value '" + val + "' is not numeric",
                        "SUM_EQUALS_FIELDS: 字段 " + f + " 的值 " + val + " 不是数值");
            }
        }

        if (isAbsent(actualValue)) {
            return buildViolation(rule, fieldName, actualValue,
                    "SUM_EQUALS_FIELDS: current field value is absent",
                    "SUM_EQUALS_FIELDS: 当前字段值为空，无法与分量之和比较");
        }

        double actual;
        try {
            actual = Double.parseDouble(String.valueOf(actualValue).trim());
        } catch (NumberFormatException e) {
            return buildViolation(rule, fieldName, actualValue,
                    "SUM_EQUALS_FIELDS: current field value is not numeric",
                    "SUM_EQUALS_FIELDS: 当前字段值不是数值");
        }

        if (Double.compare(actual, sum) != 0) {
            return buildViolation(rule, fieldName, actualValue,
                    String.format("Value %.0f != SUM(%s) = %.0f (missing fields treated as 0)",
                            actual, fields, sum),
                    String.format("字段值 %.0f 不等于 %s 之和（%.0f）（缺失字段按0计算）",
                            actual, String.join(" + ", fields), sum));
        }
        return null;
    }

    /**
     * @AxleGroup=>VALUE IS ABSENT IF LAST
     * 若当前校验行是列表最后一行，则该字段必须为空。
     * 判断"当前行"的方式：actualValue 即当前行该字段的值；
     * 通过对比 context 中列表末行的同名字段值来定位。
     */
    private static RuleViolation checkListLastForbidden(
            String fieldName, Object actualValue, RuleItem rule, Map<String, Object> context) {

        String listField = rule.getRefFieldName();
        if (listField == null || listField.isEmpty()) {
            return buildViolation(rule, fieldName, actualValue,
                    "LIST_LAST_FORBIDDEN: listField is null",
                    "LIST_LAST_FORBIDDEN 规则缺少列表字段名");
        }

        Object listObj = context.get(listField);
        if (!(listObj instanceof List)) return null;

        List<?> list = (List<?>) listObj;
        if (list.isEmpty()) return null;

        // 取末行
        Object lastItem = list.get(list.size() - 1);
        if (!(lastItem instanceof Map)) return null;
        @SuppressWarnings("unchecked")
        Map<String, Object> lastRow = (Map<String, Object>) lastItem;

        // 判断当前值是否属于末行：末行中该字段值与 actualValue 一致
        Object lastVal = lastRow.get(fieldName);
        boolean isLastRow = (actualValue == null && lastVal == null)
                || (actualValue != null && actualValue.equals(lastVal));

        if (isLastRow && !isAbsent(actualValue)) {
            return buildViolation(rule, fieldName, actualValue,
                    "Field '" + fieldName + "' must be absent for the last list (last row of " + listField + ")",
                    "字段 " + fieldName + " 在列表（" + listField + " 末行）必须为空");
        }
        return null;
    }

    /**
     * @AxleGroup.TyreAxleGroup=>COUNT(VALUE IN ['Y']) = 1
     *
     * 遍历父列表（AxleGroup）的每一行，
     * 从该行取子列表（TyreAxleGroup），
     * 统计子列表中当前字段值命中枚举值的行数，
     * 逐组与阈值比较，任意一组不满足则报错。
     *
     * context 数据结构约定：
     *   context.get("AxleGroup") = List<Map<String, Object>>
     *   每个 Map 中 key="TyreAxleGroup" → List<Map<String, Object>>
     *   子 Map 中 key="TyreFittedProductionIndicator" → "Y"/"N"
     */
    private static RuleViolation checkGroupedListCount(
            String fieldName, Object actualValue, RuleItem rule, Map<String, Object> context) {

        AggregateFunction af = rule.getAggregateFunction();
        if (af == null) {
            return buildViolation(rule, fieldName, actualValue,
                    "GROUPED_LIST_COUNT: aggregateFunction is null",
                    "GROUPED_LIST_COUNT 规则缺少聚合函数描述");
        }

        String parentListName = af.getListField();  // AxleGroup
        String childListName  = af.getField();       // TyreAxleGroup
        List<String> allowed  = af.getEnumValues();  // ['Y']

        Object parentObj = context.get(parentListName);
        if (!(parentObj instanceof List)) return null; // 父列表不存在，跳过

        List<?> parentList = (List<?>) parentObj;

        for (int i = 0; i < parentList.size(); i++) {
            Object parentItem = parentList.get(i);
            if (!(parentItem instanceof Map)) continue;

            @SuppressWarnings("unchecked")
            Map<String, Object> parentRow = (Map<String, Object>) parentItem;

            // 取该 AxleGroup 下的 TyreAxleGroup 子列表
            Object childObj = parentRow.get(childListName);
            if (!(childObj instanceof List)) continue; // 某组无子列表，跳过

            List<?> childList = (List<?>) childObj;

            // 统计命中枚举值的行数
            long count = childList.stream()
                    .filter(item -> item instanceof Map)
                    .filter(item -> {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> row = (Map<String, Object>) item;
                        Object val = row.get(fieldName);
                        if (val == null) return false;
                        return allowed == null || allowed.contains(val.toString().trim());
                    })
                    .count();

            if (!af.getOperator().apply((double) count, af.getThreshold())) {
                return buildViolation(rule, fieldName, actualValue,
                        String.format(
                                "%s[%d].%s=>COUNT(%s IN %s) %s %d failed (actual=%d)",
                                parentListName, i + 1, childListName,
                                fieldName, allowed,
                                af.getOperator().getSymbol(), af.getThreshold().intValue(), count),
                        String.format(
                                "第%d个%s下的%s中，%s值为%s的数量为%d，不满足要求%s%d",
                                i + 1, parentListName, childListName,
                                fieldName, allowed, count,
                                af.getOperator().getSymbol(), af.getThreshold().intValue()));
            }
        }

        return null; // 所有分组均通过
    }
}