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

    private List<Integer> uploadStatusList;

    /** 国家 */
    private String country;

    private List<String> countries;

    /** 颜色 */
    private String color;

    private List<String> colors;

    /** 证书版本 */
    private String certificateVersion;

    /** WVTA证书编号 */
    private String wvtaNo;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date manufactureDate;

    private Date manufactureBeginTime;

    private Date manufactureEndTime;

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
    private String vehicleModel;

    private String vehicleModelStr;

    /** 工厂代码 */
    private String factoryCode;

    private List<String> factoryCodes;

    /** 工厂名称 */
    private String factoryName;

    /** 整车物料号 */
    private String materialNo;

    /** 校验结果(0=未校验 1=校验通过 2=校验失败) */
    private Integer validationResult;

    private List<Integer> validationResults;

    /** 状态(0=正常 1=停用) */
    private Integer status;

    /** 双色的次色 */
    private String secondaryColor;

    private List<String> secondaryColors;

    private String vehicleTemplateId;

    private String vehicleTemplateFilepath;

    private String cocTemplateNo;

    private String validationReportJson;

    private String engineNumber;

    private String batteryNumber;

    private String motorNumber;

    private String tvv;

    private String brand;

    private String weight;

    private String saleName;

    private String saleCompanyName;

    private String tire;

    /** 生成确认 */
    private Integer generateAffirm;

    /** 上传确认 */
    private Integer uploadAffirm;

    /** 项目名称 */
    private String projectName;

    /** 顾客编号 */
    private String customerNo;

    /** 轮胎滚阻 */
    private String tireResistanceGrade;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date breakpointTime;

    /** VIN批量查询列表 */
    private List<String> vinList;

    /** 物料号批量查询列表 */
    private List<String> materialNoList;

    /** 车型代码批量查询列表 */
    private List<String> vehicleModelList;

    private String version;

    private String tempVersion;

    private Map<String, Map<String, Object>> otherSystem;

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

    // 在 VehicleInfo.java 中新增以下字段（追加到类末尾）

    /** 整车物料号首台待确认标识 0-否 1-是 */
    private Integer firstMaterialFlag;

    /** 车辆模版首台待确认标识 0-否 1-是 */
    private Integer firstTemplateFlag;

    /** 物料号首台确认人 */
    private String materialConfirmedBy;

    /** 物料号首台确认时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date materialConfirmedTime;

    /** 模版首台确认人 */
    private String templateConfirmedBy;

    /** 模版首台确认时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date templateConfirmedTime;

    /**
     * 生成确认通知是否已经发送: 0-未发送 1-已发送
     */
    private Integer generateAffirmNotice;

    /**
     * 上传确认通知是否已经发送: 0-未发送 1-已发送
     */
    private Integer uploadAffirmNotice;

    private String modelNo;

    private String affirmCause;

    private Integer affirm;

    /** 该车辆是否已生成过XML（非数据库字段，关联 xml_file 表回填，用于首台车页面确认生成按钮显示） */
    private transient Boolean hasGeneratedXml;

    /** 最新XML文件ID（关联 xml_file.id，is_latest=1，用于首台车页面调上传/强制上传接口） */
    private transient Long xmlFileId;

    /** 最新XML文件的校验结果（关联 xml_file.validate_result，用于首台车页面上传按钮禁用判断） */
    private transient Integer xmlValidateResult;

    /** 最新XML是否曾强制上传（关联 xml_file.force_uploaded，用于首台车页面强制上传按钮显示判断） */
    private transient Boolean forceUploaded;
}
