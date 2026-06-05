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

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConditionExpression {

    // ★ 新增：@fieldA op @fieldB（跨字段比较，必须在 COMPARISON_PATTERN 之前匹配）
    private static final Pattern FIELD_EQ_FIELD_PATTERN =
            Pattern.compile("^@?([\\w.]+)\\s+([=<>!]+)\\s+@([\\w.]+)$");

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

    public static ConditionExpression parse(String expr) {
        if (expr == null) {
            return null;
        }
        expr = expr.trim();
        if (expr.isEmpty()) {
            return null;
        }

        // 0. ★ 跨字段比较：@fieldA op @fieldB（必须在 COMPARISON_PATTERN 之前）
        Matcher m = FIELD_EQ_FIELD_PATTERN.matcher(expr);
        if (m.matches()) {
            String field    = m.group(1);
            String opStr    = m.group(2);
            String refField = m.group(3);

            CompareOperator op;
            try {
                op = CompareOperator.fromSymbol(opStr);
            } catch (IllegalArgumentException e) {
                return null;
            }

            return ConditionExpression.builder()
                    .fieldName(field)
                    .operator(op)
                    .expectValue("@" + refField)  // @ 前缀标记字段引用
                    .build();
        }

        // 1. field op value
        m = COMPARISON_PATTERN.matcher(expr);
        if (m.matches()) {
            String field = m.group(1);
            String opStr = m.group(2);
            String val   = m.group(3);

            CompareOperator op;
            try {
                op = CompareOperator.fromSymbol(opStr);
            } catch (IllegalArgumentException e) {
                return null;
            }

            return ConditionExpression.builder()
                    .fieldName(field)
                    .operator(op)
                    .expectValue(val)
                    .build();
        }

        // 2. field IS PRESENT / IS ABSENT
        m = IS_PATTERN.matcher(expr);
        if (m.matches()) {
            String field     = m.group(1);
            String condition = m.group(2);
            CompareOperator op = "PRESENT".equalsIgnoreCase(condition)
                    ? CompareOperator.IS_PRESENT
                    : CompareOperator.IS_ABSENT;
            return ConditionExpression.builder()
                    .fieldName(field)
                    .operator(op)
                    .build();
        }

        // 3. @field（字段存在即满足）
        m = REF_PATTERN.matcher(expr);
        if (m.matches()) {
            return ConditionExpression.builder()
                    .fieldName(m.group(1))
                    .operator(CompareOperator.REF)
                    .build();
        }

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

        // ★ 跨字段比较：expectValue 以 @ 开头表示引用另一个字段的值
        if (this.expectValue.startsWith("@")) {
            String refFieldName = this.expectValue.substring(1);
            Object refActual    = context.get(refFieldName);

            try {
                double actualNum = Double.parseDouble(actual == null ? "0" : actual.toString());
                double refNum    = Double.parseDouble(refActual == null ? "0" : refActual.toString());
                return this.operator.apply(actualNum, refNum);
            } catch (NumberFormatException e) {
                String actualStr = actual == null ? "" : actual.toString();
                String refStr    = refActual == null ? "" : refActual.toString();
                if (this.operator == CompareOperator.EQ)  return actualStr.equals(refStr);
                if (this.operator == CompareOperator.NEQ) return !actualStr.equals(refStr);
                return false;
            }
        }

        // 原有字面量比较逻辑
        try {
            double actualNum   = Double.parseDouble(actual == null ? "0" : actual.toString());
            double expectedNum = Double.parseDouble(this.expectValue);
            return this.operator.apply(actualNum, expectedNum);
        } catch (NumberFormatException e) {
            if (this.operator == CompareOperator.EQ) {
                return Objects.equals(this.expectValue, actual == null ? null : actual.toString());
            } else if (this.operator == CompareOperator.NEQ) {
                return !Objects.equals(this.expectValue, actual == null ? null : actual.toString());
            } else {
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