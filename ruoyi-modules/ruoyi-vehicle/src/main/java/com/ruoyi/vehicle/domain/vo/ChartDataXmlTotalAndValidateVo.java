package com.ruoyi.vehicle.domain.vo;

import lombok.Data;

@Data
public class ChartDataXmlTotalAndValidateVo {
    private Integer year;

    private Integer month;

    private Integer day;

    private Integer xmlTotal;

    private Integer submitTotalNumber;

    private Integer failTotalNumber;

    private Integer passTotalNumber;

    private Integer validateTotal;

    private Integer submitValidateNumber;

    private Integer failValidateNumber;

    private Integer passValidateNumber;
}
