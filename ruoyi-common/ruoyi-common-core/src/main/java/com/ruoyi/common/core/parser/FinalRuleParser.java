package com.ruoyi.common.core.parser;

import com.ruoyi.common.core.enums.CompareOperator;
import com.ruoyi.common.core.enums.RuleItemType;
import com.ruoyi.common.core.model.*;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 规则字符串解析器
 * 支持 rule 字段和 rangeRule 字段的完整解析
 *
 * <p>解析失败时不抛出异常、不打印日志，
 * 而是将错误信息封装为 {@link RuleItemType#PARSE_ERROR} 类型的 {@link RuleItem}，
 * 由执行器统一转化为校验违规报告。
 *
 * <h3>支持的规则语法一览</h3>
 * <pre>
 *  1.  VALUE IS PRESENT IF ANY &lt;anyConditions&gt; IF ALL &lt;allConditions&gt;   → NESTED_CONDITION
 *  2.  VALUE IS PRESENT IF @field IS PRESENT|ABSENT                       → MANDATORY_IF
 *  3.  VALUE IS ABSENT  IF @field IS PRESENT|ABSENT                       → FORBIDDEN_IF
 *  4.  VALUE IS PRESENT IF ANY &lt;conditions&gt;                              → MANDATORY_IF_ANY
 *  5.  VALUE IS ABSENT  IF ALL &lt;conditions&gt;                              → FORBIDDEN_IF_ALL
 *  6.  VALUE IS PRESENT IF ALL &lt;conditions&gt;                              → MANDATORY_IF_ALL
 *  7.  VALUE IS ABSENT  IF ANY &lt;conditions&gt;                              → FORBIDDEN_IF_ANY
 *  8.  COUNT(@listField, @targetField) op N                               → COUNT_AGGREGATE
 *  9.  SUM(@listField, @field) op N|VALUE                                 → SUM_AGGREGATE
 * 10.  VALUE IN [v1, v2, ...]                                             → VALUE_IN
 * 11.  VALUE = /regex/                                                    → VALUE_REGEX
 * 12.  VALUE op literal                                                   → VALUE_COMPARE
 * 13.  VALUE IS PRESENT                                                   → VALUE_IS_PRESENT
 * 14.  VALUE IS ABSENT                                                    → VALUE_IS_ABSENT
 * 15.  @TableName=&gt;VALUE IS NUMBERED                                      → VALUE_IS_NUMBERED
 * 16.  VALUE op @fieldName                                                → VALUE_FIELD_COMPARE
 * 17.  COUNT(@listField, @condField IN [vals]) = VALUE                    → COUNT_AS_VALUE
 * 18.  @TableName=&gt;COUNT(VALUE IN [vals]) op N                           → LIST_COUNT
 * 19.  VALUE = /regex/ IF ALL &lt;conditions&gt;                               → CONDITIONAL_REGEX
 * 20.  VALUE op @fieldName IF ALL &lt;conditions&gt;                           → CONDITIONAL_FIELD_COMPARE
 * 21.  VALUE op N IF ALL &lt;conditions&gt;                                    → CONDITIONAL_VALUE_COMPARE
 * 22.  VALUE = ANY @listField.fieldName                                   → VALUE_IN_LIST_FIELD
 * 23.  @TableName=&gt;VALUE IS UNIQUE                                        → LIST_UNIQUE
 * 24.  VALUE IS PRESENT|ABSENT IF [@preCond AND] COUNT(f IN [v]) op N     → CONDITIONAL_COUNT_AGGREGATE  ★新增
 * </pre>
 */
public class FinalRuleParser {

    // ===== 规则编号前缀 =====
    private static final Pattern RULE_ID_PATTERN = Pattern.compile("^R(\\w+)\\s*:\\s*(.*)$");

    // ===== 嵌套条件（最高优先级）=====
    private static final Pattern NESTED_ANY_ALL_PATTERN =
            Pattern.compile(
                    "VALUE\\s+(IS\\s+PRESENT|IS\\s+ABSENT|=\\s+/[^/]+/)\\s+IF\\s+ANY\\s+(.+?)\\s+IF\\s+ALL\\s+(.+)",
                    Pattern.CASE_INSENSITIVE);

    private static final Pattern VALUE_IS_PRESENT_IF_REF_PATTERN =
            Pattern.compile(
                    "VALUE\\s+IS\\s+PRESENT\\s+IF\\s+@(\\w+)\\s+IS\\s+(PRESENT|ABSENT)",
                    Pattern.CASE_INSENSITIVE);

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

    // ===== SUM_EQUALS_FIELDS =====
    private static final Pattern SUM_EQUALS_FIELDS_PATTERN =
            Pattern.compile(
                    "VALUE\\s*=\\s*SUM\\s*\\(\\s*(@?[\\w.]+(?:\\s*,\\s*@?[\\w.]+)*)\\s*\\)",
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
    // compareValue 不能以 / 开头（正则）或 @ 开头（字段引用），这两种情况有专用 pattern 处理
    private static final Pattern VALUE_COMPARE_PATTERN =
            Pattern.compile("VALUE\\s+(>=|<=|>|<|=|!=)\\s+([^/\\s@][^\\s]*)",
                    Pattern.CASE_INSENSITIVE);

    // ===== 存在性 =====
    private static final Pattern VALUE_IS_PRESENT_PATTERN =
            Pattern.compile("VALUE\\s+IS\\s+PRESENT", Pattern.CASE_INSENSITIVE);
    private static final Pattern VALUE_IS_ABSENT_PATTERN =
            Pattern.compile("VALUE\\s+IS\\s+ABSENT", Pattern.CASE_INSENSITIVE);

    // ===== 列表连续编号 =====
    // 格式: @TableName=>VALUE IS NUMBERED
    private static final Pattern VALUE_IS_NUMBERED_PATTERN =
            Pattern.compile("^@([\\w.]+)\\s*=>\\s*VALUE\\s+IS\\s+NUMBERED$",
                    Pattern.CASE_INSENSITIVE);

    // ===== 跨字段值比较 =====
    // 格式1: VALUE >= @fieldName  /  VALUE != @fieldName  等（标准带@前缀）
    // 格式2: VALUE < FieldName?  （官方规则中无@前缀、末尾带?的可选字段引用写法）
    private static final Pattern VALUE_FIELD_COMPARE_PATTERN =
            Pattern.compile("VALUE\\s+(>=|<=|>|<|=|!=)\\s+@([\\w.]+)",
                    Pattern.CASE_INSENSITIVE);

    // 无 @ 前缀的跨字段比较（末尾可带 ?），必须在 VALUE_COMPARE 之前匹配
    // 用途: ActualMass → VALUE < TechnicallyPermissibleMaximumLadenMass?
    private static final Pattern VALUE_FIELD_COMPARE_NO_AT_PATTERN =
            Pattern.compile("VALUE\\s+(>=|<=|>|<|=|!=)\\s+([A-Z][\\w.]*\\??)",
                    Pattern.CASE_INSENSITIVE);

    // ===== VALUE = COUNT(field IN [vals])（带条件的列表计数赋值）=====
    // 格式: VALUE = COUNT(FieldName IN ['val1', 'val2'])  或  VALUE = COUNT(@FieldName IN ['val1', 'val2'])
    // 用途: NumberOfPoweredAxles → VALUE = COUNT(@PoweredAxleIndicator IN ['Y'])
    // 注意: 必须在 VALUE_COUNT_SIMPLE 和 VALUE_COMPARE 之前匹配
    private static final Pattern VALUE_COUNT_WITHIN_PATTERN =
            Pattern.compile(
                    "VALUE\\s*=\\s*COUNT\\s*\\(\\s*@?(\\w+)\\s+IN\\s+\\[([^\\]]+)\\]\\s*\\)",
                    Pattern.CASE_INSENSITIVE);

    // ===== VALUE = COUNT(field)（简单列表计数赋值）=====
    // 格式: VALUE = COUNT(FieldName)  或  VALUE = COUNT(@FieldName)
    // 用途: NumberOfAxles → VALUE = COUNT(@AxleGroup)
    // 注意: 必须在 VALUE_COMPARE 之前匹配
    private static final Pattern VALUE_COUNT_SIMPLE_PATTERN =
            Pattern.compile(
                    "VALUE\\s*=\\s*COUNT\\s*\\(\\s*@?(\\w+)\\s*\\)",
                    Pattern.CASE_INSENSITIVE);

    // ===== COUNT = VALUE（列表字段计数赋值给当前字段）=====
    // 格式: COUNT(@listField, @conditionField IN [vals]) = VALUE
    // 用途: [103]-[106] NumberOfAxles* 类字段
    private static final Pattern COUNT_AS_VALUE_PATTERN =
            Pattern.compile(
                    "COUNT\\s*\\(@?([\\w.]+),\\s*@?([\\w.]+)\\s+IN\\s+\\[([^\\]]+)\\]\\)\\s*=\\s*VALUE",
                    Pattern.CASE_INSENSITIVE);

    // ===== LIST_COUNT（列表上下文内对 VALUE 做计数校验）=====
    // 格式: @TableName=>COUNT(VALUE IN [vals]) op threshold
    // 用途: [233] TyreFittedProductionIndicator
    private static final Pattern LIST_COUNT_PATTERN =
            Pattern.compile(
                    "^@([\\w.]+)\\s*=>\\s*COUNT\\s*\\(VALUE\\s+IN\\s+\\[([^\\]]+)\\]\\)\\s*(>=|<=|>|<|=|!=)\\s*(\\d+)$",
                    Pattern.CASE_INSENSITIVE);

    // ===== LIST_LAST_FORBIDDEN =====
    // 格式: @TableName=>VALUE IS ABSENT IF LAST
    private static final Pattern LIST_LAST_FORBIDDEN_PATTERN =
            Pattern.compile(
                    "^@([\\w.]+)\\s*=>\\s*VALUE\\s+IS\\s+ABSENT\\s+IF\\s+LAST$",
                    Pattern.CASE_INSENSITIVE);

    // ===== A. 条件数值比较（CONDITIONAL_VALUE_COMPARE）=====
    // 格式: VALUE op N IF ALL <conditions>
    // 用途: [127][130][129][132][133] Length/Width/Height 系列
    private static final Pattern CONDITIONAL_VALUE_COMPARE_PATTERN =
            Pattern.compile(
                    "VALUE\\s+(>=|<=|>|<|=|!=)\\s+([^\\s]+)\\s+IF\\s+ALL\\s+(.+)",
                    Pattern.CASE_INSENSITIVE);

    // ===== B. 条件正则（CONDITIONAL_REGEX）=====
    // 格式: VALUE = /regex/ IF ALL <conditions>
    // 用途: [463] TestFamilyIdentifierValue
    // 注意：必须在 CONDITIONAL_VALUE_COMPARE 之前匹配，因正则值含 /
    private static final Pattern CONDITIONAL_REGEX_PATTERN =
            Pattern.compile(
                    "VALUE\\s*=\\s*/([^/]+)/\\s+IF\\s+ALL\\s+(.+)",
                    Pattern.CASE_INSENSITIVE);

    // ===== C. 条件跨字段比较（CONDITIONAL_FIELD_COMPARE）=====
    // 格式: VALUE op @fieldName IF ALL <conditions>
    // 用途: [90] PreviousStageVersion
    private static final Pattern CONDITIONAL_FIELD_COMPARE_PATTERN =
            Pattern.compile(
                    "VALUE\\s+(>=|<=|>|<|=|!=)\\s+@([\\w.]+)\\s+IF\\s+ALL\\s+(.+)",
                    Pattern.CASE_INSENSITIVE);

    // ===== COUNT 动态阈值（与字段比较）=====
    // 格式: COUNT(@listField, @targetField) op @fieldName
    // 用途: COUNT(@AxleGroup, @AxleNumber) = @NumberOfAxles
    private static final Pattern COUNT_FIELD_PATTERN =
            Pattern.compile(
                    "COUNT\\s*\\(@?([^,]+?),\\s*@?([^)]+?)\\)\\s*(>=|<=|>|<|=|!=)\\s*@([\\w.]+)",
                    Pattern.CASE_INSENSITIVE);

    // ===== D. 列表字段成员检查（VALUE_IN_LIST_FIELD）=====
    // 格式: VALUE = ANY @listField.fieldName
    // 用途: [169] MechanicalCouplingNumberVerticalMass, [242] AxleNumberCombination
    private static final Pattern VALUE_IN_LIST_FIELD_PATTERN =
            Pattern.compile(
                    "VALUE\\s*=\\s*ANY\\s+@([\\w.]+)\\.([\\w.]+)",
                    Pattern.CASE_INSENSITIVE);

    // ===== E. 列表唯一性（LIST_UNIQUE）=====
    // 格式: @TableName=>VALUE IS UNIQUE
    // 用途: [252] Colour
    private static final Pattern LIST_UNIQUE_PATTERN =
            Pattern.compile(
                    "^@([\\w.]+)\\s*=>\\s*VALUE\\s+IS\\s+UNIQUE$",
                    Pattern.CASE_INSENSITIVE);

    // ===== ★ F. 带前置字段条件的 COUNT IN 存在性校验（CONDITIONAL_COUNT_AGGREGATE）=====
    //
    // 支持两种格式，用同一个 Pattern 统一匹配，运行时通过 group(2) 是否为空判断格式：
    //
    // 格式 A（前置字段条件 + COUNT，AND 连接）：
    //   VALUE IS PRESENT|ABSENT IF @field IS PRESENT|ABSENT AND COUNT(listField IN ['val']) op N
    //   示例: VALUE IS PRESENT IF @ConsolidatedMaximum30MinutesPower IS ABSENT
    //                          AND COUNT(EnergySource IN ['95']) > 1
    //
    // 格式 B（纯 COUNT 条件，无前置字段条件）：
    //   VALUE IS PRESENT|ABSENT IF COUNT(listField IN ['val']) op N
    //   示例: VALUE IS ABSENT IF COUNT(EnergySource IN ['95']) < 2
    //
    // ⚠️ 设计要点：
    //   - 原先拆分为两个 Pattern（格式A用 (.+?) 懒匹配，格式B独立）
    //     → 在 Java NFA 引擎中两个 Pattern 均可独立 fullmatch，但 parseRuleBody 里
    //       "格式B先于格式A检测"导致格式A的规则落入格式B分支时捕获组偏移，产生解析错误。
    //   - 现改为单一统一 Pattern：
    //       group(1) = "IS PRESENT" | "IS ABSENT"          → VALUE 存在性要求
    //       group(2) = 前置条件字符串（可为空串""）             → 非空则传给 ConditionChain.parseAll()
    //       group(3) = COUNT 的列表字段名（如 EnergySource）  → aggregateFunction.listField
    //       group(4) = IN 枚举值字符串（如 '95'）             → aggregateFunction.enumValues
    //       group(5) = COUNT 比较运算符（>, <, =, >=, <=, !=）→ aggregateFunction.operator
    //       group(6) = 阈值（整数，如 1、2）                   → aggregateFunction.threshold
    //   - group(2) 匹配"COUNT( 之前、IF 之后的所有字符"（含末尾的 "AND "，解析时 trim + 去尾 AND）
    //     使用 (.*?) 零次或多次懒匹配，确保格式B时 group(2) 为空串。
    //
    // ⚠️ 匹配优先级：必须在以下 Pattern 之前注册和调用：
    //   - VALUE_IS_PRESENT_IF_REF_PATTERN / VALUE_IS_ABSENT_IF_REF_PATTERN
    //     （格式A与它们共享 "VALUE IS PRESENT|ABSENT IF @" 前缀）
    //   - MANDATORY_IF_ANY/ALL / FORBIDDEN_IF_ANY/ALL
    //     （格式B不含 ANY/ALL 关键字，但 IF (.+) 的贪婪匹配会错误吃掉 COUNT 部分）
    //
    private static final Pattern CONDITIONAL_COUNT_AGG_PATTERN =
            Pattern.compile(
                    "VALUE\\s+(IS\\s+PRESENT|IS\\s+ABSENT)\\s+IF\\s+" +
                            "((?:(?!COUNT\\().)*?)" +
                            "COUNT\\(\\s*@?(\\w+)\\s+IN\\s+\\[([^\\[]+)]\\s*\\)" +
                            "\\s*(>=|<=|>|<|=|!=)\\s*(\\d+)\\s*$",
                    Pattern.CASE_INSENSITIVE);

    // ==========================================
    // 公开入口
    // ==========================================

    /**
     * 解析 rule 字段 + rangeRule 字段，返回完整规则列表。
     * 每行解析失败时返回 {@link RuleItemType#PARSE_ERROR} 类型的条目，不会中断整体解析。
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

        RuleItem item = parseRuleBody(body, line);
        if (item != null) {
            item.setRuleId(ruleId);
            if (item.getType() != RuleItemType.PARSE_ERROR) {
                item.setRawRule(line);
            }
        }
        return item;
    }

    /**
     * 解析规则体。
     * 所有无法识别的规则均返回 {@link RuleItemType#PARSE_ERROR} 类型的 RuleItem，
     * 携带原始规则文本和错误原因，供执行器生成校验报告。
     */
    private static RuleItem parseRuleBody(String body, String rawLine) {

        try {
            // 1. 嵌套条件：VALUE ... IF ANY ... IF ALL ...
            RuleItem nestedItem = parseNestedAnyAll(body);
            if (nestedItem != null) {
                return nestedItem;
            }

            // ★ 2. CONDITIONAL_COUNT_AGGREGATE（统一 Pattern，格式A/B 均由此处理）
            //    格式A: group(2) 为前置条件串（如 "@field IS ABSENT AND "），解析时去尾 AND
            //    格式B: group(2) 为空串 ""，conditionChain = null，直接执行 COUNT 校验
            //    必须在 VALUE_IS_PRESENT/ABSENT_IF_REF 和 MANDATORY/FORBIDDEN_IF_ANY/ALL 之前匹配
            Matcher m = CONDITIONAL_COUNT_AGG_PATTERN.matcher(body);
            if (m.matches()) {
                return parseConditionalCountAgg(m, rawLine);
            }

            m = VALUE_IS_PRESENT_IF_REF_PATTERN.matcher(body);
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

            // 4. MANDATORY_IF ANY
            m = MANDATORY_IF_ANY_PATTERN.matcher(body);
            if (m.matches()) {
                return RuleItem.builder()
                        .type(RuleItemType.MANDATORY_IF_ANY)
                        .conditionChain(ConditionChain.parseAny(m.group(1).trim()))
                        .build();
            }

            // 5. FORBIDDEN_IF ALL
            m = FORBIDDEN_IF_ALL_PATTERN.matcher(body);
            if (m.matches()) {
                return RuleItem.builder()
                        .type(RuleItemType.FORBIDDEN_IF_ALL)
                        .conditionChain(ConditionChain.parseAll(m.group(1).trim()))
                        .build();
            }

            // 6. MANDATORY_IF ALL
            m = MANDATORY_IF_ALL_PATTERN.matcher(body);
            if (m.matches()) {
                return RuleItem.builder()
                        .type(RuleItemType.MANDATORY_IF_ALL)
                        .conditionChain(ConditionChain.parseAll(m.group(1).trim()))
                        .build();
            }

            // 7. FORBIDDEN_IF ANY
            m = FORBIDDEN_IF_ANY_PATTERN.matcher(body);
            if (m.matches()) {
                return RuleItem.builder()
                        .type(RuleItemType.FORBIDDEN_IF_ANY)
                        .conditionChain(ConditionChain.parseAll(m.group(1).trim()))
                        .build();
            }

            // 8-a. COUNT 动态阈值：COUNT(@listField, @targetField) op @fieldName
            m = COUNT_FIELD_PATTERN.matcher(body);
            if (m.matches()) {
                String listField   = m.group(1).trim().replace("@", "");
                String targetField = m.group(2).trim().replace("@", "");
                String operator    = m.group(3).trim();
                String refField    = m.group(4).trim();
                AggregateFunction af = AggregateFunction.builder()
                        .functionType(AggregateFunction.Type.COUNT)
                        .listField(listField)
                        .condition(targetField)
                        .operator(CompareOperator.fromSymbol(operator))
                        .threshold(null)          // 动态阈值，threshold 留 null
                        .build();
                return RuleItem.builder()
                        .type(RuleItemType.COUNT_AGGREGATE_FIELD)
                        .aggregateFunction(af)
                        .refFieldName(refField)   // 动态阈值字段名存入 refFieldName
                        .build();
            }

            // 8-b. COUNT
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

            // 9. SUM
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

            // 10. VALUE IN [...]
            m = VALUE_IN_PATTERN.matcher(body);
            if (m.matches()) {
                return RuleItem.builder()
                        .type(RuleItemType.VALUE_IN)
                        .enumValues(parseList(m.group(1)))
                        .build();
            }

            // 11-a. VALUE = /regex/ IF ALL <conditions>（条件正则，必须在裸正则之前）
            m = CONDITIONAL_REGEX_PATTERN.matcher(body);
            if (m.matches()) {
                return RuleItem.builder()
                        .type(RuleItemType.CONDITIONAL_REGEX)
                        .regexPattern(m.group(1).trim())
                        .conditionChain(ConditionChain.parseAll(m.group(2).trim()))
                        .build();
            }

            // 11. VALUE = /regex/
            m = VALUE_REGEX_PATTERN.matcher(body);
            if (m.matches()) {
                return RuleItem.builder()
                        .type(RuleItemType.VALUE_REGEX)
                        .regexPattern(m.group(1))
                        .build();
            }

            // 12-a. VALUE = COUNT(field IN [vals])（带条件列表计数，必须在VALUE_COMPARE前）
            m = VALUE_COUNT_WITHIN_PATTERN.matcher(body);
            if (m.matches()) {
                String listField = m.group(1).trim();
                List<String> withinVals = parseList(m.group(2));
                AggregateFunction af = AggregateFunction.builder()
                        .functionType(AggregateFunction.Type.COUNT)
                        .listField(listField)
                        .enumValues(withinVals)
                        .operator(com.ruoyi.common.core.enums.CompareOperator.EQ)
                        .threshold(null)
                        .build();
                return RuleItem.builder()
                        .type(RuleItemType.COUNT_AS_VALUE)
                        .aggregateFunction(af)
                        .build();
            }

            // 12-b. VALUE = COUNT(field)（简单列表计数，必须在VALUE_COMPARE前）
            m = VALUE_COUNT_SIMPLE_PATTERN.matcher(body);
            if (m.matches()) {
                String listField = m.group(1).trim();
                AggregateFunction af = AggregateFunction.builder()
                        .functionType(AggregateFunction.Type.COUNT)
                        .listField(listField)
                        .operator(com.ruoyi.common.core.enums.CompareOperator.EQ)
                        .threshold(null)
                        .build();
                return RuleItem.builder()
                        .type(RuleItemType.COUNT_AS_VALUE)
                        .aggregateFunction(af)
                        .build();
            }

            // 12-c. VALUE op FieldName?（无@前缀跨字段比较，必须在VALUE_COMPARE前）
            m = VALUE_FIELD_COMPARE_NO_AT_PATTERN.matcher(body);
            if (m.matches()) {
                String refName = m.group(2).trim();
                if (refName.endsWith("?")) {
                    refName = refName.substring(0, refName.length() - 1);
                }
                return RuleItem.builder()
                        .type(RuleItemType.VALUE_FIELD_COMPARE)
                        .operator(m.group(1).trim())
                        .refFieldName(refName)
                        .build();
            }

            // VALUE = SUM(@f1, @f2, @f3, @f4)
            m = SUM_EQUALS_FIELDS_PATTERN.matcher(body);
            if (m.matches()) {
                String[] parts = m.group(1).split(",");
                List<String> fields = new ArrayList<>();
                for (String p : parts) {
                    fields.add(p.trim().replace("@", ""));
                }
                return RuleItem.builder()
                        .type(RuleItemType.SUM_EQUALS_FIELDS)
                        .enumValues(fields)   // 复用 enumValues 存字段列表
                        .build();
            }

            // 12. VALUE 比较运算（字面量比较，必须在所有字段引用/COUNT之后）
            m = VALUE_COMPARE_PATTERN.matcher(body);
            if (m.matches()) {
                return RuleItem.builder()
                        .type(RuleItemType.VALUE_COMPARE)
                        .operator(m.group(1).trim())
                        .compareValue(m.group(2).trim())
                        .build();
            }

            // 13. VALUE IS PRESENT
            if (VALUE_IS_PRESENT_PATTERN.matcher(body).matches()) {
                return RuleItem.builder()
                        .type(RuleItemType.VALUE_IS_PRESENT)
                        .operator("IS_PRESENT")
                        .build();
            }

            // 14. VALUE IS ABSENT
            if (VALUE_IS_ABSENT_PATTERN.matcher(body).matches()) {
                return RuleItem.builder()
                        .type(RuleItemType.VALUE_IS_ABSENT)
                        .operator("IS_ABSENT")
                        .build();
            }

            // 15. TableName=>VALUE IS NUMBERED
            m = VALUE_IS_NUMBERED_PATTERN.matcher(body);
            if (m.matches()) {
                return RuleItem.builder()
                        .type(RuleItemType.VALUE_IS_NUMBERED)
                        .refFieldName(m.group(1).trim())
                        .build();
            }

            // 16-a. VALUE op @fieldName IF ALL <conditions>（条件跨字段比较）
            m = CONDITIONAL_FIELD_COMPARE_PATTERN.matcher(body);
            if (m.matches()) {
                return RuleItem.builder()
                        .type(RuleItemType.CONDITIONAL_FIELD_COMPARE)
                        .operator(m.group(1).trim())
                        .refFieldName(m.group(2).trim())
                        .conditionChain(ConditionChain.parseAll(m.group(3).trim()))
                        .build();
            }

            // 16-b. VALUE op N IF ALL <conditions>（条件数值比较）
            m = CONDITIONAL_VALUE_COMPARE_PATTERN.matcher(body);
            if (m.matches()) {
                return RuleItem.builder()
                        .type(RuleItemType.CONDITIONAL_VALUE_COMPARE)
                        .operator(m.group(1).trim())
                        .compareValue(m.group(2).trim())
                        .conditionChain(ConditionChain.parseAll(m.group(3).trim()))
                        .build();
            }

            // 16. VALUE op @fieldName（跨字段值比较，标准带 @ 前缀）
            m = VALUE_FIELD_COMPARE_PATTERN.matcher(body);
            if (m.matches()) {
                return RuleItem.builder()
                        .type(RuleItemType.VALUE_FIELD_COMPARE)
                        .operator(m.group(1).trim())
                        .refFieldName(m.group(2).trim())
                        .build();
            }

            // 17. COUNT(@listField, @conditionField IN [vals]) = VALUE
            m = COUNT_AS_VALUE_PATTERN.matcher(body);
            if (m.matches()) {
                AggregateFunction af = AggregateFunction.builder()
                        .functionType(AggregateFunction.Type.COUNT)
                        .listField(m.group(1).trim())
                        .field(m.group(2).trim())
                        .enumValues(parseList(m.group(3)))
                        .operator(com.ruoyi.common.core.enums.CompareOperator.EQ)
                        .threshold(null)
                        .build();
                return RuleItem.builder()
                        .type(RuleItemType.COUNT_AS_VALUE)
                        .aggregateFunction(af)
                        .build();
            }

            m = LIST_LAST_FORBIDDEN_PATTERN.matcher(body);
            if (m.matches()) {
                return RuleItem.builder()
                        .type(RuleItemType.LIST_LAST_FORBIDDEN)
                        .refFieldName(m.group(1).trim())  // 列表名，如 AxleGroup
                        .build();
            }

            // 18. @TableName=>COUNT(VALUE IN [vals]) op threshold
            m = LIST_COUNT_PATTERN.matcher(body);
            if (m.matches()) {
                AggregateFunction af = AggregateFunction.builder()
                        .functionType(AggregateFunction.Type.COUNT)
                        .listField(m.group(1).trim())
                        .enumValues(parseList(m.group(2)))
                        .operator(com.ruoyi.common.core.enums.CompareOperator.fromSymbol(m.group(3).trim()))
                        .threshold(Double.parseDouble(m.group(4).trim()))
                        .build();
                return RuleItem.builder()
                        .type(RuleItemType.LIST_COUNT)
                        .aggregateFunction(af)
                        .build();
            }

            // 19. VALUE = ANY @listField.fieldName（列表成员检查）
            m = VALUE_IN_LIST_FIELD_PATTERN.matcher(body);
            if (m.matches()) {
                return RuleItem.builder()
                        .type(RuleItemType.VALUE_IN_LIST_FIELD)
                        .refFieldName(m.group(1).trim())
                        .compareValue(m.group(2).trim())
                        .build();
            }

            // 20. @TableName=>VALUE IS UNIQUE（列表唯一性校验）
            m = LIST_UNIQUE_PATTERN.matcher(body);
            if (m.matches()) {
                return RuleItem.builder()
                        .type(RuleItemType.LIST_UNIQUE)
                        .refFieldName(m.group(1).trim())
                        .build();
            }

            // 所有模式均未匹配 —— 封装为解析错误条目
            return buildParseErrorItem(rawLine, "规则格式无法识别");

        } catch (Exception e) {
            return buildParseErrorItem(rawLine, "规则解析异常: " + e.getMessage());
        }
    }

    // ==========================================
    // ★ 新增：CONDITIONAL_COUNT_AGGREGATE 解析辅助方法
    // ==========================================

    /**
     * 解析格式A：前置字段条件 + COUNT IN 存在性校验
     *
     * <p>示例：
     * {@code VALUE IS PRESENT IF @ConsolidatedMaximum30MinutesPower IS ABSENT AND COUNT(EnergySource IN ['95']) > 1}
     *
     * <p>构建的 RuleItem 字段：
     * <ul>
     *   <li>{@code type}              = CONDITIONAL_COUNT_AGGREGATE</li>
     *   <li>{@code operator}          = "IS_PRESENT" | "IS_ABSENT"（VALUE 的存在性要求）</li>
     *   <li>{@code conditionChain}    = 前置字段条件链（ConditionChain.parseAll 解析）</li>
     *   <li>{@code aggregateFunction} = COUNT 的配置（listField / enumValues / operator / threshold）</li>
     * </ul>
     */
    /**
     * 解析 CONDITIONAL_COUNT_AGGREGATE（格式A/B 统一入口）
     *
     * <p>捕获组说明（来自 CONDITIONAL_COUNT_AGG_PATTERN）：
     * <ul>
     *   <li>group(1) = "IS PRESENT" | "IS ABSENT"  → VALUE 存在性要求，标准化为 IS_PRESENT/IS_ABSENT</li>
     *   <li>group(2) = 前置条件字符串（可为空串）      → 非空则去尾 "AND" 后传给 ConditionChain.parseAll()</li>
     *   <li>group(3) = COUNT 列表字段名              → aggregateFunction.listField</li>
     *   <li>group(4) = IN 枚举值字符串               → aggregateFunction.enumValues</li>
     *   <li>group(5) = COUNT 比较运算符              → aggregateFunction.operator</li>
     *   <li>group(6) = 阈值（整数）                  → aggregateFunction.threshold</li>
     * </ul>
     *
     * <p>格式A示例（group(2) 非空）：
     * {@code VALUE IS PRESENT IF @ConsolidatedMaximum30MinutesPower IS ABSENT AND COUNT(EnergySource IN ['95']) > 1}
     *
     * <p>格式B示例（group(2) 为空串）：
     * {@code VALUE IS ABSENT IF COUNT(EnergySource IN ['95']) < 2}
     */
    private static RuleItem parseConditionalCountAgg(Matcher m, String rawLine) {
        String presence   = normalizePresence(m.group(1).trim());
        String preCondRaw = m.group(2).trim();
        String listField  = m.group(3).trim().replace("@", "");  // ★ 去掉可能残留的 @
        List<String> vals = parseList(m.group(4));
        String countOp    = m.group(5).trim();
        int threshold     = Integer.parseInt(m.group(6).trim());

        String preCondStr = preCondRaw.replaceAll("(?i)\\s+AND\\s*$", "").trim();
        ConditionChain preChain = preCondStr.isEmpty()
                ? null
                : ConditionChain.parseAll(preCondStr);

        AggregateFunction af = AggregateFunction.builder()
                .functionType(AggregateFunction.Type.COUNT)
                .listField(listField)
                .enumValues(vals)
                .operator(CompareOperator.fromSymbol(countOp))
                .threshold((double) threshold)
                .build();

        return RuleItem.builder()
                .type(RuleItemType.CONDITIONAL_COUNT_AGGREGATE)
                .operator(presence)
                .conditionChain(preChain)
                .aggregateFunction(af)
                .rawRule(rawLine)
                .build();
    }

    private static String normalizePresence(String raw) {
        if (raw.toUpperCase().contains("PRESENT")) return "IS_PRESENT";
        if (raw.toUpperCase().contains("ABSENT"))  return "IS_ABSENT";
        return raw.toUpperCase().replace(" ", "_");
    }

    // ==========================================
    // 工具方法
    // ==========================================

    /**
     * 构建解析错误条目。
     */
    private static RuleItem buildParseErrorItem(String rawLine, String reason) {
        return RuleItem.builder()
                .type(RuleItemType.PARSE_ERROR)
                .rawRule(rawLine)
                .errorMessageEn("Rule parse failed: " + reason + " [raw=" + rawLine + "]")
                .errorMessageZh("规则解析失败: " + reason + " [原始规则=" + rawLine + "]")
                .build();
    }

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

        if (c.getMin() != null || c.getMax() != null) {
            items.add(RuleItem.builder()
                    .type(RuleItemType.NUMERIC_RANGE)
                    .rangeMin(c.getMin())
                    .rangeMax(c.getMax())
                    .rawRule(c.toString())
                    .build());
        }

        if (c.getTotalDigits() != null) {
            items.add(RuleItem.builder()
                    .type(RuleItemType.TOTAL_DIGITS)
                    .totalDigits(c.getTotalDigits())
                    .rawRule(c.toString())
                    .build());
        }

        if (c.getFractionDigits() != null) {
            items.add(RuleItem.builder()
                    .type(RuleItemType.FRACTION_DIGITS)
                    .fractionDigits(c.getFractionDigits())
                    .rawRule(c.toString())
                    .build());
        }

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

    /**
     * 【F类修复】多值字段拆分工具
     */
    public static String[] splitMultiValue(String value) {
        if (value == null || value.isEmpty()) {
            return new String[]{value};
        }
        if (value.contains("|")) {
            return value.split("\\|", -1);
        }
        if (value.contains(";")) {
            return value.split(";", -1);
        }
        return new String[]{value};
    }

    /**
     * 判断字段值是否为多值字段（含 | 或 ; 分隔符）
     */
    public static boolean isMultiValue(String value) {
        return value != null && (value.contains("|") || value.contains(";"));
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
        Pattern startPattern = Pattern.compile(
                "^VALUE\\s+(IS\\s+PRESENT|IS\\s+ABSENT|=\\s+/[^/]+/)\\s+IF\\s+ANY\\s*",
                Pattern.CASE_INSENSITIVE);
        Matcher startMatcher = startPattern.matcher(body);
        if (!startMatcher.find()) {
            return null;
        }
        String opPart = startMatcher.group(1).trim();
        int afterAnyPos = startMatcher.end();

        String rest = body.substring(afterAnyPos);
        int allIndex = findIfAllIndex(rest);
        if (allIndex == -1) {
            return null;
        }

        String anyCond = rest.substring(0, allIndex).trim();
        String allCond = rest.substring(allIndex + " IF ALL ".length()).trim();

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

    private static int findIfAllIndex(String str) {
        int idx = str.indexOf(" IF ALL ");
        if (idx != -1) return idx;
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