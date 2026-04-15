package com.ruoyi.common.core.model;

import com.ruoyi.common.core.enums.CompareOperator;
import com.ruoyi.common.core.enums.RuleItemType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 规则项实体类
 *表示一条解析后的校验规则
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RuleItem {

    /**
     * 规则编号，例如 R1、R2a 等
     */
    private String ruleId;

    /**
     * 规则类型（枚举）
     */
    private RuleItemType type;

    /**
     * 目标字段名（用于 VALUE_IS_PRESENT_IF、FIELD_IS_ABSENT 等）
     * 例如: HybridVehicle、EngineCapacity
     */
    private String targetFieldName;

    /**
     * 字段数据类型（来自 dict_value，如 String、Long、Double 等）
     */
    private String dataType;

    /**
     * 比较运算符（用于 VALUE_COMPARE）
     * 例如: =、!=、>=、<=
     */
    private CompareOperator compareOperator;

    /**
     * 比较值 / 规则内容（根据 type 存放不同对象）
     * - VALUE_COMPARE      → String（比较的目标值）
     * - VALUE_IS_PRESENT_IF → String（"ABSENT" 或 "PRESENT"）
     * - FORBIDDEN_IF       → String（条件表达式字符串）
     * - MANDATORY_IF       → String（条件表达式字符串）
     * - FIELD_IS_ABSENT    → String（"ABSENT" 或 "PRESENT"）
     * - FIELD_IS_NULL      → Boolean（true=IS NULL, false=IS NOT NULL）
     * - ANY_CONDITION      → ConditionExpression
     * - ALL_CONDITION      → ConditionExpression
     * - COUNT_AGGREGATE    → AggregateFunction
     * - SUM_AGGREGATE      → AggregateFunction
     * - NESTED_CONDITION   → NestedConditionRule
     */
    private Object compareValue;

    /**
     * 正则表达式（用于 VALUE_REGEX）
     * 例如: ^(AA|AB|AC)$
     */
    private String regexPattern;

    /**
     *枚举值列表（用于 VALUE_IN）
     * 例如: ["AA", "AB", "AC"]
     */
    private List<String> enumValues;

    /**
     * 范围最小值（用于 RANGE）
     * 例如: RANGE 1 TO 9→ rangeMin = 1.0
     */
    private Double rangeMin;

    /**
     * 范围最大值（用于 RANGE）
     * 例如: RANGE 1 TO 9 → rangeMax = 9.0
     */
    private Double rangeMax;

    /**
     * 原始规则字符串（用于日志和错误提示）
     */
    private String rawRule;

    /**
     * 适用的车型列表（如 ["M1x", "N1x"]，用于规则适用性判断）
     * 如果为空，则适用于所有车型
     */
    private List<String> applicableCategories;

    /**
     * 英文错误提示
     */
    private String errorMessageEn;

    /**
     * 中文错误提示
     */
    private String errorMessageZh;

    /** 字符串最小长度（LENGTH_RANGE / MIN_LENGTH 类型使用）*/
    private Integer minLength;

    /** 字符串最大长度（LENGTH_RANGE / MAX_LENGTH 类型使用）*/
    private Integer maxLength;

    /** 总有效数字位数（TOTAL_DIGITS 类型使用）*/
    private Integer totalDigits;

    /** 小数位数（FRACTION_DIGITS 类型使用）*/
    private Integer fractionDigits;

    /** 是否来自"值范围"列（区分规则来源）*/
    @Builder.Default
    private boolean fromRangeColumn = false;
}