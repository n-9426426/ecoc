package com.ruoyi.vehicle.enums;


import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 心跳状态枚举
 */
@Getter
@AllArgsConstructor
public enum HeartbeatStatusEnum {

    NORMAL(0, "正常", "#52c41a"),
    EXCEPTION(1, "异常", "#ff4d4f"),
    RECONNECTING(2, "重连中", "#faad14");

    private final Integer code;
    private final String desc;
    private final String color;

    public static HeartbeatStatusEnum of(Integer code) {
        for (HeartbeatStatusEnum e : values()) {
            if (e.code.equals(code)) return e;
        }
        return EXCEPTION;
    }
}
