package com.ruoyi.system.service;

import com.ruoyi.system.domain.SysI18n;

import java.util.List;

/**
 * 国际化翻译Service接口
 */
public interface ISysI18nService {

    String translate(String langKey, String langCode);

    /**
     * 刷新缓存
     */
    void refreshCache();

    // ==================== 增删改查 ====================

    /**
     * 查询翻译列表
     */
    List<SysI18n> selectSysI18nList(SysI18n sysI18n);

    /**
     * 根据ID查询翻译
     */
    SysI18n selectSysI18nById(String id);

    /**
     * 新增翻译
     * @return 结果
     */
    int insertSysI18n(SysI18n sysI18n);

    /**
     * 修改翻译
     * @return 结果
     */
    int updateSysI18n(SysI18n sysI18n);

    /**
     * 批量删除翻译
     * @return 结果
     */
    int deleteSysI18nByIds(String[] ids);

    /**
     * 删除翻译
     * @return 结果
     */
    int deleteSysI18nById(String id);

    void reloadLocalCache();
}