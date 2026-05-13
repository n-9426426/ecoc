package com.ruoyi.vehicle.utils;



import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 心跳异常类型枚举
 */
@Getter
@AllArgsConstructor
public enum HeartbeatExceptionTypeEnum {

    TIMEOUT("TIMEOUT", "接口响应超时"),
    LOGIN_EXPIRED("LOGIN_EXPIRED", "账号登录失效"),
    HTTP_ERROR("HTTP_ERROR", "接口响应状态异常"),
    NETWORK_ERROR("NETWORK_ERROR", "网络中断"),
    CONFIG_MISMATCH("CONFIG_MISMATCH", "账密或接口地址变更未同步");

    private final String code;
    private final String desc;
}
