package com.ruoyi.common.core.model;

import lombok.Builder;
import lombok.Data;

/**
 * 条件链节点
 */
@Data
@Builder
public class ConditionChain {
    /** 条件类型: IF_ANY, IF_ALL */
    private String conditionType;

    /** 字段名 */
    private String fieldName;

    /** 运算符: =, !=, IS_PRESENT, IS_ABSENT */
    private String operator;

    /** 比较值 */
    private String value;
}