package com.ruoyi.vehicle.service;




import com.ruoyi.vehicle.domain.SysAccountConfig;
import com.ruoyi.vehicle.domain.SysAccountConfigQuery;
import com.ruoyi.vehicle.domain.dto.AccountConfigSaveDTO;

import java.util.List;

/**
 * 账号管理 Service
 */
public interface SysAccountConfigService {

    /**
     * 列表查询（调用前用 startPage() 注入分页，返回 List 交给 getDataTable()）
     */
    List<SysAccountConfig> selectList(SysAccountConfigQuery query);

    /**
     * 按主键查询详情
     */
    SysAccountConfig selectById(Long id);

    /**
     * 新增
     */
    int insert(AccountConfigSaveDTO dto);

    /**
     * 编辑
     */
    int update(AccountConfigSaveDTO dto);

    /**
     * 删除（逻辑删除）
     */
    int deleteById(Long id);

    /**
     * 获取含明文密码的账号（仅内部心跳服务调用）
     */
    SysAccountConfig selectWithPasswordById(Long id);
}
