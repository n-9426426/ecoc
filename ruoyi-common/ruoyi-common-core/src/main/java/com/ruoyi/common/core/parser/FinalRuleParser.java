package com.ruoyi.common.core.parser;

import com.ruoyi.common.core.enums.CompareOperator;
import com.ruoyi.common.core.enums.RuleItemType;
import com.ruoyi.common.core.model.*;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 规则解析器
 */
@Slf4j
public class FinalRuleParser {

    // 规则编号前缀
    private static final Pattern RULE_ID_PATTERN =
            Pattern.compile("^(R\\d+[a-z]?):\\s*(.+)$", Pattern.DOTALL);

    // 嵌套条件规则: VALUE = /regex/ IF ANY ... IF ALL ...
    private static final Pattern NESTED_CONDITION_PATTERN =
            Pattern.compile("(VALUE\\s*=\\s*/(.+?)/)\\s+(IF\\s+.+)",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    // IF ANY/ALL 条件
    private static final Pattern IF_CONDITION_PATTERN =
            Pattern.compile("IF\\s+(ANY|ALL)\\s+(\\w+)\\s+(=|!=|IS\\s+PRESENT|IS\\s+ABSENT)\\s*['\"]?([^'\"\\s]*)['\"]?",
                    Pattern.CASE_INSENSITIVE);

    // VALUE IS PRESENT IF @fieldName
    private static final Pattern VALUE_IS_PRESENT_IF_FIELD_REF_PATTERN =
            Pattern.compile("VALUE IS PRESENT IF @(\\w+)", Pattern.CASE_INSENSITIVE);

    // VALUE IS PRESENT IF fieldName IS ABSENT/PRESENT
    private static final Pattern VALUE_IS_PRESENT_IF_PATTERN =
            Pattern.compile("VALUE IS PRESENT IF (\\w+) IS (ABSENT|PRESENT)",
                    Pattern.CASE_INSENSITIVE);

    // VALUE IS ABSENT
    private static final Pattern VALUE_IS_ABSENT_PATTERN =
            Pattern.compile("^VALUE IS ABSENT$", Pattern.CASE_INSENSITIVE);

    // VALUE IN ['AA', 'BB', ...]
    private static final Pattern VALUE_IN_PATTERN =
            Pattern.compile("VALUE IN \\[([^\\]]+)\\]", Pattern.CASE_INSENSITIVE);

    // VALUE = /regex/
    private static final Pattern VALUE_REGEX_PATTERN =
            Pattern.compile("VALUE\\s*=\\s*/(.+?)/", Pattern.CASE_INSENSITIVE);

    // VALUE 比较运算符 数字
    private static final Pattern VALUE_COMPARE_PATTERN =
            Pattern.compile("VALUE\\s*(=|!=|<=|>=|<|>)\\s*(\\S+)",
                    Pattern.CASE_INSENSITIVE);

    // FORBIDDEN IF / MANDATORY IF
    private static final Pattern FORBIDDEN_MANDATORY_PATTERN =
            Pattern.compile("(FORBIDDEN|MANDATORY) IF (.+)",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    // RANGE min TO max
    private static final Pattern RANGE_PATTERN =
            Pattern.compile("RANGE\\s+(\\d+(?:\\.\\d+)?)\\s+TO\\s+(\\d+(?:\\.\\d+)?)",
                    Pattern.CASE_INSENSITIVE);

    // fieldName IS NULL / IS NOT NULL
    private static final Pattern FIELD_IS_NULL_PATTERN =
            Pattern.compile("(\\w+)\\s+IS\\s+(NOT\\s+)?NULL",
                    Pattern.CASE_INSENSITIVE);

    // fieldName IS ABSENT / IS PRESENT
    private static final Pattern FIELD_IS_ABSENT_PATTERN =
            Pattern.compile("(\\w+)\\s+IS\\s+(ABSENT|PRESENT)",
                    Pattern.CASE_INSENSITIVE);

    // ANY fieldName = value
    private static final Pattern ANY_CONDITION_PATTERN =
            Pattern.compile("ANY (\\w+)\\s*(=|!=)\\s*['\"]?([^'\"\\s]+)['\"]?",
                    Pattern.CASE_INSENSITIVE);

    // ALL fieldName IS ABSENT/PRESENT
    private static final Pattern ALL_CONDITION_PATTERN =
            Pattern.compile("ALL (\\w+) IS (ABSENT|PRESENT)",
                    Pattern.CASE_INSENSITIVE);

    // COUNT(listField, condition) = VALUE
    private static final Pattern COUNT_PATTERN =
            Pattern.compile("COUNT\\(([^,]+),\\s*(.+?)\\)\\s*(=|>=|<=|>|<)\\s*VALUE",
                    Pattern.CASE_INSENSITIVE);

    // SUM(listField, fieldName) >= VALUE
    private static final Pattern SUM_PATTERN =
            Pattern.compile("SUM\\(([^,]+),\\s*(\\w+)\\)\\s*(=|>=|<=|>|<)\\s*VALUE",
                    Pattern.CASE_INSENSITIVE);

    // any CodeForBodywork = CA (小写any)
    private static final Pattern LOWERCASE_ANY_PATTERN =
            Pattern.compile("any (\\w+)\\s*=\\s*([^\\s]+)",
                    Pattern.CASE_INSENSITIVE);

    /**
     * 解析规则字符串（支持多行）
     */
    public static List<RuleItem> parseRules(String ruleStr) {
        List<RuleItem> items = new ArrayList<>();
        if (ruleStr == null || ruleStr.trim().isEmpty()) {
            return items;
        }

        // 按换行拆分
        String[] lines = ruleStr.split("\\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;

            try {
                RuleItem item = parseLine(line);
                if (item != null) {
                    items.add(item);
                }
            } catch (Exception e) {
                log.warn("规则解析失败: {}", line, e);
            }
        }

        return items;
    }

    /**
     * 解析单行规则
     */
    private static RuleItem parseLine(String line) {
        String ruleId = null;
        String ruleBody = line;

        // 提取规则编号
        Matcher idMatcher = RULE_ID_PATTERN.matcher(line);
        if (idMatcher.matches()) {
            ruleId = idMatcher.group(1);
            ruleBody = idMatcher.group(2).trim();
        }

        RuleItem item = parseRuleBody(ruleBody);
        if (item != null) {
            item.setRuleId(ruleId);
            item.setRawRule(line);
        }
        return item;
    }

    /**
     * 解析规则主体
     */
    private static RuleItem parseRuleBody(String body) {
        Matcher m;

        // 1. 嵌套条件规则（优先级最高）
        // 例如: VALUE = /HEV$/ IF ANY EnergySource = '95' IF ANY EngineCapacity IS PRESENT
        m = NESTED_CONDITION_PATTERN.matcher(body);
        if (m.find()) {
            String mainRule = m.group(1);  // VALUE = /HEV$/
            String regex = m.group(2);     // HEV$
            String conditions = m.group(3); // IF ANY ... IF ALL ...

            // 解析条件链
            List<ConditionChain> chains = parseConditionChains(conditions);

            NestedConditionRule nestedRule = NestedConditionRule.builder()
                    .mainRuleType(RuleItemType.VALUE_REGEX)
                    .mainRuleContent(regex)
                    .conditionChains(chains)
                    .build();

            return RuleItem.builder()
                    .type(RuleItemType.NESTED_CONDITION)
                    .compareValue(nestedRule)
                    .build();
        }

        // 2. VALUE IS PRESENT IF @fieldName
        m = VALUE_IS_PRESENT_IF_FIELD_REF_PATTERN.matcher(body);
        if (m.find()) {
            return RuleItem.builder()
                    .type(RuleItemType.VALUE_IS_PRESENT_IF_FIELD_REF)
                    .targetFieldName(m.group(1))
                    .build();
        }

        // 3. VALUE IS PRESENT IF fieldName IS ABSENT/PRESENT
        m = VALUE_IS_PRESENT_IF_PATTERN.matcher(body);
        if (m.find()) {
            return RuleItem.builder()
                    .type(RuleItemType.VALUE_IS_PRESENT_IF)
                    .targetFieldName(m.group(1))
                    .compareValue(m.group(2).toUpperCase())
                    .build();
        }

        // 4. VALUE IS ABSENT
        m = VALUE_IS_ABSENT_PATTERN.matcher(body);
        if (m.find()) {
            return RuleItem.builder()
                    .type(RuleItemType.VALUE_IS_ABSENT)
                    .build();
        }

        // 5. COUNT(listField, condition) = VALUE
        m = COUNT_PATTERN.matcher(body);
        if (m.find()) {
            String listField = m.group(1).trim();
            String conditionStr = m.group(2).trim();
            String operator = m.group(3);

            AggregateFunction aggFunc = AggregateFunction.builder()
                    .aggregateType("COUNT")
                    .listFieldName(listField)
                    .filterCondition(parseSimpleCondition(conditionStr))
                    .compareOperator(operator)
                    .compareValue("VALUE")
                    .build();

            return RuleItem.builder()
                    .type(RuleItemType.COUNT_AGGREGATE)
                    .compareValue(aggFunc)
                    .build();
        }

        // 6. SUM(listField, fieldName) >= VALUE
        m = SUM_PATTERN.matcher(body);
        if (m.find()) {
            String listField = m.group(1).trim();
            String targetField = m.group(2).trim();
            String operator = m.group(3);

            AggregateFunction aggFunc = AggregateFunction.builder()
                    .aggregateType("SUM")
                    .listFieldName(listField)
                    .targetFieldName(targetField)
                    .compareOperator(operator)
                    .compareValue("VALUE")
                    .build();

            return RuleItem.builder()
                    .type(RuleItemType.SUM_AGGREGATE)
                    .compareValue(aggFunc)
                    .build();
        }

        // 7. VALUE IN ['AA', 'BB', ...]
        m = VALUE_IN_PATTERN.matcher(body);
        if (m.find()) {
            List<String> values = parseQuotedList(m.group(1));
            return RuleItem.builder()
                    .type(RuleItemType.VALUE_IN)
                    .enumValues(values)
                    .build();
        }

        // 8. VALUE = /regex/
        m = VALUE_REGEX_PATTERN.matcher(body);
        if (m.find()) {
            return RuleItem.builder()
                    .type(RuleItemType.VALUE_REGEX)
                    .regexPattern(m.group(1))
                    .build();
        }

        // 9. ANY fieldName = value
        m = ANY_CONDITION_PATTERN.matcher(body);
        if (m.find()) {
            ConditionExpression cond = ConditionExpression.builder()
                    .quantifier("ANY")
                    .fieldName(m.group(1))
                    .operator(m.group(2))
                    .value(m.group(3))
                    .build();
            return RuleItem.builder()
                    .type(RuleItemType.ANY_CONDITION)
                    .compareValue(cond)
                    .build();
        }

        // 10. ALL fieldName IS ABSENT/PRESENT
        m = ALL_CONDITION_PATTERN.matcher(body);
        if (m.find()) {
            ConditionExpression cond = ConditionExpression.builder()
                    .quantifier("ALL")
                    .fieldName(m.group(1))
                    .operator("IS_" + m.group(2).toUpperCase())
                    .build();
            return RuleItem.builder()
                    .type(RuleItemType.ALL_CONDITION)
                    .compareValue(cond)
                    .build();
        }

        // 11. any CodeForBodywork = CA (小写any)
        m = LOWERCASE_ANY_PATTERN.matcher(body);
        if (m.find()) {
            ConditionExpression cond = ConditionExpression.builder()
                    .quantifier("ANY")
                    .fieldName(m.group(1))
                    .operator("=")
                    .value(m.group(2))
                    .build();
            return RuleItem.builder()
                    .type(RuleItemType.ANY_CONDITION)
                    .compareValue(cond)
                    .build();
        }

        // 12. FORBIDDEN IF / MANDATORY IF
        m = FORBIDDEN_MANDATORY_PATTERN.matcher(body);
        if (m.find()) {
            String type = m.group(1).toUpperCase();
            String condition = m.group(2).trim();
            return RuleItem.builder()
                    .type("FORBIDDEN".equals(type)
                            ? RuleItemType.FORBIDDEN_IF
                            : RuleItemType.MANDATORY_IF)
                    .compareValue(condition)
                    .build();
        }

        // 13. RANGE min TO max
        m = RANGE_PATTERN.matcher(body);
        if (m.find()) {
            return RuleItem.builder()
                    .type(RuleItemType.RANGE)
                    .rangeMin(Double.parseDouble(m.group(1)))
                    .rangeMax(Double.parseDouble(m.group(2)))
                    .build();
        }

        // 14. fieldName IS ABSENT / IS PRESENT
        m = FIELD_IS_ABSENT_PATTERN.matcher(body);
        if (m.find()) {
            return RuleItem.builder()
                    .type(RuleItemType.FIELD_IS_ABSENT)
                    .targetFieldName(m.group(1))
                    .compareValue(m.group(2).toUpperCase())
                    .build();
        }

        // 15. fieldName IS NULL / IS NOT NULL
        m = FIELD_IS_NULL_PATTERN.matcher(body);
        if (m.find()) {
            boolean isNotNull = m.group(2) != null;
            return RuleItem.builder()
                    .type(RuleItemType.FIELD_IS_NULL)
                    .targetFieldName(m.group(1))
                    .compareValue(!isNotNull)
                    .build();
        }

        // 16. VALUE 比较运算符 值（放最后）
        m = VALUE_COMPARE_PATTERN.matcher(body);
        if (m.find()) {
            String opStr = m.group(1);
            String val = m.group(2);
            return RuleItem.builder()
                    .type(RuleItemType.VALUE_COMPARE)
                    .compareOperator(CompareOperator.fromSymbol(opStr))
                    .compareValue(val)
                    .build();
        }

        log.warn("无法识别的规则: {}", body);
        return null;
    }

    /**
     * 解析条件链
     * 例如: IF ANY EnergySource = '95' IF ANY EngineCapacity IS PRESENT
     */
    private static List<ConditionChain> parseConditionChains(String conditionsStr) {
        List<ConditionChain> chains = new ArrayList<>();

        Matcher m = IF_CONDITION_PATTERN.matcher(conditionsStr);
        while (m.find()) {
            String quantifier = m.group(1).toUpperCase();  // ANY or ALL
            String fieldName = m.group(2);
            String operator = m.group(3).toUpperCase().replace(" ", "_");
            String value = m.group(4);

            chains.add(ConditionChain.builder()
                    .conditionType("IF_" + quantifier)
                    .fieldName(fieldName)
                    .operator(operator)
                    .value(value)
                    .build());
        }

        return chains;
    }

    /**
     * 解析简单条件（用于COUNT的过滤条件）
     */
    private static ConditionExpression parseSimpleCondition(String conditionStr) {
        Pattern p = Pattern.compile("(\\w+)\\s*(=|!=)\\s*['\"]?([^'\"]+)['\"]?");
        Matcher m = p.matcher(conditionStr);
        if (m.find()) {
            return ConditionExpression.builder()
                    .fieldName(m.group(1))
                    .operator(m.group(2))
                    .value(m.group(3))
                    .build();
        }
        return null;
    }

    /**
     * 解析带引号的列表
     */
    private static List<String> parseQuotedList(String listStr) {
        List<String> result = new ArrayList<>();
        Pattern p = Pattern.compile("['\"]([^'\"]+)['\"]");
        Matcher m = p.matcher(listStr);
        while (m.find()) {
            result.add(m.group(1).trim());
        }
        if (result.isEmpty()) {
            for (String s : listStr.split(",")) {
                result.add(s.trim());
            }
        }
        return result;
    }

    /**
     *✅ 新增入口：同时解析"正式规则"列和"值范围"列
     *
     * @param ruleStr 正式规则/正则列内容（原有列）
     * @param rangeStr 值范围列内容（新列，如min=0.0; max=999999）
     * @return 合并后的 RuleItem 列表
     */
    public static List<RuleItem> parseRulesWithRange(String ruleStr, String rangeStr) {
        List<RuleItem> items = new ArrayList<>();

        // 1. 解析正式规则（原有逻辑，不变）
        items.addAll(parseRules(ruleStr));

        // 2. 解析值范围列
        ValueRangeConstraint constraint = ValueRangeParser.parse(rangeStr);
        if (constraint != null) {
            List<RuleItem> rangeItems = ValueRangeParser.toRuleItems(constraint);
            items.addAll(rangeItems);
        }

        return items;
    }
}