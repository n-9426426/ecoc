package com.ruoyi.system.api.enums;

import lombok.Getter;

@Getter
public enum SysNoticeModel {
    VEHICLE_TEMPLATE("VEHICLE_TEMPLATE"),
    VEHICLE_INFO("VEHICLE_INFO"),
    XML_FILE("XML_FILE"),
    ;

    private final String model;

    SysNoticeModel(String model) {
        this.model = model;
    }
}
