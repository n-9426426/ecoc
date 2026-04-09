package com.ruoyi.system.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

@Configuration
public class I18nConfig {

    /**
     * 支持的语言列表
     */
    public static final List<Locale> SUPPORTED_LOCALES = Arrays.asList(
            Locale.SIMPLIFIED_CHINESE,// zh_CN
            Locale.US   // en_US
    );

    /**
     * 默认语言
     */
    public static final Locale DEFAULT_LOCALE = Locale.SIMPLIFIED_CHINESE;

    /**
     * 配置语言解析器
     *优先从请求头 Accept-Language 解析语言
     */
    @Bean
    public LocaleResolver localeResolver() {
        AcceptHeaderLocaleResolver resolver = new AcceptHeaderLocaleResolver();
        resolver.setSupportedLocales(SUPPORTED_LOCALES);
        resolver.setDefaultLocale(DEFAULT_LOCALE);
        return resolver;
    }

    /**
     * 将语言字符串转换为标准语言代码
     *例如：en → en_US，zh → zh_CN
     */
    public static String normalizeLangCode(String lang) {
        if (lang == null || lang.isEmpty()) {
            return "zh_CN";
        }
        if (lang.startsWith("en")) {
            return "en_US";
        }
        return "zh_CN";
    }
}
