package com.ruoyi.common.core.parser;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.core.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 上游数据 → eCoC 字段值映射解析器
 *
 * <p>根据 sys_dict_data 中 {@code value_map} 字段存储的映射规则描述符，
 * 将上游原始值（K 列）转换为 eCoC 目标值（L 列）。
 *
 * <h2>value_map 字段格式（存入数据库）</h2>
 * <pre>
 *   DIRECT                          直接透传，不做转换
 *   NULL                            固定输出 null / 空
 *   STRIP_UNIT                      去掉数值后的单位，保留纯数字（支持小数）
 *   STRIP_UNIT:N                    去掉单位并取第 N 个数值（从1开始，多值场景）
 *   EXTRACT_NUMBER:N                从文本中提取第 N 个普通数字（整数/小数）
 *   EXTRACT_EXPONENT                从文本中提取指数值，自动适配以下格式：
 *                                     · 科学计数法：8.04E+11 / 8.04e+11 / 5.6E11
 *                                     · Unicode上标：10¹¹ / 3.5×10⁸
 *                                     · 括号注释：（11上标）/ (11上标)
 *                                     · 脱字符：^11
 *   EXTRACT_PATTERN:{regex}:{group} 用正则提取分组
 *   DATE_FORMAT:{inputFmt}          日期格式转换，输入格式→xs:date (yyyy-MM-dd)
 *   DATETIME_FORMAT:{inputFmt}      日期时间转换，输入格式→xs:dateTime (yyyy-MM-dd'T'HH:mm:ss)
 *   SPLIT_JOIN:{inSep}:{outSep}     分隔符替换（如 ; → /）
 *   SPLIT_TAKE:{sep}:{index}        按分隔符切分后取第 index 个（从0开始）
 *   SPLIT_MULTIROW:{sep}            按分隔符切分，每项占一行（换行符 \n 分隔）
 *   ENUM:{k1=v1,k2=v2,...}          枚举映射表（上游值→目标值）
 *   PREFIX_STRIP:{prefix}           去掉固定前缀后取剩余
 *   SUFFIX_STRIP:{suffix}           去掉固定后缀后取剩余
 *   SUBSTRING:{start}:{end}         字符串截取（end=-1 表示到末尾）
 *   DICT_MAP                        从 value_connection 字段解析的合并映射表中查找目标值。
 *                                     调用方须使用 {@link #convertWithDictMap} 并传入
 *                                     由 {@link #mergeValueConnection} 生成的映射表。
 *                                     value_connection 格式：
 *                                     {"来源A":{"原值":"目标值",...},"来源B":{...}}
 *                                     示例（精确 key）：value_map = "DICT_MAP"，rawValue = "法国"
 *                                           → 合并后映射表中查找"法国"返回"F"
 *                                     支持范围 key（精确匹配未命中时自动降级）：
 *                                       key 格式（变量名任意，大小写不限）：
 *                                         双侧：6.6<=RRC<=7.7 / 6.6<RRC<7.7
 *                                         单侧：RRC<=7.7 / 6.6<=RRC
 *                                       从 rawValue 提取第1个数字后逐 key 匹配范围；
 *                                       所有范围均未命中时再尝试兜底键 *。
 *                                     示例（范围 key）：rawValue = "6.8N/kN"
 *                                           → 提取 6.8 → 命中"6.6<=RRC<=7.7" → 返回"B"
 *  STRIP_UNIT_JOIN:{inSep}:{outSep} 按 inSep 拆分，每项提取第1个数字，再用 outSep 拼接
 *  GROUP_JOIN_SEP:{inSep}:{outSep}  不做值转换，仅修改当前 uuid 组的多值拼接符。
 *  RANGE_MAP:{条件1}={目标值1};{条件2}={目标值2};...
 *                                    按数值范围映射。先从原值提取第1个数字，再逐条匹配范围表达式。
 *                                    条件格式：{lo}{op1}VALUE{op2}{hi}，支持 <= 和 < 两种比较符。
 *                                    也支持单侧条件：VALUE<={hi} 或 {lo}<=VALUE。
 *                                    * 作为兜底默认值键（未命中时返回）。
 *                                    示例：RANGE_MAP:6.6<=VALUE<=7.7=B;7.7<VALUE<=8.8=C;*=D
 *                                          原值 "6.8N/kN" → 提取 6.8 → 命中 6.6<=VALUE<=7.7 → 返回 "B"
 *  RIM_SPEC:BOTH                     提取轮毂规格235/50R19 103V 19x7J ET47 6.28N/kN C1
 *                                              215/55R18 99H 18x7 1/2J ET33 5.96N/kN C1
 *                                    输出示例：19,7  /  18,7.5
 *  AXIS_DRIVE                        格式：AXIS_DRIVE:{sep}:{trueVal}:{falseVal}:{keyword1}:{keyword2}
 *                                    默认：sep=; trueVal=Y falseVal=N keyword1=front keyword2=rear
 *  管道链式执行：PIPE:{rule1}|{rule2}|  将多个 value_map 规则串联，前一步输出作为下一步输入
 * </pre>
 *
 * <h2>数据库存储约定（value_map 列 ≤ 100 字符）</h2>
 * <ul>
 *   <li>枚举项过多时，将映射表单独建一张字典或 JSON 列，value_map 改存枚举 dict_type 引用键</li>
 *   <li>正则中不允许出现冒号 {@code :}，如需匹配冒号请用 {@code \x3A}</li>
 * </ul>
 */
@Slf4j
public class ValueMappingParser {

    // 哨兵值，区分"转换返回null（出错/未命中）"和"规则就是要置空"
    public static final String EMPTY_SENTINEL = "\u0000__NULL__\u0000";

    // ── 日期格式 ──────────────────────────────────────────────────
    private static final DateTimeFormatter XSD_DATE     = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter XSD_DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /** 常见上游日期格式 */
    private static final List<DateTimeFormatter> COMMON_DATE_FORMATS = Arrays.asList(
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("MM/dd/yyyy"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy")
    );

    // ── 数字提取 ──────────────────────────────────────────────────
    /** 匹配整数或小数（含负号） */
    private static final Pattern NUMBER_PATTERN =
            Pattern.compile("-?\\d+(?:\\.\\d+)?");

    // ── 指数提取：五种格式 ────────────────────────────────────────
    /**
     * 策略1 — 科学计数法：8.04E+11 / 8.04e+11 / 5.6E11 / 1.2e-3
     * group(1) = 指数数字串
     */
    private static final Pattern SCI_NOTATION_PATTERN =
            Pattern.compile("\\d+(?:\\.\\d+)?[Ee][+\\-]?(\\d+)");

    /**
     * 策略2 — ×10 后跟 Unicode 上标数字（含有/无乘号两种）：
     *   3.5×10⁸  /  10¹¹/km
     * group(1) = 连续上标字符串
     */
    private static final Pattern UNICODE_SUP_PATTERN =
            Pattern.compile("(?:[×xX*]\\s*)?10([\\u00B9\\u00B2\\u00B3\\u2070\\u2074-\\u2079\\u207A\\u207B]+)");

    /**
     * 策略3 — 括号注释（中文/英文括号）：（11上标）/ (11上标)
     * group(1) = 指数数字串
     */
    private static final Pattern ANNOTATED_SUP_PATTERN =
            Pattern.compile("[（(](\\d+)上标[）)]");

    /**
     * 策略4 — 脱字符：^11 / ^{11}
     * group(1) = 指数数字串
     */
    private static final Pattern CARET_SUP_PATTERN =
            Pattern.compile("\\^\\{?(\\d+)\\}?");

    // 策略5：10后直接跟负号+数字：10-2 / 10-3
    private static final Pattern PLAIN_NEG_EXP_PATTERN =
            Pattern.compile("10(-\\d+)");

    /** Unicode 上标数字 → ASCII 数字 映射 */
    private static final Map<Character, Character> SUPERSCRIPT_MAP;
    static {
        Map<Character, Character> m = new LinkedHashMap<>();
        m.put('⁰', '0'); m.put('¹', '1'); m.put('²', '2');
        m.put('³', '3'); m.put('⁴', '4'); m.put('⁵', '5');
        m.put('⁶', '6'); m.put('⁷', '7'); m.put('⁸', '8');
        m.put('⁹', '9'); m.put('⁺', '+'); m.put('⁻', '-');
        SUPERSCRIPT_MAP = Collections.unmodifiableMap(m);
    }

    // =====================================================
    //  公开入口
    // =====================================================

    /**
     * 根据 value_map 规则描述符将上游原始值转换为 eCoC 目标值。
     *
     * <p><b>注意：</b>当 {@code valueMap} 为 {@code "DICT_MAP"} 时，
     * 此方法无法处理，请改用 {@link #convertWithDictMap(String, String, Map)}。
     *
     * @param rawValue 上游原始值（K 列）
     * @param valueMap 映射规则描述符（sys_dict_data.value_map）
     * @return 转换后的目标值字符串；无法转换时返回 null
     */
    public static String convert(String rawValue, String valueMap) {
        if (valueMap == null || StringUtils.isBlank(valueMap)) {
            return rawValue;
        }

        String raw = (rawValue == null) ? "" : rawValue.trim();
        String descriptor = valueMap.trim();

        try {
            // 按首段关键字分发
            String safeDescriptor = descriptor
                    .replace("\\x3A", "\u0001")
                    .replace("(?:", "\u0002")
                    .replace("(?=", "\u0003")
                    .replace("(?!", "\u0004")
                    .replace("(?<=", "\u0005")
                    .replace("(?<!", "\u0006");
            String[] parts = safeDescriptor.split(":", 7);
            String type = parts[0].toUpperCase();

            switch (type) {

                // ── 直传 ──────────────────────────────────────────
                case "DIRECT":
                    return raw.isEmpty() ? null : raw;

                // ── 固定空值 ─────────────────────────────────────
                case "NULL":
                    return EMPTY_SENTINEL;

                // ── 去单位取纯数字 ────────────────────────────────
                case "STRIP_UNIT": {
                    int index = (parts.length >= 2) ? parseIndex(parts[1], 1) : 1;
                    return extractNthNumber(raw, index);
                }

                case "STRIP_UNIT_JOIN": {
                    // STRIP_UNIT_JOIN:{inSep}:{outSep}
                    // 按 inSep 拆分，每项提取第1个数字，再用 outSep 拼接
                    if (parts.length < 3) return raw;
                    String inSep  = unescapeSep(parts[1]);
                    String outSep = unescapeSep(parts[2]);
                    String[] items = raw.split(Pattern.quote(inSep), -1);
                    StringJoiner sj = new StringJoiner(outSep);
                    for (String item : items) {
                        String num = extractNthNumber(item.trim(), 1);
                        if (num != null) sj.add(num);
                    }
                    String joined = sj.toString();
                    return joined.isEmpty() ? null : joined;
                }

                // ── 从复杂文本提取第 N 个数字 ─────────────────────
                case "EXTRACT_NUMBER": {
                    int index = (parts.length >= 2) ? parseIndex(parts[1], 1) : 1;
                    return extractNthNumber(raw, index);
                }

                // ── 提取指数（科学计数 / Unicode上标 / 括号注释 / 脱字符）──
                case "EXTRACT_EXPONENT":
                    return extractExponent(raw);

                // ── 正则提取分组 ──────────────────────────────────
                case "EXTRACT_PATTERN": {
                    // value_map = EXTRACT_PATTERN:{regex}:{group}
                    if (parts.length < 3) {
                        log.warn("[ValueMappingParser] EXTRACT_PATTERN 缺少参数: {}", descriptor);
                        return null;
                    }
                    String regex = parts[1]
                            .replace("\\\\", "\\")
                            .replace("\u0001", ":")
                            .replace("\u0002", "(?:")
                            .replace("\u0003", "(?=")
                            .replace("\u0004", "(?!")
                            .replace("\u0005", "(?<=")
                            .replace("\u0006", "(?<!");
                    int group   = parseIndex(parts[2], 1);
                    Matcher m = Pattern.compile(regex, Pattern.UNICODE_CHARACTER_CLASS).matcher(raw);
                    return m.find() ? m.group(group) : null;
                }

                // ── 日期格式转换 → xs:date ────────────────────────
                case "DATE_FORMAT": {
                    String inputFmt  = (parts.length >= 2) ? parts[1] : null;
                    String outputFmt = (parts.length >= 3) ? parts[2] : null;
                    return convertDate(raw, inputFmt, outputFmt);
                }

                // ── 日期时间格式转换 → xs:dateTime ────────────────
                case "DATETIME_FORMAT": {
                    String inputFmt  = (parts.length >= 2) ? parts[1] : null;
                    String outputFmt = (parts.length >= 3) ? parts[2] : null;
                    return convertDateTime(raw, inputFmt, outputFmt);
                }

                // ── 分隔符替换 ────────────────────────────────────
                case "SPLIT_JOIN": {
                    // value_map = SPLIT_JOIN:{inSep}:{outSep}
                    if (parts.length < 3) return raw;
                    String inSep  = unescapeSep(parts[1]);
                    String outSep = unescapeSep(parts[2]);
                    String[] items = raw.split(Pattern.quote(inSep), -1);
                    return String.join(outSep, items);
                }

                // ── 切分后取第 N 项 ───────────────────────────────
                case "SPLIT_TAKE": {
                    // value_map = SPLIT_TAKE:{sep}:{index}
                    if (parts.length < 3) return raw;
                    String sep   = unescapeSep(parts[1]);
                    int    idx   = parseIndex(parts[2], 0);
                    String[] arr = raw.split(Pattern.quote(sep), -1);
                    if (idx < 0) idx = arr.length + idx;          // 支持 -1 取最后一项
                    if (idx < 0 || idx >= arr.length) return null;
                    return arr[idx].trim();
                }

                // ── 切分为多行（\n 拼接） ─────────────────────────
                case "SPLIT_MULTIROW": {
                    // value_map = SPLIT_MULTIROW:{sep}
                    if (parts.length < 2) return raw;
                    String sep   = unescapeSep(parts[1]);
                    String[] arr = raw.split(Pattern.quote(sep), -1);
                    StringJoiner sj = new StringJoiner("\n");
                    for (String item : arr) {
                        String t = item.trim();
                        if (!t.isEmpty()) sj.add(t);
                    }
                    return sj.toString();
                }

                // ── 枚举映射 ──────────────────────────────────────
                case "ENUM": {
                    if (parts.length < 2) return null;
                    Map<String, String> enumMap = parseEnumMap(parts[1]);
                    String matched = enumMap.getOrDefault(raw, enumMap.getOrDefault("*", null));
                    // 支持将枚举值 __NULL__ 映射为 EMPTY_SENTINEL，用于主动置空
                    if ("__NULL__".equals(matched)) return EMPTY_SENTINEL;
                    return matched;
                }

                // ── 去掉前缀 ──────────────────────────────────────
                case "PREFIX_STRIP": {
                    if (parts.length < 2) return raw;
                    String prefix = parts[1];
                    return raw.startsWith(prefix) ? raw.substring(prefix.length()).trim() : raw;
                }

                // ── 去掉后缀 ──────────────────────────────────────
                case "SUFFIX_STRIP": {
                    if (parts.length < 2) return raw;
                    String suffix = parts[1];
                    return raw.endsWith(suffix)
                            ? raw.substring(0, raw.length() - suffix.length()).trim()
                            : raw;
                }

                // ── 字符串截取 ────────────────────────────────────
                case "SUBSTRING": {
                    // value_map = SUBSTRING:{start}:{end}  (end=-1 → 末尾)
                    if (parts.length < 3) return raw;
                    int start = Integer.parseInt(parts[1].trim());
                    int end   = Integer.parseInt(parts[2].trim());
                    if (start >= raw.length()) return null;
                    if (end == -1 || end > raw.length()) end = raw.length();
                    return raw.substring(start, end);
                }

                // ── 正则多分组拼接 ────────────────────────────────
                case "EXTRACT_PATTERN_JOIN": {
                    // value_map 格式：EXTRACT_PATTERN_JOIN:{regex}:{g1}:{g2}:{sep}
                    if (parts.length < 4) {
                        log.warn("[ValueMappingParser] EXTRACT_PATTERN_JOIN 参数不足: {}", descriptor);
                        return null;
                    }
                    String regex = parts[1]
                            .replace("\\\\", "\\")
                            .replace("\u0001", ":")
                            .replace("\u0002", "(?:")
                            .replace("\u0003", "(?=")
                            .replace("\u0004", "(?!")
                            .replace("\u0005", "(?<=")
                            .replace("\u0006", "(?<!");
                    int    g1  = parseIndex(parts[2], 1);
                    int    g2  = parseIndex(parts[3], 2);
                    String sep = (parts.length >= 5) ? unescapeSep(parts[4]) : "";
                    Matcher m = Pattern.compile(regex).matcher(raw);
                    if (!m.find()) {
                        log.warn("[ValueMappingParser] EXTRACT_PATTERN_JOIN 未匹配: regex={} raw={}", regex, raw);
                        return null;
                    }
                    String part1 = m.group(g1);
                    String part2 = m.group(g2).trim();
                    return part1 + sep + part2;
                }

                // ── 数值范围映射 ──────────────────────────────────
                // value_map 格式：RANGE_MAP:{条件1}={目标值1};{条件2}={目标值2};...
                // 条件格式：{lo}{op1}VALUE{op2}{hi}，支持 <= 和 < 两种比较符，也支持单侧。
                // * 作为兜底默认值键。
                // 示例：RANGE_MAP:6.6<=VALUE<=7.7=B;7.7<VALUE<=8.8=C;*=D
                case "RANGE_MAP": {
                    if (parts.length < 2) return null;
                    // 还原被转义的冒号，再取冒号后面的全部内容作为规则段
                    String rangeSpec = descriptor.substring("RANGE_MAP:".length())
                            .replace("\u0001", ":");
                    // 先从原值提取第1个数字
                    String numStr = extractNthNumber(raw, 1);
                    if (numStr == null) {
                        log.warn("[ValueMappingParser] RANGE_MAP 无法从原值提取数字: {}", raw);
                        return null;
                    }
                    double numVal;
                    try {
                        numVal = Double.parseDouble(numStr);
                    } catch (NumberFormatException e) {
                        log.warn("[ValueMappingParser] RANGE_MAP 数字解析失败: {}", numStr);
                        return null;
                    }
                    return applyRangeMap(numVal, rangeSpec, raw);
                }

                // ── 字典映射（value_connection 新路径）────────────
                case "DICT_MAP": {
                    // value_map = "DICT_MAP"
                    // 必须通过 convertWithDictMap() 传入 mergedDictMap 才能正常工作。
                    // 直接调用 convert() 走到此分支时，无映射表可用，记录错误并返回 null。
                    log.error("[ValueMappingParser] DICT_MAP 规则须通过 convertWithDictMap() 调用，" +
                            "请勿直接使用 convert()。raw={}", raw);
                    return null;
                }

                // ── 提取轮毂规格：RIM_SPEC:BOTH ──────────────────
                case "RIM_SPEC": {
                    if (parts.length < 2) return null;
                    String part = parts[1].toUpperCase();

                    if ("BOTH".equals(part)) {
                        // 1. 提取直径：R 后面的整数
                        Matcher diamMatcher = Pattern.compile("R(\\d+)").matcher(raw);
                        if (!diamMatcher.find()) {
                            log.warn("[ValueMappingParser] RIM_SPEC:BOTH 未找到直径(R\\d+): {}", raw);
                            return null;
                        }
                        String diameter = diamMatcher.group(1);

                        // 2. 提取宽度：x 后面、J 前面，支持 "7J" 和 "7 1/2J" 两种格式
                        Matcher widthMatcher = Pattern.compile("x(\\d+(?:\\s+\\d+/\\d+)?)J").matcher(raw);
                        if (!widthMatcher.find()) {
                            log.warn("[ValueMappingParser] RIM_SPEC:BOTH 未找到宽度(x...J): {}", raw);
                            return null;
                        }
                        String widthRaw = widthMatcher.group(1).trim(); // "7" 或 "7 1/2"

                        // 3. 分数转小数："7 1/2" → 7.5，"7" → 7
                        String width;
                        Matcher fracMatcher = Pattern.compile("(\\d+)\\s+(\\d+)/(\\d+)").matcher(widthRaw);
                        if (fracMatcher.matches()) {
                            double val = Double.parseDouble(fracMatcher.group(1))
                                    + Double.parseDouble(fracMatcher.group(2))
                                    / Double.parseDouble(fracMatcher.group(3));
                            width = (val == Math.floor(val))
                                    ? String.valueOf((long) val)
                                    : String.valueOf(val);
                        } else {
                            width = widthRaw;
                        }

                        return diameter + "," + width;
                    }

                    log.warn("[ValueMappingParser] RIM_SPEC 不支持的 part: {}", parts[1]);
                    return null;
                }

                // ── 管道链式执行：PIPE:{rule1}|{rule2}|... ───────
                // 将多个 value_map 规则串联，前一步输出作为下一步输入。
                // 注意：规则内部的 | 需用 \x7C 转义。
                // PIPE 内若含 DICT_MAP 步骤，须通过 convertWithDictMap() 调用以传入 mergedDictMap；
                // 直接调用 convert() 时 mergedDictMap 为 null，DICT_MAP 步骤将返回 null 并打印 error。
                case "PIPE": {
                    if (parts.length < 2) return raw;
                    String pipeLine = descriptor.substring("PIPE:".length());
                    String[] steps = pipeLine.split("\\|", -1);
                    String current = raw;
                    for (String step : steps) {
                        if (current == null) return null;
                        step = step.trim()
                                .replace("\\x7C", "|")
                                .replace("\u0001", ":");
                        // 委托给 convertStep，mergedDictMap=null（DICT_MAP 步骤将报错）
                        current = convertStep(current, step, null);
                        if (EMPTY_SENTINEL.equals(current)) return EMPTY_SENTINEL;
                    }
                    return current;
                }

                case "AXIS_DRIVE": {
                    String sep      = (parts.length >= 2) ? unescapeSep(parts[1]) : ";";
                    String trueVal  = (parts.length >= 3) ? parts[2] : "Y";
                    String falseVal = (parts.length >= 4) ? parts[3] : "N";
                    String keyword1 = (parts.length >= 5) ? parts[4] : "front";
                    String keyword2 = (parts.length >= 6) ? parts[5] : "rear";
                    String rawLower = raw.toLowerCase();
                    String val1 = rawLower.contains(keyword1.toLowerCase()) ? trueVal : falseVal;
                    String val2 = rawLower.contains(keyword2.toLowerCase()) ? trueVal : falseVal;
                    return val1 + sep + val2;
                }

                default:
                    log.warn("[ValueMappingParser] 未知映射类型: {}", type);
                    return raw;
            }

        } catch (Exception e) {
            log.error("[ValueMappingParser] 映射转换异常 valueMap={} raw={}: {}",
                    valueMap, rawValue, e.getMessage());
            return null;
        }
    }

    // =====================================================
    //  DICT_MAP：基于 value_connection 的映射
    // =====================================================

    /**
     * DICT_MAP 规则入口：使用由 {@code value_connection} 解析得到的合并映射表执行值转换。
     *
     * <p>支持以下两种 valueMap 场景：
     * <ul>
     *   <li>{@code "DICT_MAP"} — 直接在 mergedDictMap 中查找 rawValue</li>
     *   <li>{@code "PIPE:...步骤...|DICT_MAP"} — 管道中含 DICT_MAP 步骤时，
     *       透传 mergedDictMap 给每一步，确保 DICT_MAP 步骤正常执行</li>
     * </ul>
     *
     * <p>对于其他类型的 {@code valueMap}，直接委托给 {@link #convert(String, String)}。
     *
     * <p>示例（直接 DICT_MAP）：
     * <pre>
     *   value_connection = {"COC模版":{"法国":"F","德国":"G"},"MES":{"法国":"F","日本":"J"}}
     *   → mergedDictMap  = {"法国":"F","德国":"G","日本":"J"}
     *   → convertWithDictMap("法国", "DICT_MAP", mergedDictMap) → "F"
     * </pre>
     *
     * <p>示例（PIPE 中含 DICT_MAP）：
     * <pre>
     *   输入：     "215/55R18 99H 18x7J ET33 5.96N/kN C1"
     *   valueMap： "PIPE:EXTRACT_PATTERN:(\d+\.\d+N\/kN):1|DICT_MAP"
     *   step-1 EXTRACT_PATTERN → "5.96N/kN"
     *   step-2 DICT_MAP        → mergedDictMap.get("5.96N/kN") → 目标值
     * </pre>
     *
     * @param rawValue      上游原始值
     * @param valueMap      映射规则描述符（sys_dict_data.value_map）
     * @param mergedDictMap 已合并的映射表（由 {@link #mergeValueConnection} 生成）
     * @return 转换后的目标值；DICT_MAP 未命中时返回 null
     */
    public static String convertWithDictMap(String rawValue, String valueMap, Map<String, String> mergedDictMap) {
        if (valueMap == null || StringUtils.isBlank(valueMap)) {
            return rawValue;
        }
        return convertStep(rawValue, valueMap.trim(), mergedDictMap);
    }

    /**
     * 单步转换的统一分发器，同时感知 {@code mergedDictMap}。
     *
     * <ul>
     *   <li>{@code DICT_MAP} → 直接查 mergedDictMap</li>
     *   <li>{@code PIPE:...} → 拆分管道步骤，每步递归调用本方法，透传 mergedDictMap</li>
     *   <li>其他规则 → 委托给 {@link #convert(String, String)}</li>
     * </ul>
     *
     * @param rawValue      当前步骤的输入值
     * @param step          单条规则描述符
     * @param mergedDictMap DICT_MAP 映射表；非 DICT_MAP 步骤时忽略
     * @return 当前步骤的输出值
     */
    private static String convertStep(String rawValue, String step, Map<String, String> mergedDictMap) {
        if (step == null || StringUtils.isBlank(step)) return rawValue;

        String upperStep = step.trim().toUpperCase();

        // ── DICT_MAP 步骤：查合并映射表 ──────────────────────────
        if (upperStep.equals("DICT_MAP")) {
            String raw = (rawValue == null) ? "" : rawValue.trim();
            if (raw.isEmpty()) return null;
            if (mergedDictMap == null || mergedDictMap.isEmpty()) {
                log.error("[ValueMappingParser] DICT_MAP：mergedDictMap 为空，无法映射。raw={}", raw);
                return null;
            }

            // ① 精确匹配
            String result = mergedDictMap.get(raw);
            if (result != null) return result;

            // ② 范围 key 匹配：从原值提取第1个数字，遍历 map key 尝试解析为范围表达式。
            //    支持格式（变量名任意，大小写不限，支持 <= 和 <）：
            //      双侧：6.6<=RRC<=7.7  /  6.6<RRC<=7.7  /  6.6<=RRC<7.7  /  6.6<RRC<7.7
            //      单侧左开：RRC<=7.7  /  RRC<7.7
            //      单侧右开：6.6<=RRC  /  6.6<RRC
            String numStr = extractNthNumber(raw, 1);
            if (numStr != null) {
                try {
                    double numVal = Double.parseDouble(numStr);
                    for (Map.Entry<String, String> entry : mergedDictMap.entrySet()) {
                        if (matchesRangeKey(entry.getKey(), numVal)) {
                            return entry.getValue();
                        }
                    }
                } catch (NumberFormatException ignored) { }
            }

            // ③ 兜底 * 键
            result = mergedDictMap.get("*");
            if (result != null) return result;

            log.warn("[ValueMappingParser] DICT_MAP 未命中: raw='{}', 可用键={}", raw, mergedDictMap.keySet());
            return null;
        }

        // ── PIPE 步骤：拆分子步骤，逐步执行，透传 mergedDictMap ──
        if (upperStep.startsWith("PIPE:")) {
            String pipeLine = step.trim().substring("PIPE:".length());
            String[] steps = pipeLine.split("\\|", -1);
            String current = rawValue;
            for (String s : steps) {
                if (current == null) return null;
                s = s.trim()
                        .replace("\\x7C", "|")
                        .replace("\\x3A", ":");
                current = convertStep(current, s, mergedDictMap);
                if (EMPTY_SENTINEL.equals(current)) return EMPTY_SENTINEL;
            }
            return current;
        }

        // ── 其他规则：走常规 convert ──────────────────────────────
        return convert(rawValue, step);
    }

    /**
     * 将 {@code value_connection} 字段存储的多来源映射 JSON 合并为一张扁平映射表。
     *
     * <p>{@code value_connection} 格式（两层 JSON 对象）：
     * <pre>
     *   {
     *     "COC模版": {"法国": "F", "德国": "G"},
     *     "MES":     {"法国": "F", "日本": "J"}
     *   }
     * </pre>
     *
     * <p>合并规则：
     * <ul>
     *   <li>同一 key 在不同来源中值相同：取一次，无冲突</li>
     *   <li>同一 key 在不同来源中值不同：后来的值覆盖，打印 warn 日志</li>
     * </ul>
     *
     * @param valueConnectionJson {@code SysDictData.valueConnection} 字段的 JSON 字符串
     * @return 合并后的扁平映射表；入参为空或解析失败时返回空 map（不抛异常）
     */
    @SuppressWarnings("unchecked")
    public static Map<String, String> mergeValueConnection(String valueConnectionJson) {
        Map<String, String> merged = new LinkedHashMap<>();
        if (StringUtils.isBlank(valueConnectionJson)) {
            return merged;
        }
        try {
            ObjectMapper om = new ObjectMapper();
            Map<String, Object> outer = om.readValue(
                    valueConnectionJson,
                    new TypeReference<Map<String, Object>>() {});

            for (Map.Entry<String, Object> sourceEntry : outer.entrySet()) {
                String sourceName = sourceEntry.getKey();
                Object subMapObj  = sourceEntry.getValue();
                if (!(subMapObj instanceof Map)) {
                    log.warn("[ValueMappingParser] mergeValueConnection: 来源 '{}' 的值不是 Map，已跳过", sourceName);
                    continue;
                }
                Map<String, Object> subMap = (Map<String, Object>) subMapObj;
                for (Map.Entry<String, Object> kv : subMap.entrySet()) {
                    String k = kv.getKey();
                    String v = kv.getValue() == null ? null : String.valueOf(kv.getValue());
                    if (merged.containsKey(k) && !Objects.equals(merged.get(k), v)) {
                        log.warn("[ValueMappingParser] mergeValueConnection: key='{}' 在来源 '{}' 中值冲突 " +
                                        "（已有值='{}' 新值='{}'），以新值覆盖",
                                k, sourceName, merged.get(k), v);
                    }
                    merged.put(k, v);
                }
            }
        } catch (Exception e) {
            log.error("[ValueMappingParser] mergeValueConnection 解析失败: {}", e.getMessage(), e);
        }
        return merged;
    }

    // =====================================================
    //  内部工具方法
    // =====================================================

    /**
     * 从文本中提取第 N 个数字（整数或小数，N 从 1 开始）。
     *
     * <p>示例：
     * <pre>
     *   "2672mm"           → extractNthNumber(raw, 1) → "2672"
     *   "105kW at 5200"    → extractNthNumber(raw, 2) → "5200"
     *   "1236kg，1200kg"   → extractNthNumber(raw, 2) → "1200"
     *   "PN: 8.04E+11 #"  → extractNthNumber(raw, 1) → "8.04"
     * </pre>
     */
    private static String extractNthNumber(String text, int n) {
        if (text == null || StringUtils.isBlank(text)) return null;
        Matcher m = NUMBER_PATTERN.matcher(text);
        int count = 0;
        while (m.find()) {
            count++;
            if (count == n) {
                String val = m.group();
                return val.endsWith(".") ? val.substring(0, val.length() - 1) : val;
            }
        }
        return null;
    }

    /**
     * 判断数值是否命中范围 key 表达式。
     *
     * <p>支持的范围 key 格式（变量名任意字母，大小写不限）：
     * <ul>
     *   <li>双侧：{@code 6.6<=RRC<=7.7} / {@code 6.6<RRC<=7.7} / {@code 6.6<=RRC<7.7} / {@code 6.6<RRC<7.7}</li>
     *   <li>单侧上界：{@code RRC<=7.7} / {@code RRC<7.7}</li>
     *   <li>单侧下界：{@code 6.6<=RRC} / {@code 6.6<RRC}</li>
     * </ul>
     *
     * <p>非范围 key（如普通字符串、纯数字）直接返回 false，由调用方继续精确匹配或兜底。
     *
     * @param key    value_connection 中的映射 key
     * @param numVal 已从原值中提取的数字
     * @return 数值是否落在该范围内
     */
    private static boolean matchesRangeKey(String key, double numVal) {
        if (key == null || key.isEmpty()) return false;
        // 双侧范围：{lo}{op1}{VAR}{op2}{hi}
        // 例：6.6<=RRC<=7.7  /  6.6<RRC<=7.7  /  6.6<=RRC<7.7
        Matcher dual = Pattern.compile(
                "(-?\\d+(?:\\.\\d+)?)\\s*(<=|<)\\s*[A-Za-z_]+\\s*(<=|<)\\s*(-?\\d+(?:\\.\\d+)?)"
        ).matcher(key.trim());
        if (dual.matches()) {
            double lo  = Double.parseDouble(dual.group(1));
            String op1 = dual.group(2);
            String op2 = dual.group(3);
            double hi  = Double.parseDouble(dual.group(4));
            boolean loOk = "<=".equals(op1) ? numVal >= lo : numVal > lo;
            boolean hiOk = "<=".equals(op2) ? numVal <= hi : numVal < hi;
            return loOk && hiOk;
        }
        // 单侧上界：{VAR}{op}{hi}  例：RRC<=7.7 / RRC<7.7
        Matcher upperOnly = Pattern.compile(
                "[A-Za-z_]+\\s*(<=|<)\\s*(-?\\d+(?:\\.\\d+)?)"
        ).matcher(key.trim());
        if (upperOnly.matches()) {
            String op = upperOnly.group(1);
            double hi = Double.parseDouble(upperOnly.group(2));
            return "<=".equals(op) ? numVal <= hi : numVal < hi;
        }
        // 单侧下界：{lo}{op}{VAR}  例：6.6<=RRC / 10.6<RRC
        Matcher lowerOnly = Pattern.compile(
                "(-?\\d+(?:\\.\\d+)?)\\s*(<=|<)\\s*[A-Za-z_]+"
        ).matcher(key.trim());
        if (lowerOnly.matches()) {
            double lo = Double.parseDouble(lowerOnly.group(1));
            String op = lowerOnly.group(2);
            return "<=".equals(op) ? numVal >= lo : numVal > lo;
        }
        return false;
    }

    /**
     * 按范围规则串匹配数值，返回对应目标值。
     *
     * <p>{@code rangeSpec} 为分号分隔的条目列表，每条格式：{@code 条件表达式=目标值}。
     * 条件表达式支持：
     * <ul>
     *   <li>双侧：{@code 6.6<=VALUE<=7.7}（变量名任意，大小写不限）</li>
     *   <li>单侧上界：{@code VALUE<=7.7}</li>
     *   <li>单侧下界：{@code 6.6<=VALUE}</li>
     *   <li>兜底：{@code *}（未命中任何范围时返回）</li>
     * </ul>
     *
     * <p>示例：{@code 6.6<=VALUE<=7.7=B;7.7<VALUE<=8.8=C;*=D}，numVal=6.8 → "B"
     *
     * @param numVal    从原值中已提取的数字
     * @param rangeSpec 范围规则串（RANGE_MAP: 后面的部分，已还原转义冒号）
     * @param rawForLog 原始值，仅用于日志
     * @return 命中的目标值；全部未命中时返回 null
     */
    private static String applyRangeMap(double numVal, String rangeSpec, String rawForLog) {
        if (rangeSpec == null || rangeSpec.isEmpty()) return null;
        String fallback = null;
        for (String entry : rangeSpec.split(";", -1)) {
            entry = entry.trim();
            if (entry.isEmpty()) continue;
            // 最后一个 '=' 左边是条件，右边是目标值
            int eqIdx = entry.lastIndexOf('=');
            if (eqIdx < 0) {
                log.warn("[ValueMappingParser] RANGE_MAP 条目格式错误（缺少 '='）: {}", entry);
                continue;
            }
            String condition = entry.substring(0, eqIdx).trim();
            String target    = entry.substring(eqIdx + 1).trim();
            if ("*".equals(condition)) {
                fallback = target;
                continue;
            }
            if (matchesRangeKey(condition, numVal)) {
                return target;
            }
        }
        if (fallback != null) return fallback;
        log.warn("[ValueMappingParser] RANGE_MAP 未命中任何范围: raw='{}', spec='{}'", rawForLog, rangeSpec);
        return null;
    }

    /**
     * 从文本中提取指数值，按优先级依次尝试五种格式：
     *
     * <ol>
     *   <li><b>科学计数法</b>：{@code 8.04E+11}、{@code 1.2e-3}、{@code 5.6E11}</li>
     *   <li><b>Unicode 上标</b>：{@code 10¹¹}、{@code 3.5×10⁸}</li>
     *   <li><b>括号注释</b>：{@code （11上标）}、{@code (11上标)}</li>
     *   <li><b>脱字符</b>：{@code ^11}、{@code ^{11}}</li>
     *   <li><b>10-N 格式</b>：{@code 10-2}</li>
     * </ol>
     */
    private static String extractExponent(String text) {
        if (text == null || StringUtils.isBlank(text)) return null;

        Matcher m = SCI_NOTATION_PATTERN.matcher(text);
        if (m.find()) return m.group(1);

        m = UNICODE_SUP_PATTERN.matcher(text);
        if (m.find()) return normalizeSuperscript(m.group(1));

        m = ANNOTATED_SUP_PATTERN.matcher(text);
        if (m.find()) return m.group(1);

        m = CARET_SUP_PATTERN.matcher(text);
        if (m.find()) return m.group(1);

        m = PLAIN_NEG_EXP_PATTERN.matcher(text);
        if (m.find()) return m.group(1);

        log.warn("[ValueMappingParser] EXTRACT_EXPONENT 未找到指数: {}", text);
        return null;
    }

    /**
     * 将 Unicode 上标字符串转换为普通 ASCII 数字字符串。
     * 例如 "¹¹" → "11"，"⁸" → "8"。
     */
    private static String normalizeSuperscript(String sup) {
        StringBuilder sb = new StringBuilder(sup.length());
        for (char c : sup.toCharArray()) {
            Character mapped = SUPERSCRIPT_MAP.get(c);
            sb.append(mapped != null ? mapped : c);
        }
        return sb.toString();
    }

    private static String convertDate(String raw, String inputFmt, String outputFmt) {
        if (raw == null || StringUtils.isBlank(raw)) return null;
        DateTimeFormatter outFormatter = StringUtils.isNotBlank(outputFmt)
                ? DateTimeFormatter.ofPattern(outputFmt)
                : XSD_DATE;

        if (StringUtils.isNotBlank(inputFmt)) {
            try {
                LocalDate d = LocalDate.parse(raw, DateTimeFormatter.ofPattern(inputFmt));
                return d.format(outFormatter);
            } catch (DateTimeParseException e) {
                log.warn("[ValueMappingParser] 日期解析失败 fmt={} raw={}", inputFmt, raw);
            }
        }
        for (DateTimeFormatter fmt : COMMON_DATE_FORMATS) {
            try {
                return LocalDate.parse(raw, fmt).format(outFormatter);
            } catch (DateTimeParseException ignored) { }
        }
        try {
            return LocalDateTime.parse(raw).toLocalDate().format(outFormatter);
        } catch (DateTimeParseException ignored) { }
        log.warn("[ValueMappingParser] 无法解析日期: {}", raw);
        return null;
    }

    private static String convertDateTime(String raw, String inputFmt, String outputFmt) {
        if (raw == null || StringUtils.isBlank(raw)) return null;
        DateTimeFormatter outFormatter = StringUtils.isNotBlank(outputFmt)
                ? DateTimeFormatter.ofPattern(outputFmt)
                : XSD_DATETIME;

        if (StringUtils.isNotBlank(inputFmt)) {
            try {
                LocalDateTime dt = LocalDateTime.parse(raw, DateTimeFormatter.ofPattern(inputFmt));
                return dt.format(outFormatter);
            } catch (DateTimeParseException e) {
                log.warn("[ValueMappingParser] 日期时间解析失败 fmt={} raw={}", inputFmt, raw);
            }
        }
        try {
            return LocalDateTime.parse(raw).format(outFormatter);
        } catch (DateTimeParseException ignored) { }
        String dateOnly = convertDate(raw, inputFmt, outputFmt);
        return (dateOnly != null) ? dateOnly + "T00:00:00" : null;
    }

    /**
     * 解析枚举映射表字符串 {@code k1=v1,k2=v2,...}。
     * 星号 {@code *} 作为兜底默认值键。
     */
    private static Map<String, String> parseEnumMap(String spec) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String entry : spec.split(",")) {
            String[] kv = entry.split("=", 2);
            if (kv.length == 2) {
                map.put(kv[0].trim(), kv[1].trim());
            }
        }
        return map;
    }

    /**
     * 将数据库中存储的分隔符别名还原为真实字符。
     * {@code SEMICOLON} → {@code ;} ，{@code NEWLINE} → {@code \n}
     */
    private static String unescapeSep(String sep) {
        switch (sep.toUpperCase()) {
            case "SEMICOLON":   return ";";
            case "NEWLINE":     return "\n";
            case "COMMA":       return ",";
            case "PIPE":        return "|";
            case "SLASH":       return "/";
            case "TAB":         return "\t";
            case "COMMA_SPACE": return ", ";
            default:            return sep;
        }
    }

    private static int parseIndex(String s, int defaultVal) {
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return defaultVal; }
    }

    // =====================================================
    //  便捷工厂方法（用于生成 value_map 描述符入库）
    // =====================================================

    /** 生成 STRIP_UNIT 描述符（取第 N 个数字） */
    public static String stripUnit(int nthNumber) {
        return nthNumber == 1 ? "STRIP_UNIT" : "STRIP_UNIT:" + nthNumber;
    }

    /** 生成 DATE_FORMAT 描述符 */
    public static String dateFormat(String inputPattern) {
        return "DATE_FORMAT:" + inputPattern;
    }

    /** 生成 DATETIME_FORMAT 描述符 */
    public static String datetimeFormat(String inputPattern) {
        return "DATETIME_FORMAT:" + inputPattern;
    }

    /** 生成 ENUM 描述符（有序，保证不超过100字符） */
    public static String enumMap(Map<String, String> entries) {
        StringJoiner sj = new StringJoiner(",", "ENUM:", "");
        for (Map.Entry<String, String> e : entries.entrySet()) {
            sj.add(e.getKey() + "=" + e.getValue());
        }
        return sj.toString();
    }

    /** 生成 SPLIT_JOIN 描述符 */
    public static String splitJoin(String inSep, String outSep) {
        return "SPLIT_JOIN:" + escapeSepAlias(inSep) + ":" + escapeSepAlias(outSep);
    }

    /** 生成 SPLIT_TAKE 描述符（index 从 0 开始） */
    public static String splitTake(String sep, int index) {
        return "SPLIT_TAKE:" + escapeSepAlias(sep) + ":" + index;
    }

    /** 生成 SPLIT_MULTIROW 描述符 */
    public static String splitMultirow(String sep) {
        return "SPLIT_MULTIROW:" + escapeSepAlias(sep);
    }

    /** 生成 DICT_MAP 描述符（value_map 列存入 "DICT_MAP"，映射数据存入 value_connection 列） */
    public static String dictMap() {
        return "DICT_MAP";
    }

    private static String escapeSepAlias(String sep) {
        switch (sep) {
            case ";":  return "SEMICOLON";
            case "\n": return "NEWLINE";
            case ",":  return "COMMA";
            case "|":  return "PIPE";
            case "/":  return "SLASH";
            case "\t": return "TAB";
            default:   return sep;
        }
    }

    /**
     * 从 value_map 描述符中提取 GROUP_JOIN_SEP 声明的输入/输出分隔符。
     *
     * <p>格式：{@code GROUP_JOIN_SEP:{inSep}:{outSep}}
     * <ul>
     *   <li>{inSep}  — 原始多值之间的分隔符（如 COMMA、SEMICOLON 等别名或字面量）</li>
     *   <li>{outSep} — 输出拼接时使用的分隔符</li>
     * </ul>
     *
     * @param valueMap value_map 字段值
     * @return 长度为2的数组 [inSep, outSep]；非 GROUP_JOIN_SEP 规则返回 null
     */
    public static String[] extractGroupJoinSep(String valueMap) {
        if (StringUtils.isBlank(valueMap)) return null;
        String trimmed = valueMap.trim();
        if (!trimmed.toUpperCase().startsWith("GROUP_JOIN_SEP:")) return null;
        String[] parts = trimmed.split(":", 3);
        if (parts.length < 3) return null;
        return new String[]{
                unescapeSep(parts[1]),   // inSep
                unescapeSep(parts[2])    // outSep
        };
    }
}