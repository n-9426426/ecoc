package com.ruoyi.vehicle.domain.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.io.Serializable;
import java.util.Date;

@Data
public class VehicleDto implements Serializable {

    private static final long serialVersionUID = 1L;

    // VIN
    @NotBlank
    private String vin;

    // 整车物料号
    @NotBlank
    private String materialNo;

    // TVV
    private String tvv;

    // 车型
    @NotBlank
    private String vehicleModel;

    // 工厂代码
    @NotBlank
    private String factoryCode;

    // 颜色代码
    @NotBlank
    private String color;

    // 出口国家
    @NotBlank
    private String country;

    // 制造日期
    @NotNull
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
    @NotBlank
    private String weight;

    // 销售名称
    @NotBlank
    private String saleName;

    // 品牌
    @NotBlank
    private String brand;

    // 轮胎
    @NotBlank
    private String tire;
}
