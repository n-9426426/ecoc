package com.ruoyi.vehicle.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.util.Date;
import java.util.List;

public class XmlTemplateVo {

    private Long templateId;

    private String templateCode;

    private String templateName;

    private Long modelDictCode;

    private String modelDictLabel;

    private Integer status;

    private String remark;

    private String createBy;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;

    private String country;

    private Long energyType;

    private String version;

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

    public String getModelDictLabel() {
        return modelDictLabel;
    }

    public void setModelDictLabel(String modelDictLabel) {
        this.modelDictLabel = modelDictLabel;
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

    public List<AttributeTreeNode> getAttributeTree() {
        return attributeTree;
    }

    public void setAttributeTree(List<AttributeTreeNode> attributeTree) {
        this.attributeTree = attributeTree;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public Long getEnergyType() {
        return energyType;
    }

    public void setEnergyType(Long energyType) {
        this.energyType = energyType;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }
}