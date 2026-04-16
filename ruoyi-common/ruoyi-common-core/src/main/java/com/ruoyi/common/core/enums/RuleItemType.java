package com.ruoyi.common.core.enums;

import lombok.Getter;

/**
 * 规则项类型
 */
@Getter
public enum RuleItemType {
    // 基础规则
    VALUE_IS_PRESENT_IF_FIELD_REF,      // VALUE IS PRESENT IF @fieldName
    VALUE_IS_PRESENT_IF,                 // VALUE IS PRESENT IF fieldName IS ABSENT/PRESENT
    VALUE_IS_ABSENT,                     // VALUE IS ABSENT
    VALUE_IN,                            // VALUE IN ['AA', 'AB', ...]
    VALUE_REGEX,                         // VALUE = /regex/
    VALUE_COMPARE,                       // VALUE >= 1
    RANGE,                               // RANGE 1 TO 9

    // 条件规则
    FORBIDDEN_IF,                        // FORBIDDEN IF vehicleCategory IN [Mx]
    MANDATORY_IF,                        // MANDATORY IF vehicleCategory IN [Mx] AND stageOfCompletion = C
    FIELD_IS_NULL,                       // fieldName IS NULL / IS NOT NULL
    FIELD_IS_ABSENT,                     // fieldName IS ABSENT / IS PRESENT

    // 列表规则
    ANY_CONDITION,                       // ANY fieldName = value
    ALL_CONDITION,                       // ALL fieldName IS ABSENT

    // 聚合规则
    COUNT_AGGREGATE,                     // COUNT(axleList, condition) = VALUE
    SUM_AGGREGATE,                       // SUM(axleList, fieldName) >= VALUE

    // 嵌套条件规则
    NESTED_CONDITION,                     // VALUE = /regex/ IF ANY ... IF ALL ...

    /** 数值范围校验：min=x; max=y */
    NUMERIC_RANGE,

    /** 字符串长度范围：minLength=x; maxLength=y */
    LENGTH_RANGE,

    /**仅最大长度：maxLength=n */
    MAX_LENGTH,

    /** 仅最小长度：minLength=n */
    MIN_LENGTH,

    /** 总有效数字位数：totalDigits=n */
    TOTAL_DIGITS,

    /** 小数位数：fractionDigits=n */
    FRACTION_DIGITS,
}