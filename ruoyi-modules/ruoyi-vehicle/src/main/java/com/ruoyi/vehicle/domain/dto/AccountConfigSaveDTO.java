package com.ruoyi.vehicle.domain.dto;


import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/**
 * 账号新增 / 编辑 请求DTO
 */
@Data
public class AccountConfigSaveDTO {

    /** 编辑时必传 */
    private Long id;

    @NotBlank(message = "国家代码不能为空")
    private String countryCode;

    @NotBlank(message = "国家名称不能为空")
    private String countryName;

    @NotBlank(message = "账号不能为空")
    private String account;

    @NotBlank(message = "密码不能为空")
    private String password;

    @NotBlank(message = "主接口地址不能为空")
    private String apiUrl;

    private String backupApiUrl;

    @NotNull(message = "启用状态不能为空")
    private Integer enableStatus;

    private String remark;
}
