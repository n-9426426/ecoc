package com.ruoyi.common.core.enums;

import lombok.Getter;

import java.util.HashMap;
import java.util.Map;

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
    NULL,
    STRUCTURE,
    VALUE_COUNT_REF,
    PARSE_ERROR;

    public static String getRuleType(RuleItemType ruleItemType) {
        Map<RuleItemType, String> map = new HashMap<>();
        //===== 存在性 =====
        map.put(RuleItemType.VALUE_IS_PRESENT,"VALUE IS PRESENT");
        map.put(RuleItemType.VALUE_IS_ABSENT,   "VALUE IS ABSENT");
        // ===== 枚举 =====
        map.put(RuleItemType.VALUE_IN,          "VALUE IN");
        // ===== 正则 =====
        map.put(RuleItemType.VALUE_REGEX,       "VALUE REGEX");
        // ===== 数值比较 =====
        map.put(RuleItemType.VALUE_COMPARE,     "VALUE COMPARE");
        map.put(RuleItemType.VALUE_COUNT_REF,    "VALUE COMPARE");
        // ===== 条件触发 - 单字段引用=====
        map.put(RuleItemType.MANDATORY_IF,      "VALUE IS PRESENT IF");
        map.put(RuleItemType.FORBIDDEN_IF,      "VALUE IS ABSENT IF");
        // ===== 条件触发 - 多值任一/全部 =====
        map.put(RuleItemType.MANDATORY_IF_ANY,  "VALUE IS PRESENT IF");
        map.put(RuleItemType.MANDATORY_IF_ALL,  "VALUE IS PRESENT IF");
        map.put(RuleItemType.FORBIDDEN_IF_ANY,  "VALUE IS ABSENT IF");
        map.put(RuleItemType.FORBIDDEN_IF_ALL,  "VALUE IS ABSENT IF");
        // ===== 聚合 =====
        map.put(RuleItemType.COUNT_AGGREGATE,   "AGGREGATE");
        map.put(RuleItemType.SUM_AGGREGATE,     "AGGREGATE");
        // ===== 嵌套条件 =====
        map.put(RuleItemType.NESTED_CONDITION,  "NESTED CONDITION");
        // ===== 范围约束 =====
        map.put(RuleItemType.NUMERIC_RANGE,     "VALUE RANGE");
        map.put(RuleItemType.LENGTH_RANGE,      "VALUE RANGE");
        map.put(RuleItemType.MIN_LENGTH,        "VALUE RANGE");
        map.put(RuleItemType.MAX_LENGTH,        "VALUE RANGE");
        map.put(RuleItemType.TOTAL_DIGITS,      "VALUE RANGE");
        map.put(RuleItemType.FRACTION_DIGITS,   "VALUE RANGE");
        // 结构校验
        map.put(RuleItemType.STRUCTURE, "STRUCTURE");
        // ===== 空值 =====
        map.put(RuleItemType.NULL,              null);
        // 解析错误
        map.put(RuleItemType.PARSE_ERROR, "PARSE_ERROR");

        return map.get(ruleItemType);
    }
}