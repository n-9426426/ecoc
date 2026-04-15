package com.ruoyi.common.core.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 聚合函数实体类
 * 用于 COUNT / SUM 聚合规则
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AggregateFunction {

    /**
     * 聚合类型（COUNT 或 SUM）
     */
    private String aggregateType;

    /**
     * 列表字段名
     * 例如: axleList、bodyworkList、energySourceList
     */
    private String listFieldName;

    /**
     * 目标字段名（仅 SUM 使用）
     * 例如: massAxle
     */
    private String targetFieldName;

    /**
     * 过滤条件（仅 COUNT 使用）
     * 例如: brakedAxleIndicator = 'Y'
     */
    private ConditionExpression filterCondition;

    /**
     * 比较运算符（=、>=、<=、>、<）
     */
    private String compareOperator;

    /**
     * 比较值（通常是 "VALUE"，表示与当前字段值比较）
     */
    private String compareValue;
}