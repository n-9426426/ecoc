package com.ruoyi.vehicle.domain;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.core.web.domain.BaseEntity;

import java.io.IOException;
import java.util.*;

public class VehicleInfo extends BaseEntity {

    private static final long serialVersionUID = 1L;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 车辆ID */
    private Long vehicleId;

    private List<Long> vehicleIds;

    /** 上传状态 0-未上传 1-已上传 */
    private Integer uploadStatus;

    /** 国家 */
    private String country;

    /** 颜色 */
    private String color;

    /** 证书版本 */
    private String certificateVersion;

    /** WVTA证书编号 */
    private String wvtaNo;

    /** 发证日期 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date issueDate;

    /** 是否回收 */
    private Boolean reclaim = false;

    private String json;

    private transient Map<String, Object> jsonMap;

    private Long xmlTemplateId;

    public Map<String, Object> getJsonMap() {
        if (jsonMap != null) {
            return jsonMap;
        }

        // 如果没有转换过，返回原始 JSON 解析结果（使用 LinkedHashMap 保持顺序）
        if (json == null || json.trim().isEmpty()) {
            return Collections.emptyMap();
        }

        try {
            return MAPPER.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (IOException e) {
            return Collections.emptyMap();
        }
    }

    public void setJsonMap(Map<String, Object> jsonMap) {
        this.jsonMap = jsonMap;
    }

    public Long getVehicleId() {
        return vehicleId;
    }

    public void setVehicleId(Long vehicleId) {
        this.vehicleId = vehicleId;
    }

    public Boolean getReclaim() {
        return reclaim;
    }

    public void setReclaim(Boolean reclaim) {
        this.reclaim = reclaim;
    }

    public List<Long> getVehicleIds() {
        return vehicleIds;
    }

    public void setVehicleIds(List<Long> vehicleIds) {
        this.vehicleIds = vehicleIds;
    }

    public Integer getUploadStatus() {
        return uploadStatus;
    }

    public void setUploadStatus(Integer uploadStatus) {
        this.uploadStatus = uploadStatus;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = color;
    }

    public String getCertificateVersion() {
        return certificateVersion;
    }

    public void setCertificateVersion(String certificateVersion) {
        this.certificateVersion = certificateVersion;
    }

    public String getWvtaNo() {
        return wvtaNo;
    }

    public void setWvtaNo(String wvtaNo) {
        this.wvtaNo = wvtaNo;
    }

    public Date getIssueDate() {
        return issueDate;
    }

    public void setIssueDate(Date issueDate) {
        this.issueDate = issueDate;
    }

    @Override
    public String toString() {
        return "VehicleInfo{" +
                "vehicleId=" + vehicleId +
                ", uploadStatus=" + uploadStatus +
                ", country='" + country + '\'' +
                ", color='" + color + '\'' +
                ", certificateVersion='" + certificateVersion + '\'' +
                ", wvtaNo='" + wvtaNo + '\'' +
                ", issueDate=" + issueDate +
                ", createBy='" + getCreateBy() + '\'' +
                ", createTime=" + getCreateTime() +
                ", updateBy='" + getUpdateBy() + '\'' +
                ", updateTime=" + getUpdateTime() +
                ", remark='" + getRemark() + '\'' +
                '}';
    }

    public String getJson() {
        return json;
    }

    public void setJson(String json) {
        this.json = json;
    }

    public Long getXmlTemplateId() {
        return xmlTemplateId;
    }

    public void setXmlTemplateId(Long xmlTemplateId) {
        this.xmlTemplateId = xmlTemplateId;
    }
}