package com.ruoyi.common.core.enums;

import lombok.Getter;

/**
 * 比较运算符枚举
 */
@Getter
public enum CompareOperator {

    GT(">"),
    GTE(">="),
    LT("<"),
    LTE("<="),
    EQ("="),
    NEQ("!=");

    private final String symbol;

    CompareOperator(String symbol) {
        this.symbol = symbol;
    }

    public String getSymbol() {
        return symbol;
    }

    public static CompareOperator fromSymbol(String symbol) {
        for (CompareOperator op : values()) {
            if (op.symbol.equals(symbol.trim())) {
                return op;
            }
        }
        throw new IllegalArgumentException("未知运算符: " + symbol);
    }

    /**
     * 对两个 double 值执行比较
     */
    public boolean apply(double actual, double expected) {
        switch (this) {
            case GT:  return actual > expected;
            case GTE: return actual >= expected;
            case LT:  return actual < expected;
            case LTE: return actual <= expected;
            case EQ:  return actual == expected;
            case NEQ: return actual != expected;
            default:  throw new IllegalStateException("未处理的运算符: " + this);
        }
    }

    /**
     * 对两个字符串执行比较（仅支持 EQ / NEQ）
     */
    public boolean applyString(String actual, String expected) {
        switch (this) {
            case EQ:  return actual != null && actual.equals(expected);
            case NEQ: return actual == null || !actual.equals(expected);
            default:  throw new IllegalArgumentException("字符串比较不支持运算符: " + this);
        }
    }
}