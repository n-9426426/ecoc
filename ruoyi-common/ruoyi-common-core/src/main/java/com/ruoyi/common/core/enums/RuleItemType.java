package com.ruoyi.common.core.enums;

import lombok.Getter;

/**
 * 规则类型枚举
 */
@Getter
public enum RuleItemType {

    // ===== 存在性 =====
    VALUE_IS_PRESENT,       // VALUE IS PRESENT
    VALUE_IS_ABSENT,        // VALUE IS ABSENT

    // ===== 枚举 =====
    VALUE_IN,               // VALUE IN [x, y, z]

    // ===== 正则 =====
    VALUE_REGEX,            // VALUE = /regex/

    // ===== 数值比较 =====
    VALUE_COMPARE,          // VALUE >= x, VALUE = x 等

    // ===== 条件触发 =====
    MANDATORY_IF,           // VALUE IS PRESENT IF @fieldName IS PRESENT/ABSENT（单字段引用）
    FORBIDDEN_IF,           // VALUE IS ABSENT IF @fieldName IS PRESENT/ABSENT（单字段引用）

    MANDATORY_IF_ANY,       // VALUE IS PRESENT IF ANY @field = value（任一满足则必填）
    MANDATORY_IF_ALL,       // VALUE IS PRESENT IF ALL @field = value（全部满足则必填）
    FORBIDDEN_IF_ALL,       // VALUE IS ABSENT IF ALL @field = value（全部满足则禁填）
    FORBIDDEN_IF_ANY,       // VALUE IS ABSENT IF ANY @field = value（任一满足则禁填）

    // ===== 聚合 =====
    COUNT_AGGREGATE,        // COUNT(list, cond) >= n
    SUM_AGGREGATE,          // SUM(list, field) >= VALUE

    // ===== 嵌套条件 =====
    NESTED_CONDITION,       // VALUE = /re/ IF ANY f=v IF ALL g IS PRESENT

    // ===== 范围约束（来自 rangeRule 字段）=====
    NUMERIC_RANGE,          // min=x; max=y
    LENGTH_RANGE,           // minLength=x; maxLength=y
    MIN_LENGTH,             // minLength=x
    MAX_LENGTH,             // maxLength=x
    TOTAL_DIGITS,           // totalDigits=n
    FRACTION_DIGITS,         // fractionDigits=n
    NULL
}