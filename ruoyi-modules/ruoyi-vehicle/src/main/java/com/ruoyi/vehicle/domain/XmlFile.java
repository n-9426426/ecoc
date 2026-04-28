package com.ruoyi.vehicle.domain;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.ruoyi.common.core.web.domain.BaseEntity;
import lombok.Data;

import java.util.Date;
import java.util.List;

/**
 * XML文件对象 xml_file
 */
@Data
public class XmlFile extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** ID */
    private Long id;

    private List<Long> ids;

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

    /** 车型代码 */
    private String modelCode;

    private String modelName;

    /** 工厂代码 */
    private String factoryCode;

    /** 整车物料号 */
    private String vehicleMaterialNo;

    /** 出口国家 */
    private String country;

    /** 上传结果 */
    private String uploadResult;

    /** 上传日期 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date uploadDate;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date uploadDateBeginTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date uploadDateEndTime;

    /** 发证日期 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date issueDate;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date issueDateBeginTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date issueDateEndTime;

    /** 校验结果（0=未校验 1=通过 2=不通过） */
    private Integer validateResult;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createdStartTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createdEndTime;

    private String validationReportJson;

    /** VIN批量查询列表 */
    private List<String> vinList;

    /** 车型代码批量查询列表 */
    private List<String> modelCodeList;

    /** 出口国家显示文本（导出用） */
    private String countryLabel;

    /** 上传结果显示文本（导出用） */
    private String uploadResultLabel;

    /** 校验结果显示文本（导出用） */
    private String validateResultLabel;

    /** 状态显示文本（导出用） */
    private String statusLabel;
}
