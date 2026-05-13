package com.ruoyi.system.domain;

import lombok.Data;

import java.io.Serializable;

@Data
public class SysPostAuth implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long postId;

    private String factoryCode;

    private String country;

    private String vehicleModel;
}
