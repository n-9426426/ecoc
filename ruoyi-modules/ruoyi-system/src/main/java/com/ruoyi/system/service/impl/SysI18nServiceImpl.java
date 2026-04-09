package com.ruoyi.system.service.impl;

import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.common.core.utils.DateUtils;
import com.ruoyi.common.core.utils.StringUtils;
import com.ruoyi.common.core.utils.uuid.UUID;
import com.ruoyi.common.redis.service.RedisService;
import com.ruoyi.system.config.I18nConfig;
import com.ruoyi.system.config.RedisListenerConfig;
import com.ruoyi.system.constant.I18nConstants;
import com.ruoyi.system.domain.SysI18n;
import com.ruoyi.system.mapper.SysI18nMapper;
import com.ruoyi.system.service.ISysI18nService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.annotation.PostConstruct;
import javax.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 国际化翻译Service实现
 */
@Service
public class SysI18nServiceImpl implements ISysI18nService {

    private static final Logger log = LoggerFactory.getLogger(SysI18nServiceImpl.class);

    @Autowired
    private SysI18nMapper i18nMapper;

    @Autowired
    private RedisService redisService;

    @Autowired
    private RedisTemplate redisTemplate;

    /**
     * 本地 JVM 内存缓存
     * key: langCode（如 zh_CN、en_US）
     * value: Map<langKey, langValue>
     * 使用 volatile 保证多线程可见性
     */
    private final Map<String, Map<String, String>> localCache = new ConcurrentHashMap<>();

    // ==================== 初始化 ====================

    /**
     * 项目启动时自动加载所有翻译到本地缓存 + Redis
     */
    @PostConstruct
    public void init() {
        log.info("【i18n】开始初始化翻译缓存...");
        try {
            loadingI18nCache();
            log.info("【i18n】翻译缓存初始化完成");
        } catch (Exception e) {
            log.error("【i18n】翻译缓存初始化失败，原因：{}", e.getMessage(), e);
        }
    }

    /**
     * 加载所有语言翻译到本地缓存 + Redis
     */
    private void loadingI18nCache() {
        loadLangCache(I18nConstants.LANG_ZH_CN);
        loadLangCache(I18nConstants.LANG_EN_US);
    }

    /**
     * 加载指定语言的翻译到本地缓存 + Redis
     */
    private void loadLangCache(String langCode) {
        List<SysI18n> list = i18nMapper.selectByLangCode(langCode);
        if (list == null || list.isEmpty()) {
            log.warn("【i18n】语言 [{}] 暂无翻译数据", langCode);
            return;
        }
        Map<String, String> messages = new HashMap<>();
        list.forEach(item -> messages.put(item.getLangKey(), item.getLangValue()));

        // 1. 存入本地 JVM 缓存（translate 只读此处，无 IO，无阻塞）
        localCache.put(langCode, Collections.unmodifiableMap(messages));

        // 2. 同步存入 Redis（供其他服务节点懒加载使用）
        String cacheKey = I18nConstants.CACHE_KEY_PREFIX + langCode;
        redisService.setCacheObject(cacheKey, messages,
                I18nConstants.CACHE_EXPIRE_HOURS, TimeUnit.HOURS);

        log.info("【i18n】语言 [{}] 加载完成，共 {} 条", langCode, messages.size());
    }

    // ==================== 翻译相关 ====================

    /**
     * 翻译Key
     * 优先级：传入的 langCode > 请求参数 lang > 请求头 Accept-Language > 默认语言 zh_CN
     * ✅ 只读本地内存，无 IO，无阻塞，任何上下文均可安全调用
     */
    @Override
    public String translate(String langKey, String langCode) {
        if (StringUtils.isBlank(langKey)) {
            return langKey;
        }

        String resolvedLang;
        // 优先使用调用方显式传入的 langCode
        if (StringUtils.isNotEmpty(langCode)) {
            resolvedLang = I18nConfig.normalizeLangCode(langCode);
        } else {
            // 否则从当前请求上下文中解析
            resolvedLang = resolveCurrentLang();
        }

        // 命中本地缓存，降级为默认语言
        Map<String, String> messages = localCache.getOrDefault(
                resolvedLang,
                localCache.getOrDefault(I18nConstants.DEFAULT_LANG, Collections.emptyMap())
        );
        return messages.getOrDefault(langKey, langKey);
    }

    /**
     * 刷新缓存
     * 1. 清 Redis
     * 2. 重建本地缓存
     * 3. 发布 Redis 通知，告知其他节点刷新本地缓存
     */
    @Override
    public void refreshCache() {
        // 清 Redis
        redisService.deleteObject(I18nConstants.CACHE_KEY_PREFIX + I18nConstants.LANG_ZH_CN);
        redisService.deleteObject(I18nConstants.CACHE_KEY_PREFIX + I18nConstants.LANG_EN_US);
        // 重建本节点本地缓存 + 写入 Redis
        loadingI18nCache();
        // 发布通知，其他节点收到后只重建本地缓存
        redisTemplate.convertAndSend(RedisListenerConfig.I18N_REFRESH_TOPIC, "reload");
        log.info("【i18n】缓存刷新完成，已通知所有节点");
    }

    @Override
    public void reloadLocalCache() {
        reloadLangLocalCache(I18nConstants.LANG_ZH_CN);
        reloadLangLocalCache(I18nConstants.LANG_EN_US);
    }

    private void reloadLangLocalCache(String langCode) {
        String cacheKey = I18nConstants.CACHE_KEY_PREFIX + langCode;
        Map<String, String> messages = redisService.getCacheObject(cacheKey);
        if (messages != null && !messages.isEmpty()) {
            localCache.put(langCode, Collections.unmodifiableMap(messages));
            log.info("【i18n】语言 [{}] 本地缓存已从 Redis 重建，共 {} 条", langCode, messages.size());
        } else {
            // Redis 中没有则从 DB 加载
            loadLangCache(langCode);
        }
    }

    // ==================== 增删改查 ====================

    /**
     * 查询翻译列表
     */
    @Override
    public List<SysI18n> selectSysI18nList(SysI18n sysI18n) {
        return i18nMapper.selectSysI18nList(sysI18n);
    }

    /**
     * 根据ID查询翻译
     */
    @Override
    public SysI18n selectSysI18nById(String id) {
        return i18nMapper.selectSysI18nById(id);
    }

    /**
     * 新增翻译
     */
    @Override
    public int insertSysI18n(SysI18n sysI18n) {
        // 校验唯一性
        checkLangKeyUnique(sysI18n, true);
        // 生成UUID主键
        sysI18n.setId(UUID.randomUUID().toString(true));
        // 默认状态正常
        if (sysI18n.getStatus() == null) {
            sysI18n.setStatus(I18nConstants.STATUS_NORMAL);
        }
        int rows = i18nMapper.insertSysI18n(sysI18n);
        // 新增成功后刷新缓存
        if (rows > 0) {
            refreshCache();
        }
        return rows;
    }

    /**
     * 修改翻译
     */
    @Override
    public int updateSysI18n(SysI18n sysI18n) {
        // 校验唯一性
        checkLangKeyUnique(sysI18n, false);
        sysI18n.setUpdateTime(DateUtils.getNowDate());
        int rows = i18nMapper.updateSysI18n(sysI18n);
        // 修改成功后刷新缓存
        if (rows > 0) {
            refreshCache();
        }
        return rows;
    }

    /**
     * 批量删除翻译
     */
    @Override
    public int deleteSysI18nByIds(String[] ids) {
        int rows = i18nMapper.deleteSysI18nByIds(ids);
        // 删除成功后刷新缓存
        if (rows > 0) {
            refreshCache();
        }
        return rows;
    }

    /**
     * 删除单条翻译
     */
    @Override
    public int deleteSysI18nById(String id) {
        int rows = i18nMapper.deleteSysI18nById(id);
        if (rows > 0) {
            refreshCache();
        }
        return rows;
    }

    // ==================== 私有方法 ====================

    /**
     * 校验翻译键+语言代码唯一性
     * @param isInsert true=新增校验 false=修改校验
     */
    private void checkLangKeyUnique(SysI18n sysI18n, boolean isInsert) {
        SysI18n exist = i18nMapper.checkLangKeyUnique(sysI18n.getLangKey(), sysI18n.getLangCode(), sysI18n.getIsBackend());
        if (exist == null) {
            return;
        }
        // 修改时，排除自身
        if (!isInsert && exist.getId().equals(sysI18n.getId())) {
            return;
        }
        throw new ServiceException(
                String.format(translate("i18n.lang.key.duplicate", null),
                        sysI18n.getLangKey(), sysI18n.getLangCode())
        );
    }

    /**
     * 从Redis缓存获取翻译数据（缓存未命中则从DB加载）
     * 用于兜底，正常情况下 translate() 直接读本地缓存不走此方法
     */
    private Map<String, String> getCachedMessages(String langCode) {
        String cacheKey = I18nConstants.CACHE_KEY_PREFIX + langCode;

        // 1. 先查 Redis
        Map<String, String> cached = redisService.getCacheObject(cacheKey);
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }

        // 2. 查数据库
        List<SysI18n> list = i18nMapper.selectByLangCode(langCode);
        Map<String, String> messages = new HashMap<>();
        if (list != null && !list.isEmpty()) {
            list.forEach(item -> messages.put(item.getLangKey(), item.getLangValue()));
        }

        // 3. 写入 Redis，缓存24小时
        if (!messages.isEmpty()) {
            redisService.setCacheObject(
                    cacheKey, messages,
                    I18nConstants.CACHE_EXPIRE_HOURS, TimeUnit.HOURS
            );
        }
        return messages;
    }

    /**
     * 解析当前请求的语言代码
     * 优先级：请求参数 lang > 请求头 Accept-Language > 默认 zh_CN
     * 在非 Web 上下文（异步/定时任务）中会直接返回默认语言，不会抛出异常
     */
    private String resolveCurrentLang() {
        try {
            ServletRequestAttributes attributes =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes != null) {
                HttpServletRequest request = attributes.getRequest();
                // 1. 优先从请求参数获取 ?lang=en_US
                String lang = request.getParameter(I18nConstants.PARAM_LANG);
                if (StringUtils.isNotEmpty(lang)) {
                    return I18nConfig.normalizeLangCode(lang);
                }
                // 2. 其次从请求头获取 Accept-Language
                String acceptLang = request.getHeader(I18nConstants.HEADER_LANG);
                if (StringUtils.isNotEmpty(acceptLang)) {
                    return I18nConfig.normalizeLangCode(acceptLang);
                }
            }
        } catch (Exception ignored) {
            // 忽略异常，返回默认语言
        }
        return I18nConstants.DEFAULT_LANG;
    }
}