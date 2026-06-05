package com.ruoyi.vehicle.service;

import com.ruoyi.vehicle.domain.MaterialBlacklist;

import java.util.List;
import java.util.Map;

/**
 * 物料黑名单 Service 接口
 */
public interface IMaterialBlacklistService {

    List<MaterialBlacklist> selectMaterialBlacklistList(MaterialBlacklist materialBlacklist);

    MaterialBlacklist selectMaterialBlacklistById(Long id);

    int insertMaterialBlacklist(MaterialBlacklist materialBlacklist);

    int updateMaterialBlacklist(MaterialBlacklist materialBlacklist);

    int deleteMaterialBlacklistByIds(Long[] ids);

    /**
     * 根据物料号查询黑名单记录
     */
    MaterialBlacklist selectMaterialBlacklistByMaterialNo(String materialNo);

    MaterialBlacklist updateMaterialBlacklistStatus(Long id);

    Map<String, Object> removeToMaterial(Long[] ids);
}
