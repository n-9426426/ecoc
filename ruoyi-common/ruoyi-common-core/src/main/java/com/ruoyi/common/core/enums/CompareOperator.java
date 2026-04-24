package com.ruoyi.common.core.enums;

import lombok.Getter;

/**
 * 比较运算符枚举
 * 支持：数值比较（=, !=, >, <, >=, <=）、存在性判断（IS PRESENT, IS ABSENT）
 */
@Getter
public enum CompareOperator {

    EQ("="),
    NEQ("!="),
    LT("<"),
    GT(">"),
    LTE("<="),
    GTE(">="),

    IS_PRESENT("IS PRESENT"),
    IS_ABSENT("IS ABSENT"),
    REF("REF"),;

    private final String symbol;

    CompareOperator(String symbol) {
        this.symbol = symbol;
    }

    public static CompareOperator fromSymbol(String symbol) {
        String normalized = symbol.trim().toUpperCase();
        for (CompareOperator op : values()) {
            if (op.symbol.equalsIgnoreCase(normalized) ||
                    (op == REF && "REF".equalsIgnoreCase(normalized))) {
                return op;
            }
        }
        throw new IllegalArgumentException("未知运算符: " + symbol);
    }

    /**
     * 对两个 double 值执行比较（仅用于数值比较）
     */
    public boolean apply(double actual, double expected) {
        switch (this) {
            case GT:  return actual > expected;
            case GTE: return actual >= expected;
            case LT:  return actual < expected;
            case LTE: return actual <= expected;
            case EQ:  return actual == expected;
            case NEQ: return actual != expected;
            default:
                // IS_PRESENT / IS_ABSENT 不应进入此方法
                throw new IllegalArgumentException("不支持的运算符用于数值比较: " + this);
        }
    }

    /**
     * 对两个字符串执行比较（仅支持 EQ / NEQ）
     */
    public boolean applyString(String actual, String expected) {
        switch (this) {
            case EQ:
                return actual != null && actual.equals(expected);
            case NEQ:
                return actual == null || !actual.equals(expected);
            default:
                throw new IllegalArgumentException("字符串比较不支持运算符: " + this);
        }
    }
}