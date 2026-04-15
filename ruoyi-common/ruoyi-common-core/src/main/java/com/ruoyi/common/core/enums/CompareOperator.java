package com.ruoyi.common.core.enums;

import lombok.Getter;

/**
 * 比较运算符枚举
 */
@Getter
public enum CompareOperator {
    EQ("="),
    NEQ("!="),
    LT("<"),
    GT(">"),
    LTE("<="),
    GTE(">=");

    private final String symbol;

    CompareOperator(String symbol) {
        this.symbol = symbol;
    }

    /**
     * 根据符号获取枚举
     */
    public static CompareOperator fromSymbol(String symbol) {
        for (CompareOperator op : values()) {
            if (op.symbol.equals(symbol)) {
                return op;
            }
        }
        throw new IllegalArgumentException("未知运算符: " + symbol);
    }
}