package com.ruoyi.common.core.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 整体校验报告
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ValidationReport {

    /** 车型（如 M1、N2、L1e） */
    private String vehicleCategory;

    /** 完成阶段（如 C、A） */
    private String stageOfCompletion;

    /** 是否全部通过 */
    private boolean allValid;

    /** 系统错误信息（JSON 解析失败等） */
    private String error;

    /** 所有字段的校验结果列表 */
    private List<FieldValidationResult> fieldResults;

    // ==========================================
    // 静态工厂方法
    // ==========================================

    public static ValidationReport pass() {
        return ValidationReport.builder()
                .allValid(true)
                .fieldResults(new ArrayList<>())
                .build();
    }

    public static ValidationReport fail(String error) {
        return ValidationReport.builder()
                .allValid(false)
                .error(error)
                .fieldResults(new ArrayList<>())
                .build();
    }

    // ==========================================
    // 实例方法
    // ==========================================

    public void addFieldResult(FieldValidationResult result) {
        if (this.fieldResults == null) this.fieldResults = new ArrayList<>();
        this.fieldResults.add(result);
        if (!result.isValid()) this.allValid = false;
    }

    public void merge(ValidationReport other) {
        if (other == null) return;
        if (!other.isAllValid()) this.allValid = false;
        if (other.getFieldResults() != null) {
            if (this.fieldResults == null) this.fieldResults = new ArrayList<>();
            this.fieldResults.addAll(other.getFieldResults());
        }
    }

    /** 获取未通过的字段列表 */
    public List<FieldValidationResult> getFailedFields() {
        if (fieldResults == null) return Collections.emptyList();
        return fieldResults.stream()
                .filter(r -> !r.isValid())
                .collect(Collectors.toList());
    }
}