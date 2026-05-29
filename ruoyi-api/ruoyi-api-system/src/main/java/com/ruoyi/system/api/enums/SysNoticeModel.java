package com.ruoyi.system.api.enums;

import lombok.Getter;

@Getter
public enum SysNoticeModel {
    VEHICLE_TEMPLATE("VEHICLE_TEMPLATE"),
    VEHICLE_INFO("VEHICLE_INFO"),
    XML_FILE("XML_FILE"),
    FIRST_VEHICLE_GENERATE_AFFIRM("FIRST_VEHICLE_GENERATE_AFFIRM"),
    FIRST_VEHICLE_UPLOAD_AFFIRM("FIRST_VEHICLE_UPLOAD_AFFIRM")
    ;

    private final String model;

    SysNoticeModel(String model) {
        this.model = model;
    }
}
