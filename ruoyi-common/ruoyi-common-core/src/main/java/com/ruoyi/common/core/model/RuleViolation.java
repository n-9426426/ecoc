package com.ruoyi.common.core.model;

import com.ruoyi.common.core.enums.RuleItemType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单条规则违规信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RuleViolation {

    /** 规则编号 */
    private String ruleId;

    /** 字段名 */
    private String fieldName;

    /** 实际值（字符串形式） */
    private String actualValue;

    /** 英文错误信息 */
    private String messageEn;

    /** 中文错误信息 */
    private String messageZh;

    /** 原始规则字符串 */
    private String rawRule;

    private RuleItemType ruleType;
}