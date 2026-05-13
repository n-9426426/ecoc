package com.ruoyi.vehicle.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 账号管理配置实体
 * 对应表：sys_account_config
 */
@Data
public class SysAccountConfig {

    /** 主键ID */
    private Long id;

    /** 国家代码（来源系统字典） */
    private String countryCode;

    /** 国家名称 */
    private String countryName;

    /** 账号 */
    private String account;

    /** 密码（加密存储） */
    private String password;

    /** 主接口地址 */
    private String apiUrl;

    /** 备用接口地址 */
    private String backupApiUrl;

    /**
     * 心跳状态：0-正常 1-异常 2-重连中
     *
     */
    private Integer heartbeatStatus;

    /** 启用状态：0-禁用 1-启用 */
    private Integer enableStatus;

    /**
     * 接口可用标记：0-不可用 1-可用
     * 持续重连失败30分钟后系统自动置0
     */
    private Integer apiAvailable;

    /** 最后一次心跳时间 */
    private LocalDateTime lastHeartbeatTime;

    /** 备注 */
    private String remark;

    private String createBy;
    private LocalDateTime createTime;
    private String updateBy;
    private LocalDateTime updateTime;

    /** 逻辑删除：0-未删除 1-已删除 */
    private Integer deleted;
}
