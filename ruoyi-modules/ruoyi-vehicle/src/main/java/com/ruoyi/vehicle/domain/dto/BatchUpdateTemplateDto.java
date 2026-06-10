package com.ruoyi.vehicle.domain.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class BatchUpdateTemplateDto {
    /** 要修改的车辆ID列表 */
    private Map<Long, Long> vehicleUpdateTemplateIds;

    private List<Long> vehicleIds;
}
