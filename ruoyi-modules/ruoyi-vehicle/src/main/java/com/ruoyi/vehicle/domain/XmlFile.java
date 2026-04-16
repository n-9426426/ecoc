package com.ruoyi.vehicle.domain;

import com.ruoyi.common.core.web.domain.BaseEntity;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;

/**
 * XML文件对象 xml_file
 */
public class XmlFile extends BaseEntity {
    private static final long serialVersionUID = 1L;

    /** ID */
    private Long id;

    private String vin;

    /** 文件名 */
    private String fileName;

    /** 文件路径 */
    private String filePath;

    /** 文件大小 */
    private Long fileSize;

    /** 文件分级 */
    private String fileLevel;

    /** 版本号 */
    private String version;

    /** 是否最新版本 */
    private Boolean isLatest;

    /** CoC PDF路径 */
    private String pdfPath;

    /** 状态(0正常 1停用) */
    private String status;

    /** 是否回收 */
    private Boolean reclaim = false;

    private String content;

    private Long xmlTemplateId;

    private String xmlTemplateName;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }

    public String getFileLevel() {
        return fileLevel;
    }

    public void setFileLevel(String fileLevel) {
        this.fileLevel = fileLevel;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public Boolean getIsLatest() {
        return isLatest;
    }

    public void setIsLatest(Boolean isLatest) {
        this.isLatest = isLatest;
    }

    public String getPdfPath() {
        return pdfPath;
    }

    public void setPdfPath(String pdfPath) {
        this.pdfPath = pdfPath;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Boolean getReclaim() {
        return reclaim;
    }

    public void setReclaim(Boolean reclaim) {
        this.reclaim = reclaim;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this, ToStringStyle.MULTI_LINE_STYLE)
                .append("id", getId())
                .append("fileName", getFileName())
                .append("filePath", getFilePath())
                .append("fileSize", getFileSize())
                .append("fileLevel", getFileLevel())
                .append("version", getVersion())
                .append("isLatest", getIsLatest())
                .append("pdfPath", getPdfPath())
                .append("status", getStatus())
                .append("deleted", getDeleted())
                .append("createBy", getCreateBy())
                .append("createTime", getCreateTime())
                .append("updateBy", getUpdateBy())
                .append("updateTime", getUpdateTime())
                .append("remark", getRemark())
                .toString();
    }

    public String getVin() {
        return vin;
    }

    public void setVin(String vin) {
        this.vin = vin;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Long getXmlTemplateId() {
        return xmlTemplateId;
    }

    public void setXmlTemplateId(Long xmlTemplateId) {
        this.xmlTemplateId = xmlTemplateId;
    }

    public String getXmlTemplateName() {
        return xmlTemplateName;
    }

    public void setXmlTemplateName(String xmlTemplateName) {
        this.xmlTemplateName = xmlTemplateName;
    }
}