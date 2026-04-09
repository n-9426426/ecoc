package com.ruoyi.xml.domain;

import com.ruoyi.common.core.web.domain.BaseEntity;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;

/**
 * XML版本历史对象 xml_version
 */
public class XmlVersion extends BaseEntity {
    private static final long serialVersionUID = 1L;

    /** ID */
    private Long id;

    /** 文件ID */
    private Long fileId;

    /** 版本号 */
    private String version;

    /** 文件路径 */
    private String filePath;

    /** 变更类型 */
    private String changeType;

    /** 变更描述 */
    private String changeDesc;

    /** 差异内容 */
    private String diffContent;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getFileId() {
        return fileId;
    }

    public void setFileId(Long fileId) {
        this.fileId = fileId;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public String getChangeType() {
        return changeType;
    }

    public void setChangeType(String changeType) {
        this.changeType = changeType;
    }

    public String getChangeDesc() {
        return changeDesc;
    }

    public void setChangeDesc(String changeDesc) {
        this.changeDesc = changeDesc;
    }

    public String getDiffContent() {
        return diffContent;
    }

    public void setDiffContent(String diffContent) {
        this.diffContent = diffContent;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this, ToStringStyle.MULTI_LINE_STYLE)
                .append("id", getId())
                .append("fileId", getFileId())
                .append("version", getVersion())
                .append("filePath", getFilePath())
                .append("changeType", getChangeType())
                .append("changeDesc", getChangeDesc())
                .append("diffContent", getDiffContent())
                .append("createBy", getCreateBy())
                .append("createTime", getCreateTime())
                .toString();
    }
}