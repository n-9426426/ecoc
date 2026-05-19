package com.ruoyi.vehicle.domain;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.ruoyi.common.core.web.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Date;
import java.util.List;

/**
 * 断点管理对象 breakpoint
 *
 * @author ruoyi
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class Breakpoint extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 主键ID */
    private Long id;

    /** 断点VIN */
    private String vin;

    /** 断点时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date time;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date startTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date endTime;

    /** 实际制造时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date manufactureDate;

    /** 整车物料号数量 */
    private String materialNumber;

    /** 负责人（项目经理） */
    private String principal;

    /** 是否处理（0否 1是） */
    private String isDispose;

    /** 处理人 */
    private String disposeUser;

    /** 处理时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date disposeTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date disposeStartTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date disposeEndTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createStartTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createEndTime;

    private List<VehicleInfo> vehicleInfos;
}
