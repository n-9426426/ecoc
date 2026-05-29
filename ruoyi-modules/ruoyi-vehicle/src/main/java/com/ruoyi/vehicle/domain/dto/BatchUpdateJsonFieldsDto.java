package com.ruoyi.vehicle.domain.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class BatchUpdateJsonFieldsDto {
    /** 需要修改的车辆ID列表 */
    private List<Long> vehicleIds;

    /**
     * 需要替换的字段映射，key = JSON 中的键名，value = 新值
     * 例如：{"engineType": "电动", "color": "白色"}
     */
    private Map<String, String> fieldValues;
}
