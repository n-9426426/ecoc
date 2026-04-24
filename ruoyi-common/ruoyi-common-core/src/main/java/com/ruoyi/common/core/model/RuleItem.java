package com.ruoyi.common.core.model;

import com.ruoyi.common.core.enums.RuleItemType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 单条规则项
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RuleItem {

    /** 规则编号，如 R1、R2a */
    private String ruleId;

    /** 规则类型 */
    private RuleItemType type;

    /** 运算符（字符串形式，如 ">="、"IS_PRESENT"） */
    private String operator;

    /** 比较目标值（字符串形式） */
    private String compareValue;

    /** 数值范围最小值（NUMERIC_RANGE 用） */
    private Double rangeMin;

    /** 数值范围最大值（NUMERIC_RANGE 用） */
    private Double rangeMax;

    private Integer minLength;

    private Integer maxLength;

    private Integer totalDigits;

    private Integer fractionDigits;

    /** 枚举值列表（VALUE_IN 用） */
    private List<String> enumValues;

    /** 正则表达式（VALUE_REGEX 用） */
    private String regexPattern;

    /** 条件链（MANDATORY_IF / FORBIDDEN_IF 用） */
    private ConditionChain conditionChain;

    /** 聚合函数（COUNT_AGGREGATE / SUM_AGGREGATE 用） */
    private AggregateFunction aggregateFunction;

    /** 嵌套条件规则（NESTED_CONDITION 用） */
    private NestedConditionRule nestedCondition;

    /** 原始规则字符串（用于日志） */
    private String rawRule;

    /** 自定义英文错误提示 */
    private String errorMessageEn;

    /** 自定义中文错误提示 */
    private String errorMessageZh;

    /** @字段名引用（不含@符号） */
    private String refFieldName;

    /** 引用字段的期望状态：PRESENT 或 ABSENT */
    private String refFieldCondition;
}