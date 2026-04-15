package com.ruoyi.common.core.parser;

import com.ruoyi.common.core.enums.RuleItemType;
import com.ruoyi.common.core.model.RuleItem;
import com.ruoyi.common.core.model.ValueRangeConstraint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 值范围解析器
 * 解析 CSV "值范围"
 *   min=0.0; max=999999
 *   maxLength=2
 *   minLength=1; maxLength=35
 *   totalDigits=5;
 *   totalDigits=9; fractionDigits=5; min=0.0; max=9999.99999
 */
@Slf4j
public class ValueRangeParser {

    /**
     * 解析值范围字符串为ValueRangeConstraint
     */
    public static ValueRangeConstraint parse(String rangeStr) {
        if (!StringUtils.hasText(rangeStr)) {
            return null;
        }

        ValueRangeConstraint constraint = new ValueRangeConstraint();

        // 按分号分割，兼容带空格：min=0.0; max=999999
        String[] parts = rangeStr.split(";");
        for (String part : parts) {
            part = part.trim();
            if (part.isEmpty()) continue;

            if (part.startsWith("min=")) {
                parseDouble(part.substring(4).trim(), "min")
                        .ifPresent(constraint::setNumericMin);

            } else if (part.startsWith("max=")) {
                parseDouble(part.substring(4).trim(), "max")
                        .ifPresent(constraint::setNumericMax);

            } else if (part.startsWith("minLength=")) {
                parseInt(part.substring(10).trim(), "minLength")
                        .ifPresent(constraint::setMinLength);

            } else if (part.startsWith("maxLength=")) {
                parseInt(part.substring(10).trim(), "maxLength")
                        .ifPresent(constraint::setMaxLength);

            } else if (part.startsWith("totalDigits=")) {
                parseInt(part.substring(12).trim(), "totalDigits")
                        .ifPresent(constraint::setTotalDigits);

            } else if (part.startsWith("fractionDigits=")) {
                parseInt(part.substring(15).trim(), "fractionDigits")
                        .ifPresent(constraint::setFractionDigits);

            } else {
                log.debug("未识别的值范围片段: [{}]", part);
            }
        }

        return constraint.isEmpty() ? null : constraint;
    }

    /**
     * 将ValueRangeConstraint 转换为 RuleItem 列表
     *每种约束独立生成一个 RuleItem，便于执行器逐项校验
     */
    public static List<RuleItem> toRuleItems(ValueRangeConstraint constraint) {
        List<RuleItem> items = new ArrayList<>();
        if (constraint == null) return items;

        // 1. 数值范围
        if (constraint.hasNumericRange()) {
            items.add(RuleItem.builder()
                    .type(RuleItemType.NUMERIC_RANGE)
                    .rangeMin(constraint.getNumericMin())
                    .rangeMax(constraint.getNumericMax())
                    .fromRangeColumn(true)
                    .build());
        }

        // 2. totalDigits
        if (constraint.hasTotalDigits()) {
            items.add(RuleItem.builder()
                    .type(RuleItemType.TOTAL_DIGITS)
                    .totalDigits(constraint.getTotalDigits())
                    .fromRangeColumn(true)
                    .build());
        }

        // 3. fractionDigits
        if (constraint.hasFractionDigits()) {
            items.add(RuleItem.builder()
                    .type(RuleItemType.FRACTION_DIGITS)
                    .fractionDigits(constraint.getFractionDigits())
                    .fromRangeColumn(true)
                    .build());
        }

        // 4. 长度约束（minLength / maxLength）
        if (constraint.hasLengthConstraint()) {
            if (constraint.getMinLength() != null && constraint.getMaxLength() != null) {
                // 同时有min 和 max
                items.add(RuleItem.builder()
                        .type(RuleItemType.LENGTH_RANGE)
                        .minLength(constraint.getMinLength())
                        .maxLength(constraint.getMaxLength())
                        .fromRangeColumn(true)
                        .build());
            } else if (constraint.getMaxLength() != null) {
                items.add(RuleItem.builder()
                        .type(RuleItemType.MAX_LENGTH)
                        .maxLength(constraint.getMaxLength())
                        .fromRangeColumn(true)
                        .build());
            } else {
                items.add(RuleItem.builder()
                        .type(RuleItemType.MIN_LENGTH)
                        .minLength(constraint.getMinLength())
                        .fromRangeColumn(true)
                        .build());
            }
        }

        return items;
    }

    // ==================== 私有工具方法 ====================

    private static java.util.Optional<Double> parseDouble(String val, String key) {
        try {
            return java.util.Optional.of(Double.parseDouble(val));
        } catch (NumberFormatException e) {
            log.warn("值范围 [{}] 解析失败，原始值: [{}]", key, val);
            return java.util.Optional.empty();
        }
    }

    private static java.util.Optional<Integer> parseInt(String val, String key) {
        try {
            return java.util.Optional.of(Integer.parseInt(val));
        } catch (NumberFormatException e) {
            log.warn("值范围 [{}] 解析失败，原始值: [{}]", key, val);
            return java.util.Optional.empty();
        }
    }
}