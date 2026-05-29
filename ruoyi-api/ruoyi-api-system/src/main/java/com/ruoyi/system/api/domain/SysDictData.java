package com.ruoyi.system.api.domain;

import com.ruoyi.common.core.constant.UserConstants;
import com.ruoyi.common.core.web.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

/**
 * 字典数据表 sys_dict_data
 * 
 * @author ruoyi
 */
@Data
@ToString
@EqualsAndHashCode(callSuper = true)
public class SysDictData extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 字典编码 */
    private Long dictCode;

    /** 字典排序 */
    private Long dictSort;

    /** 字典标签 */
    private String dictLabel;

    /** 字典键值 */
    private String dictValue;

    /** 字典类型 */
    private String dictType;

    private String dictTypeStr;

    /** 样式属性（其他样式扩展） */
    private String cssClass;

    /** 表格字典样式 */
    private String rule;

    private String rangeRule;

    /** 是否默认（Y是 N否） */
    private String isDefault;

    /** 状态（0正常 1停用） */
    private String status;

    private String keyMap;

    private String valueMap;

    private Long dictTypeAffiliation;

    private String dictTypeAffiliationStr;

    private String ruleType;

    private Boolean isVehicle = true;

    private String keyMapJson;

    private String originalSystem;

    private String uuid;

    private String otherLabel;

    private String otherLabelSystem;

    private String cocOrder;

    private String tableName;

    private String excelColumnNameEnUs;

    private String excelColumnNameZhCn;

    private Long excelColumnSort;

    @NotBlank(message = "字典标签不能为空")
    @Size(min = 0, max = 100, message = "字典标签长度不能超过100个字符")
    public String getDictLabel()
    {
        return dictLabel;
    }

    @NotBlank(message = "字典键值不能为空")
    @Size(min = 0, max = 100, message = "字典键值长度不能超过100个字符")
    public String getDictValue()
    {
        return dictValue;
    }

    @NotBlank(message = "字典类型不能为空")
    @Size(min = 0, max = 100, message = "字典类型长度不能超过100个字符")
    public String getDictType()
    {
        return dictType;
    }

    @Size(min = 0, max = 100, message = "样式属性长度不能超过100个字符")
    public String getCssClass()
    {
        return cssClass;
    }

    public boolean getDefault()
    {
        return UserConstants.YES.equals(this.isDefault);
    }

    public Boolean getVehicle() {
        return isVehicle;
    }
}
