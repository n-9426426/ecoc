package com.ruoyi.common.core.model;

import com.ruoyi.common.core.enums.RuleItemType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 嵌套条件规则实体类
 * 用于 VALUE = /regex/ IF ANY ... IF ALL ... 这类复合规则
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NestedConditionRule {

    /**
     * 主规则类型
     * 例如: VALUE_REGEX、VALUE_IN
     */
    private RuleItemType mainRuleType;

    /**
     * 主规则内容
     * -若mainRuleType = VALUE_REGEX → String（正则表达式）
     * - 若 mainRuleType = VALUE_IN   → List<String>（枚举值列表）
     */
    private Object mainRuleContent;

    /**
     * 条件链（按顺序依次评估）
     * 所有条件都满足时，才执行主规则校验
     */
    private List<ConditionChain> conditionChains;
}