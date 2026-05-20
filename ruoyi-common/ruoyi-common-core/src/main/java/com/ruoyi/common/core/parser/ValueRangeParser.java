package com.ruoyi.common.core.parser;

import com.ruoyi.common.core.model.ValueRangeConstraint;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

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

    private static final Set<String> KNOWN_KEYS = new HashSet<>(Arrays.asList(
            "min", "max", "minLength", "maxLength", "totalDigits", "fractionDigits"
    ));

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

    /**
     * 解析 rangeRule，返回无法解析的键名列表（格式如 ["min=abc", "totalDigits=xyz"]）
     * 列表为空表示全部解析成功
     */
    public static List<String> parseErrors(String rangeStr) {
        List<String> errors = new ArrayList<>();
        if (rangeStr == null || rangeStr.trim().isEmpty()) return errors;

        for (String part : rangeStr.split(";")) {
            String[] kv = part.trim().split("=");
            if (kv.length != 2) {
                errors.add(part.trim() + "(格式错误)");
                continue;
            }
            String key = kv[0].trim();
            String val = kv[1].trim();

            if (!KNOWN_KEYS.contains(key)) {
                errors.add(key + "='" + val + "'(未知键)");
                continue;
            }

            try {
                switch (key) {
                    case "min":
                    case "max":
                        Double.parseDouble(val);
                        break;
                    default:
                        Integer.parseInt(val);
                }
            } catch (NumberFormatException e) {
                errors.add(key + "='" + val + "'(值非法)");
            }
        }

        return errors;
    }
}