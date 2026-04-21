package com.ruoyi.common.core.parser;

import com.ruoyi.common.core.model.ValueRangeConstraint;
import lombok.extern.slf4j.Slf4j;

/**
 * rangeRule 字段解析器
 * 支持格式：
 *   min=0.0; max=9999.99
 *   minLength=1; maxLength=35
 *   maxLength=50
 *   totalDigits=18
 *   fractionDigits=6
 */
@Slf4j
public class ValueRangeParser {

    public static ValueRangeConstraint parse(String rangeStr) {
        if (rangeStr == null || rangeStr.trim().isEmpty()) return null;

        ValueRangeConstraint.ValueRangeConstraintBuilder builder = ValueRangeConstraint.builder();

        for (String part : rangeStr.split(";")) {
            String[] kv = part.trim().split("=");
            if (kv.length != 2) continue;
            String key = kv[0].trim();
            String val = kv[1].trim();

            try {
                switch (key) {
                    case "min":           builder.min(Double.parseDouble(val));           break;
                    case "max":           builder.max(Double.parseDouble(val));           break;
                    case "minLength":     builder.minLength(Integer.parseInt(val));       break;
                    case "maxLength":     builder.maxLength(Integer.parseInt(val));       break;
                    case "totalDigits":   builder.totalDigits(Integer.parseInt(val));     break;
                    case "fractionDigits":builder.fractionDigits(Integer.parseInt(val));  break;
                    default:
                        log.warn("未知 rangeRule 键: {}", key);
                }
            } catch (NumberFormatException e) {
                log.error("rangeRule 解析失败, key={}, val={}", key, val);
            }
        }

        return builder.build();
    }
}