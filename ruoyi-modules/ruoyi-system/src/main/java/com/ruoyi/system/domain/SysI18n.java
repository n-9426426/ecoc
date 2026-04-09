package com.ruoyi.system.domain;

import com.ruoyi.common.core.web.domain.BaseEntity;

public class SysI18n extends BaseEntity {

    /** 主键UUID */
    private String id;

    /** 翻译键 */
    private String langKey;

    /** 语言代码 zh_CN/en_US */
    private String langCode;

    /** 翻译内容 */
    private String langValue;

    /** 所属模块 */
    private String module;

    /** 状态 0正常 1停用 */
    private Integer status;

    /** 是否后端数据 */
    private Integer isBackend;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getLangKey() {
        return langKey;
    }

    public void setLangKey(String langKey) {
        this.langKey = langKey;
    }

    public String getLangCode() {
        return langCode;
    }

    public void setLangCode(String langCode) {
        this.langCode = langCode;
    }

    public String getLangValue() {
        return langValue;
    }

    public void setLangValue(String langValue) {
        this.langValue = langValue;
    }

    public String getModule() {
        return module;
    }

    public void setModule(String module) {
        this.module = module;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    @Override
    public String toString() {
        return "SysI18n{" +
                "id='" + id + '\'' +
                ", langKey='" + langKey + '\'' +
                ", langCode='" + langCode + '\'' +
                ", langValue='" + langValue + '\'' +
                ", module='" + module + '\'' +
                ", status=" + status +
                '}';
    }

    public Integer getIsBackend() {
        return isBackend;
    }

    public void setIsBackend(Integer isBackend) {
        this.isBackend = isBackend;
    }
}
