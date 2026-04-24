package com.ruoyi.vehicle.domain;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.core.web.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.IOException;
import java.util.*;

@EqualsAndHashCode(callSuper = true)
@Data
public class VehicleInfo extends BaseEntity {

    private static final long serialVersionUID = 1L;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 车辆ID */
    private Long vehicleId;

    private List<Long> vehicleIds;

    /** 上传状态(0=未生成 1=已生成 2=已上传 3=未上传 4=上传失败) */
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

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date issueDateBeginTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date issueDateEndTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createdBeginTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createdEndTime;

    /** 是否回收 */
    private Boolean reclaim = false;

    private String json;

    private transient Map<String, Object> jsonMap;

    private Long xmlTemplateId;

    /** VIN码 */
    private String vin;

    /** 车型代码 */
    private Long vehicleModel;

    private String vehicleModelStr;

    /** 工厂代码 */
    private String factoryCode;

    /** 工厂名称 */
    private String factoryName;

    /** 整车物料号 */
    private String materialNo;

    /** 校验结果(0=未校验 1=校验通过 2=校验失败) */
    private Integer validationResult;

    /** 状态(0=正常 1=停用) */
    private Integer status;

    /** 双色的次色 */
    private String secondaryColor;

    private String vehicleTemplateId;

    private String vehicleTemplateFilepath;

    private String cocTemplateNo;

    private String validationReportJson;

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
}