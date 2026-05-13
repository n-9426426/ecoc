package com.ruoyi.vehicle.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 心跳监测配置实体
 * 对应表：sys_heartbeat_config（支持按国家自定义频率）
 */
@Data
public class SysHeartbeatConfig {

    private Long id;

    /** 国家代码，NULL 表示全局默认配置 */
    private String countryCode;

    /** 正常心跳间隔（秒），默认30 */
    private Integer normalInterval;

    /** 异常心跳间隔（秒），默认10 */
    private Integer exceptionInterval;

    /** 超时判定秒数，默认5 */
    private Integer timeoutSeconds;

    /** 单次最大重连次数，默认3 */
    private Integer maxReconnectTimes;

    /** 重连间隔（秒），默认5 */
    private Integer reconnectInterval;

    /** 重连失败后暂停时间（秒），默认60 */
    private Integer reconnectPause;

    /** 异常持续X分钟后通知运维，默认10 */
    private Integer notifyDuration;

    /** 重连超过X次后通知运维，默认10 */
    private Integer notifyReconnectTimes;

    /** 持续重连失败X分钟后禁用接口，默认30 */
    private Integer disableDuration;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
