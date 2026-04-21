package com.ruoyi.common.core.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 嵌套条件规则
 * 示例：VALUE = /regex/ IF ANY certType = 1 IF ALL issueDate IS PRESENT
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NestedConditionRule {

    /**
     * 主体操作：IS_PRESENT / IS_ABSENT / REGEX / COMPARE
     */
    private String operator;

    /**
     * 主体比较值（正则表达式字符串 或 比较数值）
     */
    private String compareValue;

    /**
     * IF ANY 条件链（任意满足）
     */
    private ConditionChain anyChain;

    /**
     * IF ALL 条件链（全部满足）
     */
    private ConditionChain allChain;
}