package com.ruoyi.common.core.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 单个字段的校验结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FieldValidationResult {

    /**
     * 字段名称（dict_label）
     */
    private String fieldName;

    /**
     * 字段填写的值
     */
    private Object value;

    /**
     * 是否通过校验
     */
    private boolean valid;

    /**
     * 违规列表（未通过的规则）
     */
    private List<RuleViolation> violations;
}