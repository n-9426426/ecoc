package com.ruoyi.vehicle.domain.vo;

import lombok.Data;

@Data
public class ChartDataXmlTotalVo {
    private Integer year;

    private Integer month;

    private Integer total;

    private Integer submitNumber;

    private Integer failNumber;

    private Integer passNumber;
}
