package com.ruoyi.xml.domain.vo;

import java.util.List;

public class AttributeTreeNode {

    private Long dictCode;

    private String dictLabel;

    private String attrPath;

    private String defaultValue;

    private Integer isRequired;

    private Integer isEditable;

    private Integer sortOrder;

    private List<AttributeTreeNode> children;

    public Long getDictCode() {
        return dictCode;
    }

    public void setDictCode(Long dictCode) {
        this.dictCode = dictCode;
    }

    public String getDictLabel() {
        return dictLabel;
    }

    public void setDictLabel(String dictLabel) {
        this.dictLabel = dictLabel;
    }

    public String getAttrPath() {
        return attrPath;
    }

    public void setAttrPath(String attrPath) {
        this.attrPath = attrPath;
    }

    public String getDefaultValue() {
        return defaultValue;
    }

    public void setDefaultValue(String defaultValue) {
        this.defaultValue = defaultValue;
    }

    public Integer getIsRequired() {
        return isRequired;
    }

    public void setIsRequired(Integer isRequired) {
        this.isRequired = isRequired;
    }

    public Integer getIsEditable() {
        return isEditable;
    }

    public void setIsEditable(Integer isEditable) {
        this.isEditable = isEditable;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public List<AttributeTreeNode> getChildren() {
        return children;
    }

    public void setChildren(List<AttributeTreeNode> children) {
        this.children = children;
    }
}