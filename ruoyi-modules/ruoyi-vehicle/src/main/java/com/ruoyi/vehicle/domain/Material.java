package com.ruoyi.vehicle.domain;

import com.ruoyi.common.core.web.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

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

    /** 轮胎 */
    private String trie;

    /** 重量 */
    private String weight;

    /** 品牌 */
    private String brand;

    /** 销售名称 */
    private String saleName;

    private String version;

    private Integer isLast;

    private List<VehicleTemplate> vehicleTemplates;

    private Long vehicleTemplateId;

    private String lastVersion;
}
