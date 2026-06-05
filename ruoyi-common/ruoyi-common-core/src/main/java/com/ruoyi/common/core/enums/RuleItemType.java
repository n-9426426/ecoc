package com.ruoyi.common.core.enums;

/**
 * 规则条目类型枚举
 *
 * <p>新增类型说明：
 * <ul>
 *   <li>{@link #CONDITIONAL_COUNT_AGGREGATE} —— 带前置字段条件的 COUNT WITHIN 存在性校验
 *       <br>语法：{@code VALUE IS PRESENT|ABSENT IF [@field IS PRESENT|ABSENT AND ...] COUNT(listField WITHIN [vals]) op N}
 *       <br>用途：ConsolidatedMaximumNetPowerElectric / ConsolidatedMaximum30MinutesPower 等需要
 *       同时判断"列表聚合计数"和"普通字段存在性"的复合条件场景。
 *   </li>
 * </ul>
 */
public enum RuleItemType {

    NULL,

    // ===== 存在性 =====
    VALUE_IS_PRESENT,
    VALUE_IS_ABSENT,

    // ===== 条件必填 / 条件禁填 =====
    MANDATORY_IF,
    MANDATORY_IF_ANY,
    MANDATORY_IF_ALL,
    FORBIDDEN_IF,
    FORBIDDEN_IF_ANY,
    FORBIDDEN_IF_ALL,

    // ===== 嵌套条件（IF ANY ... IF ALL ...）=====
    NESTED_CONDITION,

    // ===== 枚举 / 正则 / 数值比较 =====
    VALUE_IN,
    VALUE_REGEX,
    VALUE_COMPARE,
    VALUE_FIELD_COMPARE,

    // ===== 条件型规则 =====
    CONDITIONAL_REGEX,
    CONDITIONAL_VALUE_COMPARE,
    CONDITIONAL_FIELD_COMPARE,

    // ===== 聚合函数 =====
    COUNT_AGGREGATE,
    SUM_AGGREGATE,
    COUNT_AS_VALUE,
    LIST_COUNT,

    /**
     * 带前置字段条件的 COUNT WITHIN 存在性校验（新增）
     *
     * <p>语法格式（两种，均支持）：
     * <pre>
     *   // 格式 A：前置字段条件 + COUNT（字段条件在 COUNT 之前，用 AND 连接）
     *   VALUE IS PRESENT IF @field IS ABSENT AND COUNT(listField WITHIN ['val']) op N
     *   VALUE IS ABSENT  IF @field IS PRESENT AND COUNT(listField WITHIN ['val']) op N
     *
     *   // 格式 B：纯 COUNT 条件（无前置字段条件）
     *   VALUE IS PRESENT IF COUNT(listField WITHIN ['val']) op N
     *   VALUE IS ABSENT  IF COUNT(listField WITHIN ['val']) op N
     * </pre>
     *
     * <p>RuleItem 字段映射：
     * <ul>
     *   <li>{@code operator}         — "IS_PRESENT" 或 "IS_ABSENT"（VALUE 的存在性要求）</li>
     *   <li>{@code aggregateFunction.listField}   — 列表字段名（如 EnergySource）</li>
     *   <li>{@code aggregateFunction.enumValues}  — WITHIN 值集合（如 ["95"]）</li>
     *   <li>{@code aggregateFunction.operator}    — COUNT 的比较运算符（>, <, =, >=, <=, !=）</li>
     *   <li>{@code aggregateFunction.threshold}   — COUNT 的阈值（如 2）</li>
     *   <li>{@code conditionChain}   — 前置字段条件链（可为 null，表示无前置条件）</li>
     * </ul>
     *
     * <p>典型用例（Sortnr=48/49 互为镜像）：
     * <pre>
     *   // ConsolidatedMaximumNetPowerElectric (R49)
     *   R1:VALUE IS PRESENT IF @ConsolidatedMaximum30MinutesPower IS ABSENT AND COUNT(EnergySource WITHIN ['95']) > 1
     *   R2:VALUE IS ABSENT  IF COUNT(EnergySource WITHIN ['95']) < 2
     * </pre>
     */
    CONDITIONAL_COUNT_AGGREGATE,

    // ===== 列表操作 =====
    VALUE_IS_NUMBERED,
    VALUE_IN_LIST_FIELD,
    LIST_UNIQUE,

    // ===== 范围校验（来自 rangeRule）=====
    NUMERIC_RANGE,
    LENGTH_RANGE,
    MAX_LENGTH,
    MIN_LENGTH,
    TOTAL_DIGITS,
    FRACTION_DIGITS,

    // ===== 结构校验 =====
    STRUCTURE,
    // ===== 解析错误占位 =====
    PARSE_ERROR;

    /**
     * 获取规则类型的中文标签（用于违规报告 ruleTypeLabel 字段）
     */
    public static String getRuleType(RuleItemType type) {
        if (type == null) return "未知";
        switch (type) {
            case VALUE_IS_PRESENT:              return "必填";
            case VALUE_IS_ABSENT:               return "禁填";
            case MANDATORY_IF:                  return "条件必填（单字段引用）";
            case MANDATORY_IF_ANY:              return "条件必填（任一条件）";
            case MANDATORY_IF_ALL:              return "条件必填（全部条件）";
            case FORBIDDEN_IF:                  return "条件禁填（单字段引用）";
            case FORBIDDEN_IF_ANY:              return "条件禁填（任一条件）";
            case FORBIDDEN_IF_ALL:              return "条件禁填（全部条件）";
            case NESTED_CONDITION:              return "嵌套条件";
            case VALUE_IN:                      return "枚举校验";
            case VALUE_REGEX:                   return "正则校验";
            case VALUE_COMPARE:                 return "数值比较";
            case VALUE_FIELD_COMPARE:           return "跨字段比较";
            case CONDITIONAL_REGEX:             return "条件正则";
            case CONDITIONAL_VALUE_COMPARE:     return "条件数值比较";
            case CONDITIONAL_FIELD_COMPARE:     return "条件跨字段比较";
            case COUNT_AGGREGATE:               return "COUNT聚合校验";
            case SUM_AGGREGATE:                 return "SUM聚合校验";
            case COUNT_AS_VALUE:                return "COUNT赋值校验";
            case LIST_COUNT:                    return "列表COUNT校验";
            case CONDITIONAL_COUNT_AGGREGATE:   return "条件COUNT存在性校验";
            case VALUE_IS_NUMBERED:             return "列表连续编号校验";
            case VALUE_IN_LIST_FIELD:           return "列表成员校验";
            case LIST_UNIQUE:                   return "列表唯一性校验";
            case NUMERIC_RANGE:                 return "数值范围校验";
            case LENGTH_RANGE:                  return "字符串长度范围校验";
            case MAX_LENGTH:                    return "最大长度校验";
            case MIN_LENGTH:                    return "最小长度校验";
            case TOTAL_DIGITS:                  return "总位数校验";
            case FRACTION_DIGITS:               return "小数位数校验";
            case STRUCTURE:                     return "结构校验";
            case PARSE_ERROR:                   return "规则解析错误";
            default:                            return "未知类型";
        }
    }
}