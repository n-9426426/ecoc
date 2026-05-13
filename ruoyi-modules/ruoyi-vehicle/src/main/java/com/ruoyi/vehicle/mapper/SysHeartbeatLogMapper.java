package com.ruoyi.vehicle.mapper;


import com.ruoyi.vehicle.domain.SysHeartbeatLog;
import com.ruoyi.vehicle.domain.SysHeartbeatLogQuery;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 心跳日志 Mapper
 */
@Mapper
public interface SysHeartbeatLogMapper {

    /**
     * 列表查询（配合 PageHelper）
     */
    List<SysHeartbeatLog> selectList(SysHeartbeatLogQuery query);

    /**
     * 新增日志
     */
    int insert(SysHeartbeatLog log);

    /**
     * 查询某账号最近N分钟内的异常记录数
     */
    int countExceptionInMinutes(@Param("accountId") Long accountId,
                                @Param("minutes") int minutes);

    /**
     * 查询某账号当前异常周期内累计重连次数
     */
    int countReconnectTimes(@Param("accountId") Long accountId);

    /**
     * 清理90天前的过期日志
     */
    int deleteExpiredLogs();
}
