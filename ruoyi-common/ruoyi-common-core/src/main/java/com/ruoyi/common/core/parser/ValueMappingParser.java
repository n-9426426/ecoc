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
 *  GROUP_JOIN_SEP:{inSep}:{outSep}[:{dedup}[:{segTerm}]]
 *                                    不做值转换，仅修改当前 uuid 组的多值拼接符。
 *                                    {inSep} 当前保留未使用（历史占位，向后兼容旧数据）。
 *                                    可选段「非空即生效」，不使用 TRUE/FALSE 字面量：
 *                                      · dedup   — 非空时开启去重：拼接新值前检查该值是否已存在
 *                                                  于已拼接结果中，已存在则跳过（任意非空字符串均可，
 *                                                  建议写 DEDUP 增加可读性）。
 *                                                  示例：两行经规则都映射为 "BRK" 时，
 *                                                  若 dedup 非空，最终只拼接一次 "BRK"。
 *                                      · segTerm — 非空时，每个命中项后都追加一次该值（取代“项间插入”
 *                                                  语义，变为“每项自带后缀”）。
 *                                                  示例：segTerm=PIPE，命中 BRK、UNB → "BRK|UNB|"。
 *                                                  若 segTerm 为空（默认），则为普通项间插入：
 *                                                  命中 BRK、UNB → "BRK" + outSep + "UNB"。
 *                                    （仅适用于「单层」拼接：所有命中项共用同一种连接方式。
 *                                    若需要"段内一种分隔符、段间另一种分隔符 + 整体前缀"的复合结构，
 *                                    见下方 {@code MULTI_GROUP_JOIN_SEP}。）
 *  MULTI_GROUP_JOIN_SEP:{prefix}:{slotSep}:{segTerm}:{seg1}~{seg2}~...
 *                                    多 key_map 链场景下的「分段 + 槛位」拼接，须放在同一 uuid 链中
 *                                    单独一行声明（该行 value_map 仅作配置声明，不参与值转换，
 *                                    其 key_map/dict_label 可留空或随意，处理时会被跳过）。
 *                                    用于「段内多个来源用 slotSep 连接，段与段之间各自带 segTerm 后缀，
 *                                    整体可加固定前缀」的复合格式，如 "800;1500|750|"、"DB;DC||"、"||4142"。
 *                                      · prefix   — 整体结果固定前缀（可为空）
 *                                      · slotSep  — 同一 segment 内多个来源命中值之间的连接符（可为空，
 *                                                   为空时表示该 segment 至多 1 个槛位）
 *                                      · segTerm  — 每个非空 segment 输出后追加的固定后缀（可为空，
 *                                                   为空表示 segment 之间直接顺序拼接、无后缀）
 *                                      · segN     — 用 {@code ~} 分隔的多个 segment，每个 segment 内
 *                                                   用 {@code ,} 分隔一个或多个 {@code key_map} 字符串，
 *                                                   引用同一链中其它行的 {@code key_map}（须完全匹配），
 *                                                   取该行经其自身 value_map 转换后的结果作为槛位值；
 *                                                   槛位值为空/N/A/NULL 视为未命中，整 segment 内所有槛位
 *                                                   均未命中则该 segment（含其 segTerm）整体省略。
 *                                                   key_map 内若含 {@code :}、{@code ,}、{@code ~} 等
 *                                                   结构符，需要用 \x3A / \x2C / \x7E 转义。
 *                                    <b>重要：segment 分隔符固定用 {@code ~}，禁止用 {@code ;}</b>——
 *                                    前端编辑页面保存时（{@code SysDictDataServiceImpl#splitToRows}）会把
 *                                    整条 value_map 字符串按 {@code ;} 切分、拆成同组多行写入数据库；
 *                                    若描述符内部出现 {@code ;}，会被这层逻辑错误截断成多行残缺数据
 *                                    （例如本应是一条完整的 MULTI_GROUP_JOIN_SEP 规则，被从中间切开后，
 *                                    后半段 segment 信息丢失，导致拼接结果缺段）。
 *
 *                                    示例一（BodyworkTypeTrailer，目标 "DB;DC||"）：
 *                                      链内两行：
 *                                        key_map="18.1. Drawbar trailer:…kg"
 *                                          value_map="ENUM:N/A=__NULL__,NULL=__NULL__,*=DB"
 *                                        key_map="18.3. Centre-axle trailer:…kg"
 *                                          value_map="ENUM:N/A=__NULL__,NULL=__NULL__,*=DC"
 *                                        辅助行 value_map=
 *                                          "MULTI_GROUP_JOIN_SEP::SEMICOLON:\x7C\x7C:
 *                                           18.1. Drawbar trailer\x3A…kg,18.3. Centre-axle trailer\x3A…kg"
 *                                      18.1、18.3 均非空 → segment 内两槛位按声明顺序输出，
 *                                      用 slotSep(;) 连接 → "DB;DC"，segment 命中 → 追加 segTerm(||)
 *                                      → 结果 "DB;DC||"；仅18.1非空 → 仅 DB 命中 → "DB||"
 *                                      （注：此例只有 1 个 segment，未用到 ~ 分隔符；slotSep 本身配置
 *                                      为字面量 SEMICOLON 没问题，因为它只是描述符里的别名字符串，
 *                                      不是真实的 ; 字符，不会被 splitToRows 误切）
 *
 *                                    示例二（TechnicallyPermissibleMaximumTowableMass，目标 "800;1500|750|"）：
 *                                      链内三行（value_map 均为 STRIP_UNIT，取去单位纯数字）：
 *                                        key_map="18.1. Drawbar trailer:…kg"      value_map="STRIP_UNIT"
 *                                        key_map="18.3. Centre-axle trailer:…kg"  value_map="STRIP_UNIT"
 *                                        key_map="18.4. Un-braked trailer:…kg"    value_map="STRIP_UNIT"
 *                                        辅助行 value_map=
 *                                          "MULTI_GROUP_JOIN_SEP::SEMICOLON:PIPE:
 *                                           18.1. Drawbar trailer\x3A…kg,18.3. Centre-axle trailer\x3A…kg~
 *                                           18.4. Un-braked trailer\x3A…kg"
 *                                      （两个 segment 之间用 ~ 分隔，不是 ;）
 *                                      18.1=800kg 18.3=1500kg 18.4=750kg
 *                                      → segment1 [18.1,18.3] → "800;1500" + "|"
 *                                      → segment2 [18.4]      → "750" + "|"
 *                                      → 结果 "800;1500|750|"
 *
 *                                    示例三（TechnicallyPermissibleMaximumCombinationMass，目标 "||4142"）：
 *                                      链内一行：key_map="16.4. Technically permissible..." value_map="STRIP_UNIT"
 *                                      辅助行 value_map=
 *                                          "MULTI_GROUP_JOIN_SEP:\x7C\x7C::: 16.4. Technically permissible...\x3A…kg"
 *                                      → 结果 "||4142"
 *  FIXED_SLOT_BUDGET_JOIN:{sep}:{budget}
 *                                    多 key_map 链场景下的「固定分隔符配额」拼接。须单独占一行放在
 *                                    同一 uuid 链中（该行 key_map/dict_label 可留空），仅作配置声明，
 *                                    不参与值转换，与 GROUP_JOIN_SEP / MULTI_GROUP_JOIN_SEP 互斥。
 *                                    适用场景：分隔符总数固定不变（不随命中项数增减），命中 N 项时，
 *                                    项之间插入 (N-1) 个 sep，结尾再补足 budget-(N-1) 个 sep，
 *                                    使分隔符总数恒为 budget；全部未命中（N=0）则整体返回 null（不返回
 *                                    任何分隔符）。链内其它行各自正常转换（如 ENUM），其去重后的非空
 *                                    结果按原顺序参与本拼接（自动去重：同一值只计入一次）。
 *                                      · sep    — 分隔符（别名或字面量，如 PIPE → |）
 *                                      · budget — 固定配额（正整数）
 *                                    示例（BrakedTypeTrail，budget=2，对应 BRK/UNB 两个槛位）：
 *                                      18.1 → ENUM:N/A=__NULL__,NULL=__NULL__,*=BRK
 *                                      18.3 → ENUM:N/A=__NULL__,NULL=__NULL__,*=BRK
 *                                      18.4 → ENUM:N/A=__NULL__,NULL=__NULL__,*=UNB
 *                                      辅助行 → FIXED_SLOT_BUDGET_JOIN:PIPE:2
 *                                      只命中18.1或18.3（去重后1个BRK） → "BRK||"
 *                                      只命中18.4（1个UNB） → "UNB||"
 *                                      18.1/18.3 与 18.4 都命中（去重后2个值） → "BRK|UNB|"
 *                                      全部未命中 → null
 *  RANGE_MAP:{条件1}={目标值1};{条件2}={目标值2};...
 *                                    按数值范围映射。先从原值提取第1个数字，再逐条匹配范围表达式。
 *                                    条件格式：{lo}{op1}VALUE{op2}{hi}，支持 <= 和 < 两种比较符。
 *                                    也支持单侧条件：VALUE<={hi} 或 {lo}<=VALUE。
 *                                    * 作为兜底默认值键（未命中时返回）。
 *                                    示例：RANGE_MAP:6.6<=VALUE<=7.7=B;7.7<VALUE<=8.8=C;*=D
 *                                          原值 "6.8N/kN" → 提取 6.8 → 命中 6.6<=VALUE<=7.7 → 返回 "B"
 *  RIM_SPEC:BOTH                     提取轮毂规格，原样保留 直径x宽度J 片段，支持以下宽度格式：
 *                                      · 整数：       18x7J
 *                                      · 欧式小数：   18x7,5J（逗号作小数点）
 *                                      · 分数：       18x7 1/2J（空格+分子/分母）
 *                                    输入示例：215/55R18 99H 18x7J ET33 5.96N/kN C1
 *                                             215/55R18 99H 18x7,5J ET33 5.96N/kN C1
 *                                             235/50R19 103V 19x7 1/2J ET47 6.28N/kN C1
 *                                    输出示例：18x7J  /  18x7,5J  /  19x7 1/2J
 *  AXIS_DRIVE                        格式：AXIS_DRIVE:{sep}:{trueVal}:{falseVal}:{keyword1}:{keyword2}
 *                                    默认：sep=; trueVal=Y falseVal=N keyword1=front keyword2=rear
 *  管道链式执行：PIPE:{rule1}|{rule2}|  将多个 value_map 规则串联，前一步输出作为下一步输入
 *  EXTRACT_ALL                       提取所有正则匹配项并拼接,找出所有匹配，取指定分组，用 outSep 拼接
 *                                   格式：EXTRACT_ALL:{regex}:{group}:{outSep}
 *  EXTRACT_PATTERN_OR_DIRECT:{regex}:{group}
 *                                    正则提取分组；匹配成功返回 group，匹配失败返回原值（透传）。
 *                                    与 EXTRACT_PATTERN 的唯一区别：未匹配时不返回 null，
 *                                    常用于 PIPE 首步做"有则提取、无则透传"。
 *                                    示例：PIPE:EXTRACT_PATTERN_OR_DIRECT:(?:Engine\s*\x3A\s*)([^,\x3B]+):1|DICT_MAP
 *                                          "Engine: Positive ignition,four stroke" → "Positive ignition"
 *                                          "Positive ignition"                     → "Positive ignition"（透传）
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
            String safeDescriptor = encodeDescriptor(descriptor);
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
                    String regex = restoreEscapes(parts[1].replace("\\\\", "\\"));
                    int group   = parseIndex(parts[2], 1);
                    Matcher m = Pattern.compile(regex, Pattern.UNICODE_CHARACTER_CLASS).matcher(raw);
                    return m.find() ? m.group(group) : null;
                }

                // ── 正则提取分组（失败时透传原值，不返回 null）────────
                case "EXTRACT_PATTERN_OR_DIRECT": {
                    // value_map = EXTRACT_PATTERN_OR_DIRECT:{regex}:{group}
                    // 匹配成功 → 返回指定分组；匹配失败 → 返回原值（透传，不中断 PIPE）
                    if (parts.length < 3) {
                        log.warn("[ValueMappingParser] EXTRACT_PATTERN_OR_DIRECT 缺少参数: {}", descriptor);
                        return raw.isEmpty() ? null : raw;
                    }
                    String regex = restoreEscapes(parts[1].replace("\\\\", "\\"));
                    int group    = parseIndex(parts[2], 1);
                    Matcher m = Pattern.compile(regex, Pattern.UNICODE_CHARACTER_CLASS).matcher(raw);
                    if (m.find()) {
                        String extracted = m.group(group);
                        return (extracted != null) ? extracted.trim() : raw;
                    }
                    // 未命中：原值透传，保证 PIPE 链不中断
                    return raw.isEmpty() ? null : raw;
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
                    int start, end;
                    try {
                        start = Integer.parseInt(parts[1].trim());
                        end   = Integer.parseInt(parts[2].trim());
                    } catch (NumberFormatException e) {
                        log.error("[ValueMappingParser] SUBSTRING 参数必须为整数，实际配置: start='{}' end='{}', valueMap={}",
                                parts[1].trim(), parts[2].trim(), descriptor);
                        return null;
                    }
                    if (start < 0 || start >= raw.length()) {
                        log.warn("[ValueMappingParser] SUBSTRING start={} 超出字符串长度 {}: raw='{}'",
                                start, raw.length(), raw);
                        return null;
                    }
                    if (end == -1 || end > raw.length()) end = raw.length();
                    if (end < start) {
                        log.warn("[ValueMappingParser] SUBSTRING end={} 小于 start={}: raw='{}'",
                                end, start, raw);
                        return null;
                    }
                    return raw.substring(start, end);
                }

                // ── 正则多分组拼接 ────────────────────────────────
                case "EXTRACT_PATTERN_JOIN": {
                    // value_map 格式：EXTRACT_PATTERN_JOIN:{regex}:{g1}:{g2}:{sep}
                    if (parts.length < 4) {
                        log.warn("[ValueMappingParser] EXTRACT_PATTERN_JOIN 参数不足: {}", descriptor);
                        return null;
                    }
                    String regex = restoreEscapes(parts[1].replace("\\\\", "\\"));
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
                    return (part1 + sep + part2).trim();
                }

                // ── 数值范围映射 ──────────────────────────────────
                // value_map 格式：RANGE_MAP:{条件1}={目标值1};{条件2}={目标值2};...
                // 条件格式：{lo}{op1}VALUE{op2}{hi}，支持 <= 和 < 两种比较符，也支持单侧。
                // * 作为兜底默认值键。
                // 示例：RANGE_MAP:6.6<=VALUE<=7.7=B;7.7<VALUE<=8.8=C;*=D
                case "RANGE_MAP": {
                    if (parts.length < 2) return null;
                    // 从已编码的 safeDescriptor 截取规则段，再统一还原转义，
                    // 与 EXTRACT_PATTERN 等分支保持一致，避免操作原始 descriptor 造成双重替换。
                    String rangeSpec = restoreEscapes(
                            safeDescriptor.substring("RANGE_MAP:".length()));
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

                // ── 正则全局提取所有匹配项并拼接 ─────────────────────
                case "EXTRACT_ALL_PATTERN": {
                    // value_map = EXTRACT_ALL_PATTERN:{regex}:{sep}:{group}
                    // group 可选，默认 0（整个匹配）
                    if (parts.length < 3) {
                        log.warn("[ValueMappingParser] EXTRACT_ALL_PATTERN 缺少参数: {}", descriptor);
                        return null;
                    }
                    String regex  = restoreEscapes(parts[1].replace("\\\\", "\\"));
                    String outSep = unescapeSep(parts[2]);
                    int    group  = (parts.length >= 4) ? parseIndex(parts[3], 0) : 0;
                    Matcher m = Pattern.compile(regex, Pattern.UNICODE_CHARACTER_CLASS).matcher(raw);
                    StringJoiner sj = new StringJoiner(outSep);
                    while (m.find()) {
                        String val = m.group(group);
                        if (val != null && !val.isEmpty()) sj.add(val);
                    }
                    String result = sj.toString();
                    return result.isEmpty() ? null : result;
                }

                // ── 提取轮毂规格：RIM_SPEC:BOTH ──────────────────
                case "RIM_SPEC": {
                    if (parts.length < 2) return null;
                    String part = parts[1].toUpperCase();

                    if ("BOTH".equals(part)) {
                        Pattern rimPattern = Pattern.compile(
                                "\\d+x(?:\\d+\\s+\\d+/\\d+|\\d+,\\d+|\\d+)J");
                        Matcher rimMatcher = rimPattern.matcher(raw);
                        StringJoiner sj = new StringJoiner(";");
                        while (rimMatcher.find()) {
                            String matched = rimMatcher.group(0);

                            // 分数宽度 → 欧式小数转换：20x8 1/2J → 20x8,5J
                            Matcher fracMatcher = Pattern.compile(
                                    "(\\d+x)(\\d+)\\s+(\\d+)/(\\d+)(J)").matcher(matched);
                            if (fracMatcher.find()) {
                                int whole = Integer.parseInt(fracMatcher.group(2));
                                double frac = (double) Integer.parseInt(fracMatcher.group(3))
                                        / Integer.parseInt(fracMatcher.group(4));
                                double width = whole + frac;
                                String widthStr = (frac == 0)
                                        ? String.valueOf(whole)
                                        : String.valueOf(width).replace(".", ",");
                                matched = fracMatcher.group(1) + widthStr + fracMatcher.group(5);
                            }

                            sj.add(matched);
                        }
                        String result = sj.toString();
                        if (result.isEmpty()) {
                            log.warn("[ValueMappingParser] RIM_SPEC:BOTH 未找到轮毂规格片段: {}", raw);
                            return null;
                        }
                        return result;
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
                    // 从已编码的 safeDescriptor 截取管道串，避免原始 descriptor 中的特殊结构干扰 | 切分
                    String pipeLine = safeDescriptor.substring("PIPE:".length());
                    String[] steps = pipeLine.split("\\|", -1);
                    String current = raw;
                    for (String step : steps) {
                        if (current == null) return null;
                        // 还原子步骤中的所有占位符（含转义冒号和正则前瞻/后顾结构）
                        step = restoreEscapes(step.trim().replace("\\x7C", "|"));
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

                // ── 提取所有正则匹配项并拼接 ──────────────────────────
                // value_map = EXTRACT_ALL:{regex}:{group}:{outSep}
                // 找出所有匹配，取指定分组，用 outSep 拼接
                case "EXTRACT_ALL": {
                    if (parts.length < 3) return null;
                    String regex  = restoreEscapes(parts[1].replace("\\\\", "\\"));
                    int    group  = parseIndex(parts[2], 1);
                    String outSep = (parts.length >= 4) ? unescapeSep(parts[3]) : ";";
                    Matcher m = Pattern.compile(regex).matcher(raw);
                    StringJoiner sj = new StringJoiner(outSep);
                    while (m.find()) {
                        String val = null;
                        if (group == 0) {
                            for (int i = 1; i <= m.groupCount(); i++) {
                                if (m.group(i) != null && !m.group(i).isEmpty()) {
                                    val = m.group(i);
                                    break;
                                }
                            }
                        } else if (group <= m.groupCount()) {
                            val = m.group(group);
                        }
                        if (val != null && !val.isEmpty()) sj.add(val);
                    }
                    String result = sj.toString();
                    return result.isEmpty() ? null : result;
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

            // 多值场景：raw 含 ; 分隔的多组值（如 PIPE 上游 EXTRACT_ALL_PATTERN 输出
            // "6.17N/kN;6.87N/kN"），逐项分别映射后用 ; 重新拼接，单值时自然退化为原逻辑。
            if (raw.contains(";")) {
                String[] items = raw.split(";", -1);
                StringJoiner sj = new StringJoiner(";");
                for (String item : items) {
                    String mapped = dictMapSingle(item.trim(), mergedDictMap);
                    sj.add(mapped == null ? item.trim() : mapped);
                }
                return sj.toString();
            }

            String result = dictMapSingle(raw, mergedDictMap);
            return result == null ? raw : result;
        }

        // ── PIPE 步骤：拆分子步骤，逐步执行，透传 mergedDictMap ──
        if (upperStep.startsWith("PIPE:")) {
            String pipeLine = encodeDescriptor(step.trim()).substring("PIPE:".length());
            String[] steps = pipeLine.split("\\|", -1);
            String current = rawValue;
            for (String s : steps) {
                if (current == null) return null;
                s = restoreEscapes(s.trim().replace("\\x7C", "|"));
                current = convertStep(current, s, mergedDictMap);
                if (EMPTY_SENTINEL.equals(current)) return EMPTY_SENTINEL;
            }
            return current;
        }

        // ── 其他规则：走常规 convert ──────────────────────────────
        return convert(rawValue, step);
    }

    /**
     * 对单个值执行 DICT_MAP 映射逻辑：精确匹配 → 范围 key 匹配 → 兜底 {@code *} 键。
     * 从 {@link #convertStep} 的 DICT_MAP 分支中抽出，供单值与多值（按 ; 拆分后逐项）场景共用。
     *
     * @param raw           已 trim 的单个待映射值（非空，非 ;-分隔的复合值）
     * @param mergedDictMap 合并后的映射表
     * @return 命中的目标值；未命中且无兜底 * 键时返回 null
     */
    private static String dictMapSingle(String raw, Map<String, String> mergedDictMap) {
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
        // 反转义 HTML 实体，兼容前端或数据库写入时误编码的情况
        // 例如 "RRC&lt;=6.5" → "RRC<=6.5"，否则范围 key 匹配会失败
        valueConnectionJson = valueConnectionJson
                .replace("&lt;",   "<")
                .replace("&gt;",   ">")
                .replace("&amp;",  "&")
                .replace("&quot;", "\"")
                .replace("&#39;",  "'");
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
            case "SPACE":       return " ";
            default:            return sep;
        }
    }

    private static int parseIndex(String s, int defaultVal) {
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return defaultVal; }
    }

    private static String encodeDescriptor(String descriptor) {
        return descriptor
                .replace("\\x3A",  "\u0001")
                .replace("(?<=",   "\u0005")
                .replace("(?<!",   "\u0006")
                .replace("(?:",    "\u0002")
                .replace("(?=",    "\u0003")
                .replace("(?!",    "\u0004")
                .replace("\\s",    "\u0007")
                .replace("\\d",    "\u0008")
                .replace("\\w",    "\u000B")
                .replace("\\S",    "\u000C")
                .replace("\\D",    "\u000E")
                .replace("\\W",    "\u000F");
    }

    /**
     * 将 {@link #encodeDescriptor} 写入的私有占位符还原为原始字符序列。
     * 所有需要在分割后恢复真实内容的字段（regex、rangeSpec 等）均应调用此方法。
     */
    private static String restoreEscapes(String s) {
        return s.replace("\u0001", ":")
                .replace("\u0002", "(?:")
                .replace("\u0003", "(?=")
                .replace("\u0004", "(?!")
                .replace("\u0005", "(?<=")
                .replace("\u0006", "(?<!")
                .replace("\u0007", "\\s")
                .replace("\u0008", "\\d")
                .replace("\u000B", "\\w")
                .replace("\u000C", "\\S")
                .replace("\u000E", "\\D")
                .replace("\u000F", "\\W");
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
     * 从 value_map 描述符中提取 GROUP_JOIN_SEP 声明的输出分隔符、去重与每项后缀开关。
     *
     * <p>格式：{@code GROUP_JOIN_SEP:{inSep}:{outSep}[:{dedup}[:{segTerm}]]}
     * <ul>
     *   <li>{inSep}   — 历史占位字段，当前未使用，仅为兼容旧数据格式保留</li>
     *   <li>{outSep}  — 项间插入时使用的分隔符（segTerm 非空时不使用该语义，见下）</li>
     *   <li>{dedup}   — 可选，非空即开启去重（任意非空字符串均可，不要求 TRUE/FALSE 字面量）；
     *                   开启时，拼接前检查该值是否已存在于已拼接结果中，已存在则跳过</li>
     *   <li>{segTerm} — 可选，非空时每个命中项（含最后一项）后都追加一次该值，
     *                   取代「项间插入」语义；为空（默认）则使用 outSep 做项间插入</li>
     * </ul>
     *
     * @param valueMap value_map 字段值
     * @return 长度为4的数组 [outSep, dedup, segTerm, reserved]：
     *         下标0=outSep；下标1="true"/"false"（dedup 是否开启）；
     *         下标2=segTerm（可能为空字符串，空表示未开启）；下标3 预留恒为 ""。
     *         非 GROUP_JOIN_SEP 规则返回 null
     */
    public static String[] extractGroupJoinSep(String valueMap) {
        if (StringUtils.isBlank(valueMap)) return null;
        String trimmed = valueMap.trim();
        if (!trimmed.toUpperCase().startsWith("GROUP_JOIN_SEP:")) return null;
        String[] parts = trimmed.split(":", 5);
        if (parts.length < 3) return null;
        String outSep  = unescapeSep(parts[2]);
        boolean dedup  = parts.length >= 4 && StringUtils.isNotBlank(parts[3]);
        String segTerm = (parts.length >= 5) ? unescapeSep(parts[4].trim()) : "";
        return new String[]{
                outSep,                  // outSep
                String.valueOf(dedup),   // dedup
                segTerm,                 // segTerm（空字符串表示未开启）
                ""                       // 预留位
        };
    }

    // =====================================================
    //  MULTI_GROUP_JOIN_SEP：多 key_map 链「分段 + 槛位」拼接
    // =====================================================

    /** 一个 segment 的解析结果：内部按声明顺序引用的多个 key_map（精确匹配链内其它行）。 */
    public static final class MultiGroupSegment {
        public final List<String> keyMaps;
        public MultiGroupSegment(List<String> keyMaps) { this.keyMaps = keyMaps; }
    }

    /** {@code MULTI_GROUP_JOIN_SEP} 描述符的解析结果。 */
    public static final class MultiGroupJoinSpec {
        public final String prefix;
        public final String slotSep;
        public final String segTerm;
        public final List<MultiGroupSegment> segments;
        public MultiGroupJoinSpec(String prefix, String slotSep, String segTerm, List<MultiGroupSegment> segments) {
            this.prefix = prefix;
            this.slotSep = slotSep;
            this.segTerm = segTerm;
            this.segments = segments;
        }
    }

    /**
     * 解析 {@code MULTI_GROUP_JOIN_SEP} 描述符。
     *
     * <p>格式：{@code MULTI_GROUP_JOIN_SEP:{prefix}:{slotSep}:{segTerm}:{seg1}~{seg2}~...}，
     * 每个 segment 内用 {@code ,} 分隔一个或多个 key_map 字符串（须与链中其它行的 key_map 完全匹配，
     * key_map 内若含 {@code :}、{@code ,}、{@code ~} 等结构符需用 \x3A / \x2C / \x7E 转义）。
     *
     * <p><b>重要：</b>segment 分隔符固定使用 {@code ~}，不使用 {@code ;}，因为前端编辑页面保存时
     * （{@code SysDictDataServiceImpl#splitToRows}）会把整条 value_map 字符串按 {@code ;} 切分、
     * 拆成同组多行写入数据库，若描述符内部使用 {@code ;} 会被这层逻辑错误截断。
     *
     * @param valueMap value_map 字段值
     * @return 解析结果；非 MULTI_GROUP_JOIN_SEP 规则或格式不足返回 null
     */
    public static MultiGroupJoinSpec extractMultiGroupJoinSep(String valueMap) {
        if (StringUtils.isBlank(valueMap)) return null;
        String trimmed = valueMap.trim();
        if (!trimmed.toUpperCase().startsWith("MULTI_GROUP_JOIN_SEP:")) return null;

        // 转义保护冒号等结构符，再按 : 切分前4段，剩余整体作为 segment 规格
        String safe = encodeDescriptor(trimmed);
        String body = safe.substring("MULTI_GROUP_JOIN_SEP:".length());
        String[] head = body.split(":", 4);
        if (head.length < 4) {
            log.warn("[ValueMappingParser] MULTI_GROUP_JOIN_SEP 参数不足: {}", valueMap);
            return null;
        }
        String prefix  = restoreEscapes(unescapeSep(head[0]).replace("\\x7C", "|"));
        String slotSep = restoreEscapes(unescapeSep(head[1]).replace("\\x7C", "|"));
        String segTerm = restoreEscapes(unescapeSep(head[2]).replace("\\x7C", "|"));
        String segSpec = restoreEscapes(head[3]);

        List<MultiGroupSegment> segments = new ArrayList<>();
        for (String segText : segSpec.split("~")) {
            if (StringUtils.isBlank(segText)) continue;
            List<String> keyMaps = new ArrayList<>();
            for (String km : segText.split(",")) {
                String trimmedKm = km.trim()
                        .replace("\\x3A", ":")
                        .replace("\\x2C", ",")
                        .replace("\\x7E", "~");
                if (!trimmedKm.isEmpty()) keyMaps.add(trimmedKm);
            }
            if (!keyMaps.isEmpty()) segments.add(new MultiGroupSegment(keyMaps));
        }

        if (segments.isEmpty()) {
            log.warn("[ValueMappingParser] MULTI_GROUP_JOIN_SEP 未解析出任何 segment: {}", valueMap);
            return null;
        }
        return new MultiGroupJoinSpec(prefix, slotSep, segTerm, segments);
    }

    /**
     * 根据 {@link MultiGroupJoinSpec} 及「key_map → 已转换值」映射，执行分段拼接。
     *
     * <p>按 segment 声明顺序遍历，每个 segment 内按 keyMaps 声明顺序取值
     * （值为空/null 视为未命中，跳过），命中的值用 {@code slotSep} 连接；
     * 该 segment 若至少一个槛位命中，则在结果末尾追加 {@code segTerm}；
     * 若该 segment 内所有槛位均未命中，则整个 segment（含 segTerm）省略。
     * 最终若至少有一个 segment 命中，再在结果前加上 {@code prefix}。
     *
     * @param spec               已解析的描述符
     * @param convertedByKeyMap  key_map → 该行经自身 value_map 转换后的值（已转换、未参与拼接）
     * @return 拼接结果；若所有 segment 均未命中（无任何实际数据），返回 null
     *         （即使 prefix 非空，也不会只返回一个光秃秃的 prefix）
     */
    public static String applyMultiGroupJoinSep(MultiGroupJoinSpec spec, Map<String, String> convertedByKeyMap) {
        if (spec == null) return null;
        StringBuilder body = new StringBuilder();
        boolean anySegmentHit = false;

        for (MultiGroupSegment segment : spec.segments) {
            StringJoiner sj = new StringJoiner(spec.slotSep == null ? "" : spec.slotSep);
            for (String keyMap : segment.keyMaps) {
                String val = (convertedByKeyMap != null) ? convertedByKeyMap.get(keyMap) : null;
                if (StringUtils.isNotBlank(val)) sj.add(val);
            }
            String segResult = sj.toString();
            if (!segResult.isEmpty()) {
                anySegmentHit = true;
                body.append(segResult);
                if (spec.segTerm != null) body.append(spec.segTerm);
            }
        }

        if (!anySegmentHit) return null;
        String prefix = (spec.prefix != null) ? spec.prefix : "";
        return prefix + body;
    }

    // =====================================================
    //  FIXED_SLOT_BUDGET_JOIN：固定分隔符配额拼接
    // =====================================================

    /** {@code FIXED_SLOT_BUDGET_JOIN} 描述符的解析结果。 */
    public static final class FixedSlotBudgetSpec {
        public final String sep;
        public final int budget;
        public FixedSlotBudgetSpec(String sep, int budget) {
            this.sep = sep;
            this.budget = budget;
        }
    }

    /**
     * 解析 {@code FIXED_SLOT_BUDGET_JOIN} 描述符。
     *
     * <p>格式：{@code FIXED_SLOT_BUDGET_JOIN:{sep}:{budget}}。
     *
     * @param valueMap value_map 字段值
     * @return 解析结果；非该规则或格式非法返回 null
     */
    public static FixedSlotBudgetSpec extractFixedSlotBudgetJoin(String valueMap) {
        if (StringUtils.isBlank(valueMap)) return null;
        String trimmed = valueMap.trim();
        if (!trimmed.toUpperCase().startsWith("FIXED_SLOT_BUDGET_JOIN:")) return null;
        String[] parts = trimmed.split(":", 3);
        if (parts.length < 3) {
            log.warn("[ValueMappingParser] FIXED_SLOT_BUDGET_JOIN 参数不足: {}", valueMap);
            return null;
        }
        String sep = unescapeSep(parts[1]);
        int budget;
        try {
            budget = Integer.parseInt(parts[2].trim());
        } catch (NumberFormatException e) {
            log.warn("[ValueMappingParser] FIXED_SLOT_BUDGET_JOIN budget 非法: {}", valueMap);
            return null;
        }
        return new FixedSlotBudgetSpec(sep, budget);
    }

    /**
     * 根据 {@link FixedSlotBudgetSpec} 与一组命中值（已去重、按命中顺序排列）执行「固定分隔符配额」拼接。
     *
     * <p>命中 N 项（N&gt;0）时，项之间插入 (N-1) 个 sep，结尾再补足 (budget-(N-1)) 个 sep
     * （若计算结果为负则补 0 个），使分隔符总数恒为 budget；N=0（未传入任何命中值）时返回 null。
     *
     * @param spec       已解析的描述符
     * @param hitValues  已去重、按命中顺序排列的命中值列表
     * @return 拼接结果；hitValues 为空时返回 null
     */
    public static String applyFixedSlotBudgetJoin(FixedSlotBudgetSpec spec, List<String> hitValues) {
        if (spec == null || hitValues == null || hitValues.isEmpty()) return null;
        String sep = (spec.sep != null) ? spec.sep : "";
        int n = hitValues.size();
        int between = n - 1;
        int trailing = spec.budget - between;
        if (trailing < 0) trailing = 0;

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(hitValues.get(i));
            if (i < n - 1) sb.append(sep);
        }
        for (int i = 0; i < trailing; i++) {
            sb.append(sep);
        }
        return sb.toString();
    }
}