package com.ruoyi.vehicle.domain;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.ruoyi.common.core.web.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * COC模板主表实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class VehicleTemplate extends BaseEntity {

    /** 主键ID */
    private Long templateId;

    private List<Long> templateIds;

    /** WVTA-COC编号 */
    private String wvtaCocNo;

    /** COC模板号 */
    private String cocTemplateNo;

    /** 车型号 */
    private String modelNo;

    /** 版本号 */
    private String version;

    /** 车辆类型 */
    private String vehicleType;

    /** 状态（0=启用 1=停用） */
    private String status;

    /** 校验结果（0=未校验 1=通过 2=不通过） */
    private String validateResult;

    /** 最近校验时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date validateTime;

    /** 校验结果详情(JSON) */
    private String validateMsg;

    /** COC模板字段内容(JSON) */
    private String json;

    /** 删除标志 */
    private String delFlag;

    /** 关联的物料号列表（非DB字段）*/
    private List<VehicleTemplateMaterial> materialList;

    private String filepath;

    private Integer isLast;

    private String uuid;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date overdueDate;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date overdueDateStart;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date overdueDateEnd;

    private String templateVersion;

    private String tvv;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date effectiveDate;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date effectiveDateStart;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date effectiveDateEnd;

    private String type;

    private String variant;

    private String versionNo;

    private String tire;

    private String brand;

    private String weight;

    private String saleName;

    /** 生成确认 */
    private Integer generateAffirm;

    private String generateAffirmRaw;

    /** 上传确认 */
    private Integer uploadAffirm;

    private String uploadAffirmRaw;

    private Map<String, Map<String, Object>> otherSystem;
}