package com.ruoyi.common.core.parser;

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

    // ── 指数提取：四种格式 ────────────────────────────────────────
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
            String[] parts = descriptor.split(":", 3);
            String type = parts[0].toUpperCase();

            switch (type) {

                // ── 直传 ──────────────────────────────────────────
                case "DIRECT":
                    return raw.isEmpty() ? null : raw;

                // ── 固定空值 ─────────────────────────────────────
                case "NULL":
                    return null;

                // ── 去单位取纯数字 ────────────────────────────────
                case "STRIP_UNIT": {
                    int index = (parts.length >= 2) ? parseIndex(parts[1], 1) : 1;
                    return extractNthNumber(raw, index);
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
                    String regex = parts[1];
                    int group   = parseIndex(parts[2], 1);
                    Matcher m = Pattern.compile(regex).matcher(raw);
                    return m.find() ? m.group(group) : null;
                }

                // ── 日期格式转换 → xs:date ────────────────────────
                case "DATE_FORMAT": {
                    String inputFmt = (parts.length >= 2) ? parts[1] : null;
                    return convertDate(raw, inputFmt);
                }

                // ── 日期时间格式转换 → xs:dateTime ────────────────
                case "DATETIME_FORMAT": {
                    String inputFmt = (parts.length >= 2) ? parts[1] : null;
                    return convertDateTime(raw, inputFmt);
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
                    // value_map = ENUM:{k1=v1,k2=v2,...}
                    if (parts.length < 2) return null;
                    Map<String, String> enumMap = parseEnumMap(parts[1]);
                    return enumMap.getOrDefault(raw, enumMap.getOrDefault("*", null));
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
                // 去掉多余的尾部小数点
                return val.endsWith(".") ? val.substring(0, val.length() - 1) : val;
            }
        }
        return null;
    }

    /**
     * 从文本中提取指数值，按优先级依次尝试四种格式：
     *
     * <ol>
     *   <li><b>科学计数法</b>：{@code 8.04E+11}、{@code 1.2e-3}、{@code 5.6E11}
     *       → 提取 E/e 后面的数字串</li>
     *   <li><b>Unicode 上标</b>：{@code 10¹¹}、{@code 3.5×10⁸}
     *       → 将上标字符（U+00B9/U+00B2/U+00B3/U+2070/U+2074-U+2079）转为 ASCII 数字</li>
     *   <li><b>括号注释</b>：{@code （11上标）}、{@code (11上标)}
     *       → 提取括号内的数字串</li>
     *   <li><b>脱字符</b>：{@code ^11}、{@code ^{11}}
     *       → 提取 ^ 后面的数字串</li>
     * </ol>
     *
     * <p>示例：
     * <pre>
     *   "PN: 8.04E+11 #/km"   → "11"   (科学计数)
     *   "#.10¹¹/km"            → "11"   (Unicode上标)
     *   "3.5×10⁸ /km"          → "8"    (Unicode上标+乘号)
     *   "#.1011/km（11上标）"  → "11"   (括号注释)
     *   "1.2e+8"               → "8"    (小写e科学计数)
     *   "10^{6}"               → "6"    (脱字符)
     * </pre>
     */
    private static String extractExponent(String text) {
        if (text == null || StringUtils.isBlank(text)) return null;

        // 策略1：科学计数法 E/e 后的指数
        Matcher m = SCI_NOTATION_PATTERN.matcher(text);
        if (m.find()) {
            return m.group(1);
        }

        // 策略2：Unicode 上标数字（×10ⁿ 或 10ⁿ）
        m = UNICODE_SUP_PATTERN.matcher(text);
        if (m.find()) {
            return normalizeSuperscript(m.group(1));
        }

        // 策略3：括号注释"（N上标）"
        m = ANNOTATED_SUP_PATTERN.matcher(text);
        if (m.find()) {
            return m.group(1);
        }

        // 策略4：脱字符 ^N 或 ^{N}
        m = CARET_SUP_PATTERN.matcher(text);
        if (m.find()) {
            return m.group(1);
        }

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

    /**
     * 日期转换：输入格式 → xs:date (yyyy-MM-dd)。
     * inputFmt 为 null 时自动尝试常见格式。
     */
    private static String convertDate(String raw, String inputFmt) {
        if (raw == null || StringUtils.isBlank(raw)) return null;
        if (inputFmt != null && !StringUtils.isBlank(inputFmt)) {
            try {
                LocalDate d = LocalDate.parse(raw, DateTimeFormatter.ofPattern(inputFmt));
                return d.format(XSD_DATE);
            } catch (DateTimeParseException e) {
                log.warn("[ValueMappingParser] 日期解析失败 fmt={} raw={}", inputFmt, raw);
            }
        }
        // 自动探测
        for (DateTimeFormatter fmt : COMMON_DATE_FORMATS) {
            try {
                return LocalDate.parse(raw, fmt).format(XSD_DATE);
            } catch (DateTimeParseException ignored) { }
        }
        // 已是 ISO 格式（含时间部分）
        try {
            return LocalDateTime.parse(raw).toLocalDate().format(XSD_DATE);
        } catch (DateTimeParseException ignored) { }
        log.warn("[ValueMappingParser] 无法解析日期: {}", raw);
        return null;
    }

    /**
     * 日期时间转换：输入格式 → xs:dateTime (yyyy-MM-dd'T'HH:mm:ss)。
     */
    private static String convertDateTime(String raw, String inputFmt) {
        if (raw == null || StringUtils.isBlank(raw)) return null;
        if (inputFmt != null && !StringUtils.isBlank(inputFmt)) {
            try {
                LocalDateTime dt = LocalDateTime.parse(raw, DateTimeFormatter.ofPattern(inputFmt));
                return dt.format(XSD_DATETIME);
            } catch (DateTimeParseException e) {
                log.warn("[ValueMappingParser] 日期时间解析失败 fmt={} raw={}", inputFmt, raw);
            }
        }
        try {
            return LocalDateTime.parse(raw).format(XSD_DATETIME);
        } catch (DateTimeParseException ignored) { }
        // 尝试当作纯日期补 00:00:00
        String dateOnly = convertDate(raw, inputFmt);
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
            case "SEMICOLON": return ";";
            case "NEWLINE":   return "\n";
            case "COMMA":     return ",";
            case "PIPE":      return "|";
            case "SLASH":     return "/";
            case "TAB":       return "\t";
            default:          return sep;
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
}