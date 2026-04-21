package com.ruoyi.common.core.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 单个条件表达式
 * 支持：
 *   field = value
 *   field IS PRESENT
 *   field IS ABSENT
 *   @field（字段有值即满足）
 */
@Slf4j
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConditionExpression {

    // field = value
    private static final Pattern EQ_PATTERN =
            Pattern.compile("^([\\w.]+)\\s*=\\s*(.+)$");
    // field IS PRESENT / IS ABSENT
    private static final Pattern IS_PATTERN =
            Pattern.compile("^([\\w.]+)\\s+IS\\s+(PRESENT|ABSENT)$", Pattern.CASE_INSENSITIVE);
    // @field
    private static final Pattern REF_PATTERN =
            Pattern.compile("^@([\\w.]+)$");

    private String fieldName;
    private String operator;   // "=", "IS_PRESENT", "IS_ABSENT", "REF"
    private String expectValue;

    public static ConditionExpression parse(String expr) {
        expr = expr.trim();

        Matcher m = IS_PATTERN.matcher(expr);
        if (m.matches()) {
            return ConditionExpression.builder()
                    .fieldName(m.group(1))
                    .operator("IS_" + m.group(2).toUpperCase())
                    .build();
        }

        m = EQ_PATTERN.matcher(expr);
        if (m.matches()) {
            return ConditionExpression.builder()
                    .fieldName(m.group(1).trim())
                    .operator("=")
                    .expectValue(m.group(2).trim())
                    .build();
        }

        m = REF_PATTERN.matcher(expr);
        if (m.matches()) {
            return ConditionExpression.builder()
                    .fieldName(m.group(1))
                    .operator("REF")
                    .build();
        }

        log.warn("无法解析条件表达式: {}", expr);
        return null;
    }

    public boolean evaluate(Map<String, Object> context) {
        if (this.fieldName == null || this.operator == null) return false;
        Object val = context.get(this.fieldName);

        switch (this.operator) {
            case "IS_PRESENT":
                return !isAbsent(val);
            case "IS_ABSENT":
                return isAbsent(val);
            case "=":
                return this.expectValue != null
                        && this.expectValue.equals(val == null ? null : val.toString());
            case "REF":
                return !isAbsent(val);
            default:
                log.warn("未知条件运算符: {}", this.operator);
                return false;
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