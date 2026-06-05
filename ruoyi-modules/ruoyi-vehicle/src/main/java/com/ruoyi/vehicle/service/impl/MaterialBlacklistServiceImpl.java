package com.ruoyi.vehicle.service.impl;

import com.ruoyi.common.core.utils.DateUtils;
import com.ruoyi.common.security.utils.SecurityUtils;
import com.ruoyi.vehicle.domain.Material;
import com.ruoyi.vehicle.domain.MaterialBlacklist;
import com.ruoyi.vehicle.mapper.MaterialBlacklistMapper;
import com.ruoyi.vehicle.service.IMaterialBlacklistService;
import com.ruoyi.vehicle.service.IMaterialService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * 物料黑名单 Service 实现
 */
@Service
@RequiredArgsConstructor
public class MaterialBlacklistServiceImpl implements IMaterialBlacklistService {

    private final MaterialBlacklistMapper materialBlacklistMapper;

    @Autowired
    private IMaterialService materialService;

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
        MaterialBlacklist exist = selectMaterialBlacklistById(materialBlacklist.getId());
        if (exist != null) {
            throw new RuntimeException("该物料号已存在");
        }
        materialBlacklist.setCreateBy(SecurityUtils.getUsername());
        materialBlacklist.setCreateTime(DateUtils.getNowDate());
        return materialBlacklistMapper.insertMaterialBlacklist(materialBlacklist);
    }

    @Override
    public int updateMaterialBlacklist(MaterialBlacklist materialBlacklist) {
        MaterialBlacklist exist = selectMaterialBlacklistById(materialBlacklist.getId());
        if (exist == null) {
            throw new RuntimeException("该记录不存在");
        }
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

    @Override
    public MaterialBlacklist updateMaterialBlacklistStatus(Long id) {
        MaterialBlacklist exist = selectMaterialBlacklistById(id);
        if (exist == null) {
            throw new RuntimeException("该记录不存在");
        }
        int status = exist.getStatus() == 0 ? 1 : 0;
        MaterialBlacklist update = new MaterialBlacklist();
        update.setId(id);
        update.setStatus(status);
        update.setUpdateBy(SecurityUtils.getUsername());
        materialBlacklistMapper.updateMaterialBlacklistStatus(update);
        return update;
    }

    @Override
    public Map<String, Object> removeToMaterial(Long[] ids) {
        int count = ids.length;
        if (count == 0) {
            throw new RuntimeException("选择的数据为空");
        }
        int fail = 0;
        int success = 0;
        List<String> materialNos = new LinkedList<>();
        for (Long id : ids) {
            MaterialBlacklist materialBlacklist = materialBlacklistMapper.selectMaterialBlacklistById(id);
            if (materialBlacklist == null) {
                fail++;
                continue;
            }
            Material material = new Material();
            material.setMaterialNo(materialBlacklist.getMaterialNo());
            material.setBrand(materialBlacklist.getBrand());
            int row = materialService.insertMaterial(material);
            if (row == 0) {
                fail++;
                materialNos.add(material.getMaterialNo());
            } else {
                success++;
            }
        }
        Map<String, Object> result = new HashMap<>();
        result.put("count", count);
        result.put("fail", fail);
        result.put("success", success);
        result.put("materialNos", materialNos);
        return result;
    }
}