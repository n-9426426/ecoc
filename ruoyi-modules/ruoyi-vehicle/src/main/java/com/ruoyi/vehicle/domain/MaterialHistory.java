package com.ruoyi.vehicle.domain;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.ruoyi.common.core.web.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Date;

@Data
@EqualsAndHashCode(callSuper = true)
public class MaterialHistory extends BaseEntity {

    private static final long serialVersionUID = 1L;

    private Long id;

    private Long materialId;

    private String oldVersion;

    private String newVersion;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date changeTime;

    private String operator;

    private String remark;

    private String tire;
    private String weight;
    private String brand;
    private String saleName;
    private Date effectiveDate;
    private Date overdueDate;
}
