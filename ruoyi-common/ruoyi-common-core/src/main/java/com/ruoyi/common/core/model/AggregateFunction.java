package com.ruoyi.common.core.model;

import com.ruoyi.common.core.enums.CompareOperator;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 聚合函数描述
 * 支持 COUNT 和 SUM
 *
 * <h3>字段使用说明</h3>
 * <pre>
 * COUNT(@listField, @condField) op N           → listField + condition + operator + threshold
 * COUNT(@listField, @condField IN [v]) = VALUE → listField + field + enumValues + operator(EQ) + threshold(null)
 * @Table=&gt;COUNT(VALUE IN [v]) op N             → listField + enumValues + operator + threshold
 * SUM(@listField, @field) op N|VALUE           → listField + field + operator + threshold(null=VALUE)
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AggregateFunction {

    public enum Type { COUNT, SUM }

    /** 聚合类型 */
    private Type functionType;

    /** 列表字段名，如 axleList、TyreAxleTable */
    private String listField;

    /**
     * COUNT 用（旧式条件字符串）：条件表达式，如 "axleType = 1"
     * 新式规则推荐使用 field + enumValues 组合，condition 保留向后兼容
     */
    private String condition;

    /**
     * COUNT / SUM 用：目标字段名
     * COUNT_AS_VALUE 中为条件字段名，如 "TwinWheelsAxleIndicator"
     * SUM 中为求和字段名，如 "axleLoad"
     */
    private String field;

    /**
     * COUNT 用：枚举白名单，如 ["Y"]
     * 与 field 配合使用，表示 field IN [enumValues] 的条件
     * 用于 COUNT_AS_VALUE 和 LIST_COUNT 规则类型
     */
    @Builder.Default
    private List<String> enumValues = null;

    /** 比较运算符 */
    private CompareOperator operator;

    /**
     * 比较阈值
     * COUNT_AS_VALUE / SUM 中为 null 时表示引用当前字段值（VALUE）
     */
    private Double threshold;
}