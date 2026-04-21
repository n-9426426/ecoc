package com.ruoyi.common.core.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 值范围约束（来自 rangeRule 字段）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ValueRangeConstraint {

    /** 数值最小值 */
    private Double min;

    /** 数值最大值 */
    private Double max;

    /** 字符串最小长度 */
    private Integer minLength;

    /** 字符串最大长度 */
    private Integer maxLength;

    /** 总有效数字位数（整数+小数） */
    private Integer totalDigits;

    /** 小数位数上限 */
    private Integer fractionDigits;

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        if (min != null)           sb.append("min=").append(min).append("; ");
        if (max != null)           sb.append("max=").append(max).append("; ");
        if (minLength != null)     sb.append("minLength=").append(minLength).append("; ");
        if (maxLength != null)     sb.append("maxLength=").append(maxLength).append("; ");
        if (totalDigits != null)   sb.append("totalDigits=").append(totalDigits).append("; ");
        if (fractionDigits != null)sb.append("fractionDigits=").append(fractionDigits).append("; ");
        return sb.toString().trim();
    }
}