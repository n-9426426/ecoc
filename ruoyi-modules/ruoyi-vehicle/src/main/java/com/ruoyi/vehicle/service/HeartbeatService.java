package com.ruoyi.vehicle.service;


import com.ruoyi.vehicle.domain.SysAccountConfig;
import com.ruoyi.vehicle.domain.SysHeartbeatLog;
import com.ruoyi.vehicle.domain.SysHeartbeatLogQuery;

import java.util.List;

/**
 * 心跳监测 Service
 */
public interface HeartbeatService {

    /**
     * 对单个账号执行一次心跳检测
     */
    void doHeartbeat(SysAccountConfig account);

    /**
     * 触发单个账号重连流程
     */
    void doReconnect(SysAccountConfig account);


    /**
     * 日志列表查询
     */
    List<SysHeartbeatLog> selectLogList(SysHeartbeatLogQuery query);


    /**
     * 清理90天前日志（定时任务调用）
     */
    void cleanExpiredLogs();
}
