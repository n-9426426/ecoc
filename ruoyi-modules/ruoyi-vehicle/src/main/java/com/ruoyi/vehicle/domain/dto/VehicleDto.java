package com.ruoyi.vehicle.domain.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@Data
public class VehicleDto implements Serializable {
    private static final long serialVersionUID = 1L;

    // VIN
    private String vin;

    // 整车物料号
    private String materialNo;

    // TVV
    private String tvv;

    // 车型
    private String vehicleModel;

    // 工厂代码
    private String factoryCode;

    // 颜色代码
    private String color;

    // 出口国家
    private String country;

    // 制造日期
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date manufactureDate;

    // 发证日期
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date issueDate;

    // 发动机号
    private String engineNumber;

    // 电池号
    private String batteryNumber;

    // 电机号
    private String motorNumber;

    // 重量
    private String weight;

    // 销售名称
    private String saleName;

    // 品牌
    private String brand;

    // 轮胎
    private String tire;

    private String username;

    private String password;
}
