package com.ruoyi.vehicle.domain;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.ruoyi.common.core.web.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Date;
import java.util.List;

/**
 * 整车物料对象 material
 *
 * @author ruoyi
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class Material extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 主键ID */
    private Long id;

    /** 项目名称 */
    private String name;

    /** 整车物料号 */
    private String materialNo;

    /** TVV */
    private String tvv;

    /** 销售区域 */
    private String country;

    private List<String> countries;

    /** 轮胎 */
    private String tire;

    /** 重量 */
    private String weight;

    /** 品牌 */
    private String brand;

    /** 销售名称 */
    private String saleName;

    /** 创建时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;

    /** 创建人 */
    private String createBy;

    /** 更新时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date updateTime;

    /** 更新人 */
    private String updateBy;

    /** 备注 */
    private String remark;

    private String switchRemark;

    /** 车辆信息模版ID */
    private Long vehicleTemplateId;

    private List<Long> vehicleTemplateIds;

    private String tireResistanceGrade;

    private String vehicleModel;

    private List<String> vehicleModels;

    private Integer status;

    /** 生成确认 */
    private Integer generateAffirm;

    /** 上传确认 */
    private Integer uploadAffirm;

    private Integer affirm;

    // -------------------- 关联/查询扩展字段 --------------------

    private String vehicleType;

    private String factoryCode;

    private List<String> factoryCodes;

    private String cocTemplateNo;

    private String wvtaCocNo;

    /** 模版版本号 */
    private String version;

    /** 新版本号（用于版本升级） */
    private String newVersion;

    /** 是否最新版本（1=是，0=否） */
    private Integer isLast;

    /** 最新版本号（子查询） */
    private String lastVersion;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date effectiveDate;

    /** 匹配的车辆模版列表 */
    private List<VehicleTemplate> vehicleTemplates;

    /** 物料变更历史列表 */
    private List<MaterialHistory> materialHistories;
}