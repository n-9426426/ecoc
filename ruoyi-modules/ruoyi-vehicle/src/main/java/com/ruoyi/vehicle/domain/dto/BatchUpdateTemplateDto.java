package com.ruoyi.vehicle.domain.dto;

import lombok.Data;

import java.util.List;

@Data
public class BatchUpdateTemplateDto {
    /** 要修改的车辆ID列表 */
    private List<Long> vehicleIds;
}
