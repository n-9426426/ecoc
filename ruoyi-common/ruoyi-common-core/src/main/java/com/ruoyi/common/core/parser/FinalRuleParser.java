package com.ruoyi.common.core.parser;

import com.ruoyi.common.core.enums.RuleItemType;
import com.ruoyi.common.core.model.*;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 规则字符串解析器
 * 支持 rule 字段和 rangeRule 字段的完整解析
 */
@Slf4j
public class FinalRuleParser {

    // ===== 规则编号前缀 =====
    private static final Pattern RULE_ID_PATTERN = Pattern.compile("^R(\\w+)\\s*:\\s*(.*)$");

    // ===== 嵌套条件（最高优先级）=====
    // VALUE IS PRESENT IF ANY ... IF ALL ...
    // VALUE = /re/ IF ANY ... IF ALL ...
    private static final Pattern NESTED_ANY_ALL_PATTERN =
            Pattern.compile(
                    "VALUE\\s+(IS\\s+PRESENT|IS\\s+ABSENT|=\\s+/[^/]+/)\\s+IF\\s+ANY\\s+(.+?)\\s+IF\\s+ALL\\s+(.+)",
                    Pattern.CASE_INSENSITIVE);

    // VALUE IS PRESENT IF @字段名 IS PRESENT
    // VALUE IS PRESENT IF @字段名 IS ABSENT
    private static final Pattern VALUE_IS_PRESENT_IF_REF_PATTERN =
            Pattern.compile(
                    "VALUE\\s+IS\\s+PRESENT\\s+IF\\s+@(\\w+)\\s+IS\\s+(PRESENT|ABSENT)",
                    Pattern.CASE_INSENSITIVE);

    // VALUE IS ABSENT IF @字段名 IS PRESENT
    private static final Pattern VALUE_IS_ABSENT_IF_REF_PATTERN =
            Pattern.compile(
                    "VALUE\\s+IS\\s+ABSENT\\s+IF\\s+@(\\w+)\\s+IS\\s+(PRESENT|ABSENT)",
                    Pattern.CASE_INSENSITIVE);

    // ===== 条件规则 =====
    private static final Pattern FORBIDDEN_IF_ALL_PATTERN =
            Pattern.compile(
                    "VALUE\\s+IS\\s+ABSENT\\s+IF\\s+ALL\\s+(.+)",
                    Pattern.CASE_INSENSITIVE);

    private static final Pattern MANDATORY_IF_ALL_PATTERN =
            Pattern.compile(
                    "VALUE\\s+IS\\s+PRESENT\\s+IF\\s+ALL\\s+(.+)",
                    Pattern.CASE_INSENSITIVE);

    private static final Pattern FORBIDDEN_IF_ANY_PATTERN =
            Pattern.compile(
                    "VALUE\\s+IS\\s+ABSENT\\s+IF\\s+ANY\\s+(.+)",
                    Pattern.CASE_INSENSITIVE);

    private static final Pattern MANDATORY_IF_ANY_PATTERN =
            Pattern.compile(
                    "VALUE\\s+IS\\s+PRESENT\\s+IF\\s+ANY\\s+(.+)",
                    Pattern.CASE_INSENSITIVE);

    // ===== 聚合函数 =====
    private static final Pattern COUNT_PATTERN =
            Pattern.compile(
                    "COUNT\\s*\\(@?([^,]+?),\\s*@?([^)]+?)\\)\\s*(>=|<=|>|<|=|!=)\\s*(\\d+)",
                    Pattern.CASE_INSENSITIVE);

    private static final Pattern SUM_PATTERN =
            Pattern.compile(
                    "SUM\\s*\\(@?([^,]+?),\\s*@?([^)]+?)\\)\\s*(>=|<=|>|<|=|!=)\\s*(VALUE|\\d+(?:\\.\\d+)?)",
                    Pattern.CASE_INSENSITIVE);

    // ===== 枚举 =====
    private static final Pattern VALUE_IN_PATTERN =
            Pattern.compile("VALUE\\s+IN\\s+\\[([^\\]]+)\\]",
                    Pattern.CASE_INSENSITIVE);

    // ===== 正则 =====
    private static final Pattern VALUE_REGEX_PATTERN =
            Pattern.compile("VALUE\\s+=\\s+/([^/]+)/",
                    Pattern.CASE_INSENSITIVE);

    // ===== 数值比较 =====
    private static final Pattern VALUE_COMPARE_PATTERN =
            Pattern.compile("VALUE\\s+(>=|<=|>|<|=|!=)\\s+([^\\s]+)",
                    Pattern.CASE_INSENSITIVE);

    // ===== 存在性 =====
    private static final Pattern VALUE_IS_PRESENT_PATTERN =
            Pattern.compile("VALUE\\s+IS\\s+PRESENT", Pattern.CASE_INSENSITIVE);
    private static final Pattern VALUE_IS_ABSENT_PATTERN =
            Pattern.compile("VALUE\\s+IS\\s+ABSENT", Pattern.CASE_INSENSITIVE);

    // ==========================================
    // 公开入口
    // ==========================================

    /**
     * 解析 rule 字段 + rangeRule 字段，返回完整规则列表
     */
    public static List<RuleItem> parseRules(String ruleStr, String rangeStr) {
        List<RuleItem> items = new ArrayList<>();

        if (ruleStr != null && !ruleStr.trim().isEmpty()) {
            ruleStr = unescapeHtml(ruleStr);
            for (String line : ruleStr.split("\\n")) {
                line = line.trim();
                if (line.isEmpty()) continue;
                RuleItem item = parseLine(line);
                if (item != null) items.add(item);
            }
        }

        if (rangeStr != null && !rangeStr.trim().isEmpty()) {
            ValueRangeConstraint constraint = ValueRangeParser.parse(rangeStr);
            List<RuleItem> rangeItems = buildRangeRuleItems(constraint);
            if (!rangeItems.isEmpty()) items.addAll(rangeItems);
        }

        return items;
    }

    /**
     * 仅解析 rule 字段（兼容旧调用）
     */
    public static List<RuleItem> parseRules(String ruleStr) {
        return parseRules(ruleStr, null);
    }

    // ==========================================
    // 私有解析逻辑
    // ==========================================

    private static RuleItem parseLine(String line) {
        String ruleId = null;
        String body = line;

        Matcher idMatcher = RULE_ID_PATTERN.matcher(line);
        if (idMatcher.matches()) {
            ruleId = idMatcher.group(1);
            body = idMatcher.group(2).trim();
        }

        RuleItem item = parseRuleBody(body);
        if (item != null) {
            item.setRuleId(ruleId);
            item.setRawRule(line);
        }
        return item;
    }

    private static RuleItem parseRuleBody(String body) {

        // 1. 嵌套条件：VALUE ... IF ANY ... IF ALL ...
        RuleItem nestedItem = parseNestedAnyAll(body);
        if (nestedItem != null) {
            return nestedItem;
        }

        Matcher m = VALUE_IS_PRESENT_IF_REF_PATTERN.matcher(body);
        if (m.matches()) {
            String refCondition = m.group(2).trim().toUpperCase();
            return RuleItem.builder()
                    .type(RuleItemType.MANDATORY_IF)
                    .refFieldName(m.group(1).trim())
                    .refFieldCondition(refCondition)
                    .compareValue(m.group(2).trim().toUpperCase())
                    .build();
        }

        m = VALUE_IS_ABSENT_IF_REF_PATTERN.matcher(body);
        if (m.matches()) {
            String refCondition = m.group(2).trim().toUpperCase();
            return RuleItem.builder()
                    .type(RuleItemType.FORBIDDEN_IF)
                    .refFieldName(m.group(1).trim())
                    .refFieldCondition(refCondition)
                    .compareValue(m.group(2).trim().toUpperCase())
                    .build();
        }

        // 2. MANDATORY_IF ANY
        m = MANDATORY_IF_ANY_PATTERN.matcher(body);
        if (m.matches()) {
            return RuleItem.builder()
                    .type(RuleItemType.MANDATORY_IF_ANY)
                    .conditionChain(ConditionChain.parseAny(m.group(1).trim()))
                    .build();
        }

        // 3. FORBIDDEN_IF ALL
        m = FORBIDDEN_IF_ALL_PATTERN.matcher(body);
        if (m.matches()) {
            return RuleItem.builder()
                    .type(RuleItemType.FORBIDDEN_IF_ALL)
                    .conditionChain(ConditionChain.parseAll(m.group(1).trim()))
                    .build();
        }

        // 4. MANDATORY_IF ALL
        m = MANDATORY_IF_ALL_PATTERN.matcher(body);
        if (m.matches()) {
            return RuleItem.builder()
                    .type(RuleItemType.MANDATORY_IF_ALL)
                    .conditionChain(ConditionChain.parseAll(m.group(1).trim()))
                    .build();
        }

        // 3. FORBIDDEN_IF ANY
        m = FORBIDDEN_IF_ANY_PATTERN.matcher(body);
        if (m.matches()) {
            String condition = m.group(1).trim().replace("@", "");
            return RuleItem.builder()
                    .type(RuleItemType.FORBIDDEN_IF_ANY)
                    .conditionChain(ConditionChain.parseAll(m.group(1).trim()))
                    .build();
        }

        // 5. COUNT
        m = COUNT_PATTERN.matcher(body);
        if (m.matches()) {
            String listField = m.group(1).trim().replace("@", "");
            String targetField = m.group(2).trim().replace("@", "");
            String operator = m.group(3).trim();
            String thresholdStr = m.group(4).trim();
            AggregateFunction af = AggregateFunction.builder()
                    .functionType(AggregateFunction.Type.COUNT)
                    .listField(listField)
                    .condition(targetField)
                    .operator(com.ruoyi.common.core.enums.CompareOperator.fromSymbol(operator))
                    .threshold(Double.parseDouble(thresholdStr))
                    .build();
            return RuleItem.builder()
                    .type(RuleItemType.COUNT_AGGREGATE)
                    .aggregateFunction(af)
                    .build();
        }

        // 6. SUM
        m = SUM_PATTERN.matcher(body);
        if (m.matches()) {
            String listField = m.group(1).trim().replace("@", "");
            String targetField = m.group(2).trim().replace("@", "");
            String operator = m.group(3).trim();
            String thresholdStr = m.group(4).trim();
            Double threshold = "VALUE".equalsIgnoreCase(thresholdStr)
                    ? null : Double.parseDouble(thresholdStr);
            AggregateFunction af = AggregateFunction.builder()
                    .functionType(AggregateFunction.Type.SUM)
                    .listField(listField)
                    .field(targetField)
                    .operator(com.ruoyi.common.core.enums.CompareOperator.fromSymbol(operator))
                    .threshold(threshold)
                    .build();
            return RuleItem.builder()
                    .type(RuleItemType.SUM_AGGREGATE)
                    .aggregateFunction(af)
                    .build();
        }

        // 7. VALUE IN [...]
        m = VALUE_IN_PATTERN.matcher(body);
        if (m.matches()) {
            return RuleItem.builder()
                    .type(RuleItemType.VALUE_IN)
                    .enumValues(parseList(m.group(1)))
                    .build();
        }

        // 8. VALUE = /regex/
        m = VALUE_REGEX_PATTERN.matcher(body);
        if (m.matches()) {
            return RuleItem.builder()
                    .type(RuleItemType.VALUE_REGEX)
                    .regexPattern(m.group(1))
                    .build();
        }

        // 9. VALUE 比较运算
        m = VALUE_COMPARE_PATTERN.matcher(body);
        if (m.matches()) {
            return RuleItem.builder()
                    .type(RuleItemType.VALUE_COMPARE)
                    .operator(m.group(1).trim())
                    .compareValue(m.group(2).trim())
                    .build();
        }

        // 10. VALUE IS PRESENT
        if (VALUE_IS_PRESENT_PATTERN.matcher(body).matches()) {
            return RuleItem.builder()
                    .type(RuleItemType.VALUE_IS_PRESENT)
                    .operator("IS_PRESENT")
                    .build();
        }

        // 11. VALUE IS ABSENT
        if (VALUE_IS_ABSENT_PATTERN.matcher(body).matches()) {
            return RuleItem.builder()
                    .type(RuleItemType.VALUE_IS_ABSENT)
                    .operator("IS_ABSENT")
                    .build();
        }

        log.warn("无法解析规则行: {}", body);
        return null;
    }

    // ==========================================
    // 工具方法
    // ==========================================

    /**
     * 解析枚举列表字符串
     * 支持：[Y, N] 或 ['Y', 'N'] 或 ["Y", "N"]
     */
    private static List<String> parseList(String listStr) {
        List<String> result = new ArrayList<>();
        for (String s : listStr.split(",")) {
            String val = s.trim().replaceAll("^['\"]|['\"]$", "");
            if (!val.isEmpty()) result.add(val);
        }
        return result;
    }

    /**
     * 将 ValueRangeConstraint 转换为 RuleItem
     */
    public static List<RuleItem> buildRangeRuleItems(ValueRangeConstraint c) {
        List<RuleItem> items = new ArrayList<>();
        if (c == null) return items;

        // 1. 数值范围
        if (c.getMin() != null || c.getMax() != null) {
            items.add(RuleItem.builder()
                    .type(RuleItemType.NUMERIC_RANGE)
                    .rangeMin(c.getMin())
                    .rangeMax(c.getMax())
                    .rawRule(c.toString())
                    .build());
        }

        // 2. totalDigits
        if (c.getTotalDigits() != null) {
            items.add(RuleItem.builder()
                    .type(RuleItemType.TOTAL_DIGITS)
                    .totalDigits(c.getTotalDigits())
                    .rawRule(c.toString())
                    .build());
        }

        // 3. fractionDigits
        if (c.getFractionDigits() != null) {
            items.add(RuleItem.builder()
                    .type(RuleItemType.FRACTION_DIGITS)
                    .fractionDigits(c.getFractionDigits())
                    .rawRule(c.toString())
                    .build());
        }

        // 4. 长度约束
        if (c.getMinLength() != null && c.getMaxLength() != null) {
            items.add(RuleItem.builder()
                    .type(RuleItemType.LENGTH_RANGE)
                    .minLength(c.getMinLength())
                    .maxLength(c.getMaxLength())
                    .rawRule(c.toString())
                    .build());
        } else if (c.getMaxLength() != null) {
            items.add(RuleItem.builder()
                    .type(RuleItemType.MAX_LENGTH)
                    .maxLength(c.getMaxLength())
                    .rawRule(c.toString())
                    .build());
        } else if (c.getMinLength() != null) {
            items.add(RuleItem.builder()
                    .type(RuleItemType.MIN_LENGTH)
                    .minLength(c.getMinLength())
                    .rawRule(c.toString())
                    .build());
        }

        return items;
    }

    private static String unescapeHtml(String str) {
        if (str == null) return null;
        return str
                .replace("&gt;",  ">")
                .replace("&lt;",  "<")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
    }

    private static RuleItem parseNestedAnyAll(String body) {
        // Step 1: 匹配开头 "VALUE ... IF ANY"
        Pattern startPattern = Pattern.compile(
                "^VALUE\\s+(IS\\s+PRESENT|IS\\s+ABSENT|=\\s+/[^/]+/)\\s+IF\\s+ANY\\s*",
                Pattern.CASE_INSENSITIVE);
        Matcher startMatcher = startPattern.matcher(body);
        if (!startMatcher.find()) {
            return null;
        }
        String opPart = startMatcher.group(1).trim();
        int afterAnyPos = startMatcher.end();

        // Step 2: 从 afterAnyPos 开始，寻找 " IF ALL "（注意前后空格）
        String rest = body.substring(afterAnyPos);
        int allIndex = findIfAllIndex(rest);
        if (allIndex == -1) {
            return null;
        }

        String anyCond = rest.substring(0, allIndex).trim();
        String allCond = rest.substring(allIndex + " IF ALL ".length()).trim(); // 跳过 " IF ALL "

        // Step 3: 解析 operator
        String operator;
        String compareValue = null;
        if (opPart.equalsIgnoreCase("IS PRESENT")) {
            operator = "IS_PRESENT";
        } else if (opPart.equalsIgnoreCase("IS ABSENT")) {
            operator = "IS_ABSENT";
        } else {
            operator = "REGEX";
            compareValue = opPart.replaceAll("^=\\s*/|/$", "").trim();
        }

        // Step 4: 构建条件链（关键：允许逗号分隔的多条件）
        ConditionChain anyChain = ConditionChain.parseAny(anyCond);
        ConditionChain allChain = ConditionChain.parseAll(allCond);

        NestedConditionRule nested = NestedConditionRule.builder()
                .operator(operator)
                .compareValue(compareValue)
                .anyChain(anyChain)
                .allChain(allChain)
                .build();

        return RuleItem.builder()
                .type(RuleItemType.NESTED_CONDITION)
                .nestedCondition(nested)
                .build();
    }

    // 辅助方法：安全查找 " IF ALL "（支持前后空格、大小写）
    private static int findIfAllIndex(String str) {
        // 优先匹配 " IF ALL "（带空格）
        int idx = str.indexOf(" IF ALL ");
        if (idx != -1) return idx;
        // 次匹配 "IF ALL"（无空格，但需边界）
        idx = str.indexOf("IF ALL");
        if (idx > 0 && idx < str.length() - 8) {
            char before = str.charAt(idx - 1);
            char after = str.charAt(idx + 8);
            if (Character.isWhitespace(before) && Character.isWhitespace(after)) {
                return idx;
            }
        }
        return -1;
    }
}