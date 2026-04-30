package com.ruoyi.vehicle.domain.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@Data
public class VehicleDto implements Serializable {
    private static final long serialVersionUID = 1L;

    private String vin;

    private String vehicleModel;

    private String factoryCode;

    private String materialNo;

    private String color;

    private String secondaryColor;

    private String country;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date issueDate;

    private String engineNumber;

    private String motorNumber;

    private String brand;

    private String weight;

    private String saleName;

    private String tire;

    private String username;

    private String password;
}
