package com.ruoyi.system.mapper;

import com.ruoyi.system.domain.SysI18n;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface SysI18nMapper {
    /**
     * 根据条件查询翻译列表
     */
    List<SysI18n> selectSysI18nList(SysI18n sysI18n);

    /**
     * 根据ID查询翻译
     */
    SysI18n selectSysI18nById(@Param("id") String id);

    /**
     * 新增翻译
     */
    int insertSysI18n(SysI18n sysI18n);

    /**
     * 修改翻译
     */
    int updateSysI18n(SysI18n sysI18n);

    /**
     * 删除翻译
     */
    int deleteSysI18nById(@Param("id") String id);

    /**
     * 批量删除翻译
     */
    int deleteSysI18nByIds(@Param("ids") String[] ids);

    /**
     * 根据语言代码查询所有有效翻译（用于缓存）
     */
    List<SysI18n> selectByLangCode(@Param("langCode") String langCode);

    /**
     * 根据翻译键+语言代码查询单条翻译值（用于缓存）
     */
    String selectValueByKeyAndLang(@Param("langKey") String langKey,
                                   @Param("langCode") String langCode);

    /**
     * 批量查询翻译（用于缓存）
     */
    List<SysI18n> selectByKeysAndLang(@Param("langKeys") List<String> langKeys,
                                      @Param("langCode") String langCode);

    /**
     * 校验翻译键+语言代码是否唯一
     */
    SysI18n checkLangKeyUnique(@Param("langKey") String langKey,
                               @Param("langCode") String langCode,
                               @Param(("isBackend")) Integer isBackend);
}
