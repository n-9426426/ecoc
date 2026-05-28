package com.ruoyi.vehicle.enums;

import lombok.Getter;

@Getter
public enum VehicleLifecycleOperation {
    VEHICLE_INFO_CREATE("0"),
    FIRST_VEHICLE_AFFIRM("1"),
    VEHICLE_INFO_VALIDATE("2"),
    VEHICLE_BUILD_XML("3"),
    XML_VALIDATE("4"),
    XML_UPLOAD("5");

    private final String operation;

    VehicleLifecycleOperation(String operation) {
        this.operation = operation;
    }

}
