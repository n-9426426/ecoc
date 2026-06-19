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
            String val   = m.group(3).replaceAll("^['\"]|['\"]$", "");

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
        String actualStr = actual == null ? "" : actual.toString();

        // ★ 修复：actual 为多值字段（含 | 或 ; 分隔符，如 EnergySource="10|95|95|95"）时，
        //   整串与单个字面量直接做数值/字符串比较在语义上必然失真：
        //     - 数值解析：Double.parseDouble("10|95|95|95") 直接抛 NumberFormatException；
        //     - 字符串相等：拼接后的整串几乎不可能字面等于单个枚举值，导致 EQ 恒为 false、
        //       NEQ 恒为 true，与"该值是否出现在多值字段中"的本意完全不符
        //       （如 "ANY EnergySource != '95'" 对任何混动车都会恒成立，
        //       即便其 EnergySource 中确实包含 95）。
        //   这里按集合语义处理：
        //     EQ  → 任一分段等于 expectValue 即视为匹配（存在性，"95" ∈ {10,95,95,95}）；
        //     NEQ → 所有分段都不等于 expectValue 才视为不匹配（EQ 的逻辑取反，
        //           "95" ∉ {10,95,95,95} 才为 true）。
        //   其余运算符（>、<、>=、<=）暂不做多值拆分，维持原有按整串数值解析失败即按
        //   运算符类型默认处理（非 EQ/NEQ 一律 false）的行为，避免引入未经验证的新语义。
        if (isMultiValue(actualStr)
                && (this.operator == CompareOperator.EQ || this.operator == CompareOperator.NEQ)) {
            boolean anyMatch = false;
            for (String part : splitMultiValue(actualStr)) {
                if (Objects.equals(this.expectValue, part == null ? null : part.trim())) {
                    anyMatch = true;
                    break;
                }
            }
            return this.operator == CompareOperator.EQ ? anyMatch : !anyMatch;
        }

        try {
            double actualNum   = Double.parseDouble(actualStr.isEmpty() ? "0" : actualStr);
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

    /**
     * 判断字段值是否为多值字段（含 | 或 ; 分隔符）。
     * 与 {@code com.ruoyi.common.core.parser.FinalRuleParser#isMultiValue} 逻辑保持一致，
     * 此处独立实现以避免 model 包反向依赖 parser 包。
     */
    private static boolean isMultiValue(String value) {
        return value != null && (value.contains("|") || value.contains(";"));
    }

    /**
     * 按 | 或 ; 拆分多值字段。
     * 与 {@code com.ruoyi.common.core.parser.FinalRuleParser#splitMultiValue} 逻辑保持一致，
     * 此处独立实现以避免 model 包反向依赖 parser 包。
     */
    private static String[] splitMultiValue(String value) {
        if (value == null || value.isEmpty()) {
            return new String[]{value};
        }
        if (value.contains("|")) {
            return value.split("\\|", -1);
        }
        if (value.contains(";")) {
            return value.split(";", -1);
        }
        return new String[]{value};
    }

    private boolean isAbsent(Object value) {
        if (value == null) return true;
        if (value instanceof String) return ((String) value).trim().isEmpty();
        if (value instanceof Collection) return ((Collection<?>) value).isEmpty();
        if (value instanceof Map) return ((Map<?, ?>) value).isEmpty();
        return false;
    }
}