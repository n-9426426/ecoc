package com.ruoyi.vehicle.service;

import com.ruoyi.vehicle.domain.MaterialBlacklist;

import java.util.List;

/**
 * 物料黑名单 Service 接口
 */
public interface IMaterialBlacklistService {

    List<MaterialBlacklist> selectMaterialBlacklistList(MaterialBlacklist materialBlacklist);

    MaterialBlacklist selectMaterialBlacklistById(Long id);

    int insertMaterialBlacklist(MaterialBlacklist materialBlacklist);

    int updateMaterialBlacklist(MaterialBlacklist materialBlacklist);

    int deleteMaterialBlacklistByIds(Long[] ids);
}
