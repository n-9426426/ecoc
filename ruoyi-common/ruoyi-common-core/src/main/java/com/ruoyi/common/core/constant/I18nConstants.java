package com.ruoyi.common.core.constant;

public class I18nConstants {
    /** 状态：正常 */
    public static final Integer STATUS_NORMAL = 0;

    /** 状态：停用 */
    public static final Integer STATUS_DISABLE = 1;

    /** 默认语言 */
    public static final String DEFAULT_LANG = "zh_CN";

    /** 英文语言 */
    public static final String LANG_EN_US = "en_US";

    /** 中文语言 */
    public static final String LANG_ZH_CN = "zh_CN";

    /** Redis缓存Key前缀 */
    public static final String CACHE_KEY_PREFIX = "sys:i18n:";

    /** 缓存时长（小时） */
    public static final long CACHE_EXPIRE_HOURS = 24L;

    /** 请求头语言字段*/
    public static final String HEADER_LANG = "Accept-Language";

    /** 请求参数语言字段 */
    public static final String PARAM_LANG = "lang";
}
