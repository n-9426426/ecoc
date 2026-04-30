package com.ruoyi.vehicle.domain.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.util.Date;

@Data
public class ChartDataStatisticsDto {

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date startTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date endTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date issueStartTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date issueEndTime;

    private String factoryCode;

    private Long vehicleModel;

    private String country;

    private Integer uploadResult;

    // 前端传过来的标志，如果是year，则统计精确到年，如果是month,精确到月，如果是day，精确到日
    private String flag;

    private String abnormalType;
}
