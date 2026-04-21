package com.ruoyi.common.core.model;

import com.ruoyi.common.core.enums.CompareOperator;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 聚合函数描述
 * 支持 COUNT 和 SUM
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AggregateFunction {

    public enum Type { COUNT, SUM }

    /** 聚合类型 */
    private Type functionType;

    /** 列表字段名，如 axleList */
    private String listField;

    /** COUNT 用：条件字符串，如 "axleType = 1" */
    private String condition;

    /** SUM 用：求和字段名，如 "axleLoad" */
    private String field;

    /** 比较运算符 */
    private CompareOperator operator;

    /**
     * 比较阈值
     * SUM 中为 null 时表示引用当前字段值（VALUE）
     */
    private Double threshold;
}