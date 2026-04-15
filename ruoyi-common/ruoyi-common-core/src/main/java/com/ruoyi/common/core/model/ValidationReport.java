package com.ruoyi.common.core.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;

/**
 * 整体校验报告
 * 一次校验的完整结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ValidationReport {

    /**
     * 车型（如 M1、N2、L1e）
     */
    private String vehicleCategory;

    /**
     * 完成阶段（如 C、A）
     */
    private String stageOfCompletion;

    /**
     * 是否全部通过
     */
    private boolean allValid;

    /**
     * 系统错误信息（JSON解析失败等）
     */
    private String error;

    /**
     * 所有字段的校验结果列表
     */
    private List<FieldValidationResult> fieldResults;

    /**
     * 获取未通过的字段列表（快捷方法）
     */
    public List<FieldValidationResult> getFailedFields() {
        if (fieldResults == null) return Collections.emptyList();
        return fieldResults.stream().filter(r -> !r.isValid())
                .collect(java.util.stream.Collectors.toList());
    }
}