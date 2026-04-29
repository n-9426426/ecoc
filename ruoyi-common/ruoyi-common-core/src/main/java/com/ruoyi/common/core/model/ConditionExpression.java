package com.ruoyi.common.core.model;

import com.ruoyi.common.core.enums.CompareOperator;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 单个条件表达式
 * 支持：
 *   field = value
 *   field != value, >, <, >=, <=
 *   field IS PRESENT / IS ABSENT
 *   @field（字段有值即满足）
 *
 * <p>{@link #parse(String)} 在表达式无法识别时返回 {@code null}，
 * 不抛出异常、不打印日志，由调用方（{@link ConditionChain}）决定如何处理。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConditionExpression {

    // field op value（支持 =, !=, >, <, >=, <=）
    private static final Pattern COMPARISON_PATTERN =
            Pattern.compile("^@?([\\w.]+)\\s+([=<>!]+)\\s+([^\\s,)]+)$");

    // field IS PRESENT / IS ABSENT
    private static final Pattern IS_PATTERN =
            Pattern.compile("^@?([\\w.]+)\\s+IS\\s+(PRESENT|ABSENT)$", Pattern.CASE_INSENSITIVE);

    // @field（无运算符）
    private static final Pattern REF_PATTERN =
            Pattern.compile("^@([\\w.]+)$");

    private String fieldName;
    private CompareOperator operator;
    private String expectValue;

    /**
     * 解析单个条件表达式字符串。
     *
     * @return 解析成功时返回 {@link ConditionExpression}；
     *         表达式格式无法识别或运算符不合法时返回 {@code null}（不抛异常、不打日志）。
     */
    public static ConditionExpression parse(String expr) {
        if (expr == null) {
            return null;
        }
        expr = expr.trim();
        if (expr.isEmpty()) {
            return null;
        }

        // 1. 尝试匹配：field op value（支持 @field 或 field）
        Matcher m = COMPARISON_PATTERN.matcher(expr);
        if (m.matches()) {
            String field = m.group(1);
            String opStr = m.group(2);
            String val = m.group(3);

            CompareOperator op;
            try {
                op = CompareOperator.fromSymbol(opStr);
            } catch (IllegalArgumentException e) {
                // 运算符不合法 —— 静默返回 null
                return null;
            }

            return ConditionExpression.builder()
                    .fieldName(field)
                    .operator(op)
                    .expectValue(val)
                    .build();
        }

        // 2. 尝试匹配：field IS PRESENT / IS ABSENT
        m = IS_PATTERN.matcher(expr);
        if (m.matches()) {
            String field = m.group(1);
            String condition = m.group(2);
            CompareOperator op = "PRESENT".equalsIgnoreCase(condition)
                    ? CompareOperator.IS_PRESENT
                    : CompareOperator.IS_ABSENT;
            return ConditionExpression.builder()
                    .fieldName(field)
                    .operator(op)
                    .build();
        }

        // 3. 尝试匹配：@field（字段存在即满足）
        m = REF_PATTERN.matcher(expr);
        if (m.matches()) {
            String field = m.group(1);
            return ConditionExpression.builder()
                    .fieldName(field)
                    .operator(CompareOperator.REF)
                    .build();
        }

        // 所有模式均未匹配 —— 静默返回 null，由调用方决定如何处理
        return null;
    }

    public boolean evaluate(Map<String, Object> context) {
        if (this.fieldName == null || this.operator == null) {
            return false;
        }

        Object actual = context.get(this.fieldName);

        if (this.operator == CompareOperator.IS_PRESENT) {
            return !isAbsent(actual);
        }
        if (this.operator == CompareOperator.IS_ABSENT) {
            return isAbsent(actual);
        }
        if (this.operator == CompareOperator.REF) {
            return !isAbsent(actual);
        }

        if (this.expectValue == null) {
            return false;
        }

        try {
            double actualNum = Double.parseDouble(actual == null ? "0" : actual.toString());
            double expectedNum = Double.parseDouble(this.expectValue);
            return this.operator.apply(actualNum, expectedNum);
        } catch (NumberFormatException e) {
            if (this.operator == CompareOperator.EQ) {
                return Objects.equals(this.expectValue, actual == null ? null : actual.toString());
            } else if (this.operator == CompareOperator.NEQ) {
                return !Objects.equals(this.expectValue, actual == null ? null : actual.toString());
            } else {
                // 非数值字段使用数值比较运算符，无法降级 —— 静默返回 false
                return false;
            }
        }
    }

    private boolean isAbsent(Object value) {
        if (value == null) return true;
        if (value instanceof String) return ((String) value).trim().isEmpty();
        if (value instanceof Collection) return ((Collection<?>) value).isEmpty();
        if (value instanceof Map) return ((Map<?, ?>) value).isEmpty();
        return false;
    }
}