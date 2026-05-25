package com.ruoyi.vehicle.domain;

import com.ruoyi.common.core.web.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 物料黑名单 material_blacklist
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class MaterialBlacklist extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 主键 */
    private Long id;

    /** 物料号 */
    private String materialNo;

    /** 品牌 */
    private String brand;

    /** 状态(0=正常 1=停用) */
    private Integer status;
}
