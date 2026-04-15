package com.ruoyi.common.core.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 规则违规信息
 * 校验不通过时返回的违规详情
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RuleViolation {

    /**
     * 规则编号（如R1、R2a）
     */
    private String ruleId;

    /**
     * 英文错误提示
     */
    private String messageEn;

    /**
     * 中文错误提示
     */
    private String messageZh;

    /**
     * 原始规则字符串（用于调试）
     */
    private String rawRule;
}