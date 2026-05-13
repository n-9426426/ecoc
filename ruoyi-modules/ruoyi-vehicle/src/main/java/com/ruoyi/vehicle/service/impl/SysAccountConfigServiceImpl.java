package com.ruoyi.vehicle.service.impl;

import com.ruoyi.vehicle.domain.SysAccountConfig;
import com.ruoyi.vehicle.domain.SysAccountConfigQuery;
import com.ruoyi.vehicle.domain.dto.AccountConfigSaveDTO;
import com.ruoyi.vehicle.mapper.SysAccountConfigMapper;
import com.ruoyi.vehicle.service.SysAccountConfigService;
import com.ruoyi.vehicle.utils.HeartbeatStatusEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import java.util.List;

/**
 * 账号管理 Service 实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SysAccountConfigServiceImpl implements SysAccountConfigService {

    private final SysAccountConfigMapper accountConfigMapper;

    @Override
    public List<SysAccountConfig> selectList(SysAccountConfigQuery query) {
        return accountConfigMapper.selectList(query);
    }

    @Override
    public SysAccountConfig selectById(Long id) {
        SysAccountConfig entity = accountConfigMapper.selectById(id);
        Assert.notNull(entity, "账号不存在");
        // 返回前密码脱敏（详情展示用）
        maskPassword(entity);
        return entity;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int insert(AccountConfigSaveDTO dto) {
        SysAccountConfig entity = convertToEntity(dto);
        entity.setHeartbeatStatus(HeartbeatStatusEnum.NORMAL.getCode());
        entity.setApiAvailable(1);
        entity.setDeleted(0);
        // TODO 替换为项目统一的 SecurityUtils.getUsername()
        entity.setCreateBy("system");
        entity.setUpdateBy("system");
        int rows = accountConfigMapper.insert(entity);
        log.info("[账号管理] 新增账号: country={}, account={}", dto.getCountryCode(), dto.getAccount());
        return rows;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int update(AccountConfigSaveDTO dto) {
        Assert.notNull(dto.getId(), "编辑时ID不能为空");
        SysAccountConfig exist = accountConfigMapper.selectById(dto.getId());
        Assert.notNull(exist, "账号不存在");
        SysAccountConfig entity = convertToEntity(dto);
        entity.setId(dto.getId());
        // TODO 替换为项目统一的 SecurityUtils.getUsername()
        entity.setUpdateBy("system");
        int rows = accountConfigMapper.updateById(entity);
        log.info("[账号管理] 编辑账号: id={}, country={}", dto.getId(), dto.getCountryCode());
        return rows;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteById(Long id) {
        SysAccountConfig exist = accountConfigMapper.selectById(id);
        Assert.notNull(exist, "账号不存在");
        int rows = accountConfigMapper.deleteById(id);
        log.info("[账号管理] 删除账号: id={}, country={}", id, exist.getCountryCode());
        return rows;
    }

    @Override
    public SysAccountConfig selectWithPasswordById(Long id) {
        // 直接返回原始数据（含密码明文），仅供心跳内部调用
        return accountConfigMapper.selectById(id);
    }

    // ----------------------------------------------------------------
    //  私有工具
    // ----------------------------------------------------------------

    private SysAccountConfig convertToEntity(AccountConfigSaveDTO dto) {
        SysAccountConfig entity = new SysAccountConfig();
        entity.setCountryCode(dto.getCountryCode());
        entity.setCountryName(dto.getCountryName());
        entity.setAccount(dto.getAccount());
        // TODO 替换为项目统一的加密方案（如 AES）
        entity.setPassword(dto.getPassword());
        entity.setApiUrl(dto.getApiUrl());
        entity.setBackupApiUrl(dto.getBackupApiUrl());
        entity.setEnableStatus(dto.getEnableStatus());
        entity.setRemark(dto.getRemark());
        return entity;
    }

    private void maskPassword(SysAccountConfig entity) {
        String pwd = entity.getPassword();
        if (pwd != null && pwd.length() > 4) {
            entity.setPassword(pwd.substring(0, 2) + "****" + pwd.substring(pwd.length() - 2));
        } else {
            entity.setPassword("****");
        }
    }
}
