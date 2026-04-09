package com.ruoyi.system.api.enums;

import com.ruoyi.common.core.utils.DateUtils;

import java.time.temporal.ChronoUnit;

public enum JobType {

    VEHICLE_CLEAN_EXPIRED(
            "VEHICLE_CLEAN_EXPIRED",
            "vehicleInfoService.permanentlyDeleteVehicleInfoByIds",
            DateUtils.getCronAfterTime(30, ChronoUnit.DAYS)
    ),
    XML_CLEAN_EXPIRED(
            "XML_CLEAN_EXPIRED",
            "xmlFileService.permanentlyDeleteXmlByIds",
            DateUtils.getCronAfterTime(30, ChronoUnit.DAYS)
    )
    ;

    private final String type;
    private final String invoke;
    private final String cron;

    JobType(String type, String invoke, String cron) {
        this.type = type;
        this.invoke = invoke;
        this.cron = cron;
    }


    public String getType() {
        return type;
    }

    public String getInvoke() {
        return invoke;
    }

    public String getCron() {
        return cron;
    }

    public static JobType fromType(String type) {
        for (JobType jobType : JobType.values()) {
            if (jobType.type.equals(type)) {
                return jobType;
            }
        }
        return null; // 或抛出异常
    }
}
