package com.ruoyi.vehicle.domain;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 账号管理查询条件
 *
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class SysAccountConfigQuery extends SysAccountConfig {

    /** 开始时间（createTime范围查询） */
    private String beginTime;

    /** 结束时间 */
    private String endTime;
}

