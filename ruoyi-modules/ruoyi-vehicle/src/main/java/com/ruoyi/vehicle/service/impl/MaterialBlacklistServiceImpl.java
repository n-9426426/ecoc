package com.ruoyi.vehicle.service.impl;

import com.ruoyi.common.core.utils.DateUtils;
import com.ruoyi.common.security.utils.SecurityUtils;
import com.ruoyi.vehicle.domain.MaterialBlacklist;
import com.ruoyi.vehicle.mapper.MaterialBlacklistMapper;
import com.ruoyi.vehicle.service.IMaterialBlacklistService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 物料黑名单 Service 实现
 */
@Service
@RequiredArgsConstructor
public class MaterialBlacklistServiceImpl implements IMaterialBlacklistService {

    private final MaterialBlacklistMapper materialBlacklistMapper;

    @Override
    public List<MaterialBlacklist> selectMaterialBlacklistList(MaterialBlacklist materialBlacklist) {
        return materialBlacklistMapper.selectMaterialBlacklistList(materialBlacklist);
    }

    @Override
    public MaterialBlacklist selectMaterialBlacklistById(Long id) {
        return materialBlacklistMapper.selectMaterialBlacklistById(id);
    }

    @Override
    public int insertMaterialBlacklist(MaterialBlacklist materialBlacklist) {
        materialBlacklist.setCreateBy(SecurityUtils.getUsername());
        materialBlacklist.setCreateTime(DateUtils.getNowDate());
        return materialBlacklistMapper.insertMaterialBlacklist(materialBlacklist);
    }

    @Override
    public int updateMaterialBlacklist(MaterialBlacklist materialBlacklist) {
        materialBlacklist.setUpdateBy(SecurityUtils.getUsername());
        materialBlacklist.setUpdateTime(DateUtils.getNowDate());
        return materialBlacklistMapper.updateMaterialBlacklist(materialBlacklist);
    }

    @Override
    public int deleteMaterialBlacklistByIds(Long[] ids) {
        return materialBlacklistMapper.deleteMaterialBlacklistByIds(ids);
    }

    @Override
    public MaterialBlacklist selectMaterialBlacklistByMaterialNo(String materialNo) {
        return materialBlacklistMapper.selectMaterialBlacklistByMaterialNo(materialNo);
    }
}