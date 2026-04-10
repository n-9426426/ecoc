package com.ruoyi.xml.domain;

import com.ruoyi.common.core.web.domain.BaseEntity;
import com.ruoyi.xml.domain.vo.AttributeTreeNode;

import java.util.Date;
import java.util.List;

public class XmlTemplate extends BaseEntity {

    private Long templateId;

    private String templateCode;

    private String templateName;

    private Long modelDictCode;

    private Integer status;

    private String remark;

    private String createBy;

    private Date createTime;

    private String updateBy;

    private Date updateTime;

    private List<AttributeTreeNode> attributeTree;

    public Long getTemplateId() {
        return templateId;
    }

    public void setTemplateId(Long templateId) {
        this.templateId = templateId;
    }

    public String getTemplateCode() {
        return templateCode;
    }

    public void setTemplateCode(String templateCode) {
        this.templateCode = templateCode;
    }

    public String getTemplateName() {
        return templateName;
    }

    public void setTemplateName(String templateName) {
        this.templateName = templateName;
    }

    public Long getModelDictCode() {
        return modelDictCode;
    }

    public void setModelDictCode(Long modelDictCode) {
        this.modelDictCode = modelDictCode;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public String getCreateBy() {
        return createBy;
    }

    public void setCreateBy(String createBy) {
        this.createBy = createBy;
    }

    public Date getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }

    public String getUpdateBy() {
        return updateBy;
    }

    public void setUpdateBy(String updateBy) {
        this.updateBy = updateBy;
    }

    public Date getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(Date updateTime) {
        this.updateTime = updateTime;
    }

    public List<AttributeTreeNode> getAttributeTree() {
        return attributeTree;
    }

    public void setAttributeTree(List<AttributeTreeNode> attributeTree) {
        this.attributeTree = attributeTree;
    }
}