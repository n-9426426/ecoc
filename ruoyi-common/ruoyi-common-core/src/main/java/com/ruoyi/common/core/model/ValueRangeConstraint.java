package com.ruoyi.common.core.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 值范围约束模型
 * 对应CSV 中"值范围"列，支持以下格式：
 *min=0.0; max=999999
 *   maxLength=2
 *   minLength=1; maxLength=35
 *   totalDigits=5;
 *   totalDigits=9; fractionDigits=5; min=0.0; max=9999.99999
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ValueRangeConstraint {

    /** 数值最小值（对应 min=） */
    private Double numericMin;

    /** 数值最大值（对应 max=） */
    private Double numericMax;

    /** 字符串最小长度（对应 minLength=） */
    private Integer minLength;

    /** 字符串最大长度（对应 maxLength=） */
    private Integer maxLength;

    /** 总有效数字位数（对应 totalDigits=） */
    private Integer totalDigits;

    /** 小数位数（对应 fractionDigits=） */
    private Integer fractionDigits;

    public boolean hasNumericRange() {
        return numericMin != null || numericMax != null;
    }

    public boolean hasLengthConstraint() {
        return minLength != null || maxLength != null;
    }

    public boolean hasTotalDigits() {
        return totalDigits != null;
    }

    public boolean hasFractionDigits() {
        return fractionDigits != null;
    }

    public boolean isEmpty() {
        return !hasNumericRange() && !hasLengthConstraint()
                && !hasTotalDigits() && !hasFractionDigits();
    }
}