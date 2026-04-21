package com.ruoyi.common.core.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 单字段校验结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FieldValidationResult {

    /** 字段名 */
    private String fieldName;

    /** 字段实际值 */
    private Object value;

    /** 是否通过校验 */
    private boolean valid;

    /** 违规列表（valid=false 时有值） */
    private List<RuleViolation> violations;
}