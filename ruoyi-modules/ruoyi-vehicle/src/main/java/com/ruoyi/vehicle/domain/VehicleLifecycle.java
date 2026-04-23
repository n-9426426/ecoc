package com.ruoyi.vehicle.domain;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.util.Date;

@Data
public class VehicleLifecycle {

    private Long id;

    private String vin;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date time;

    private String operate;

    private Integer result;
}
