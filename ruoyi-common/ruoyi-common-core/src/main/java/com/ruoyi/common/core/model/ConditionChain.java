package com.ruoyi.common.core.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 条件链
 * 支持 IF ANY（任意满足）和 IF ALL（全部满足）两种模式
 */
@Slf4j
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConditionChain {

    public enum Mode { ANY, ALL }

    private Mode mode;
    private List<ConditionExpression> expressions;

    /**
     * 解析条件字符串
     * 输入示例：
     *   "vehicleType = 1, vehicleType = 2"         → ANY 模式
     *   "engineNo IS ABSENT, motorNo IS ABSENT"    → ALL 模式
     */
    public static ConditionChain parseAny(String condStr) {
        return parse(condStr, Mode.ANY);
    }

    public static ConditionChain parseAll(String condStr) {
        return parse(condStr, Mode.ALL);
    }

    private static ConditionChain parse(String condStr, Mode mode) {
        List<ConditionExpression> list = new ArrayList<>();
        if (condStr == null || condStr.trim().isEmpty()) {
            return ConditionChain.builder().mode(mode).expressions(list).build();
        }
        for (String part : condStr.split(",")) {
            ConditionExpression expr = ConditionExpression.parse(part.trim());
            if (expr != null) list.add(expr);
        }
        return ConditionChain.builder().mode(mode).expressions(list).build();
    }

    /**
     * 执行条件链判断
     */
    public boolean evaluate(Map<String, Object> context) {
        if (expressions == null || expressions.isEmpty()) return false;
        if (mode == Mode.ANY) {
            return expressions.stream().anyMatch(e -> e.evaluate(context));
        } else {
            return expressions.stream().allMatch(e -> e.evaluate(context));
        }
    }
}