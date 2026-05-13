package com.ruoyi.vehicle.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 心跳监测日志实体
 * 对应表：sys_heartbeat_log（保留90天）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SysHeartbeatLog {

    private Long id;

    /** 关联账号ID */
    private Long accountId;

    /** 国家代码 */
    private String countryCode;

    /** 检测接口地址 */
    private String apiUrl;

    /** 心跳检测时间 */
    private LocalDateTime checkTime;

    /** 检测结果：0-正常 1-异常 */
    private Integer checkResult;

    /** HTTP响应状态码 */
    private Integer responseCode;

    /**
     * 异常类型
     *
     */
    private String exceptionType;

    /** 异常信息 */
    private String exceptionMsg;

    /** 是否重连记录：0-否 1-是 */
    private Integer isReconnect;

    /** 本次重连次数 */
    private Integer reconnectCount;

    /** 重连结果：0-成功 1-失败 */
    private Integer reconnectResult;

    private LocalDateTime createTime;
}
