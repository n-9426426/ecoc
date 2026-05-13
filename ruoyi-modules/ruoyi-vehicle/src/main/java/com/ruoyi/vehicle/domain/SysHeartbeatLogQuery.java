package com.ruoyi.vehicle.domain;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 心跳日志查询条件
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class SysHeartbeatLogQuery extends SysHeartbeatLog {

    private String beginTime;
    private String endTime;


}

