package com.ruoyi.vehicle.domain;

import com.ruoyi.common.core.web.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * COC模板-整车物料号关联表实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class VehicleTemplateMaterial extends BaseEntity {

    /** 主键ID */
    private Long id;

    /** COC模板ID */
    private Long templateId;

    /** 整车物料号 */
    private String materialNo;
}