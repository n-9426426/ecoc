package com.ruoyi.common.core.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 条件链
 * 支持 IF ANY（任意满足）和 IF ALL（全部满足）两种模式。
 *
 * <p>解析失败的单个条件表达式会被静默跳过（不抛异常、不打日志），
 * 以避免单条子条件的格式错误导致整条规则失效。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConditionChain {

    public enum Mode { ANY, ALL }

    private Mode mode;
    private List<ConditionExpression> expressions;

    /**
     * 解析条件字符串（ANY 模式）
     * 输入示例： "vehicleType = 1, engineNo IS ABSENT"
     */
    public static ConditionChain parseAny(String condStr) {
        return parse(condStr, Mode.ANY);
    }

    /**
     * 解析条件字符串（ALL 模式）
     * 输入示例： "engineNo IS ABSENT, motorNo IS ABSENT"
     */
    public static ConditionChain parseAll(String condStr) {
        return parse(condStr, Mode.ALL);
    }

    /**
     * 内部解析方法。
     * 单个条件表达式解析失败时静默跳过，不抛异常、不打日志。
     */
    private static ConditionChain parse(String condStr, Mode mode) {
        List<ConditionExpression> list = new ArrayList<>();
        if (condStr == null || condStr.trim().isEmpty()) {
            return ConditionChain.builder().mode(mode).expressions(list).build();
        }

        for (String part : condStr.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            // parse() 内部返回 null 表示解析失败，直接跳过，不做任何日志
            ConditionExpression expr = ConditionExpression.parse(trimmed);
            if (expr != null) {
                list.add(expr);
            }
        }

        return ConditionChain.builder()
                .mode(mode)
                .expressions(list)
                .build();
    }

    /**
     * 执行条件链判断
     * - ANY：任一条件为 true 则返回 true
     * - ALL：全部条件为 true 才返回 true
     */
    public boolean evaluate(Map<String, Object> context) {
        if (expressions == null || expressions.isEmpty()) {
            return false;
        }
        if (mode == Mode.ANY) {
            return expressions.stream().anyMatch(e -> e.evaluate(context));
        } else {
            return expressions.stream().allMatch(e -> e.evaluate(context));
        }
    }
}