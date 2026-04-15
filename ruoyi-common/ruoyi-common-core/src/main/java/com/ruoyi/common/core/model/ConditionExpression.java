package com.ruoyi.common.core.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 条件表达式实体类
 * 用于 ANY / ALL 条件中的单个条件描述
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConditionExpression {

    /**
     * 量词（ANY 或 ALL）
     */
    private String quantifier;

    /**
     * 字段名
     * 例如: EnergySource、brakedAxleIndicator
     */
    private String fieldName;

    /**
     * 运算符
     * 例如: =、!=、IS_ABSENT、IS_PRESENT
     */
    private String operator;

    /**
     * 比较值
     * 例如: "95"、"Y"
     */
    private Object value;

    /**
     * 逻辑连接符（AND / OR）
     * 用于多条件组合
     */
    private String logic;

    /**
     * 子条件列表（用于嵌套条件）
     */
    private List<ConditionExpression> children;
}