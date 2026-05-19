package com.ruoyi.vehicle.service.impl;

import com.ruoyi.common.security.utils.SecurityUtils;
import com.ruoyi.system.api.model.LoginUser;
import com.ruoyi.vehicle.domain.Material;
import com.ruoyi.vehicle.mapper.MaterialMapper;
import com.ruoyi.vehicle.mapper.VehicleTemplateMapper;
import com.ruoyi.vehicle.service.IMaterialService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

/**
 * 整车物料 Service 业务层实现
 *
 * @author ruoyi
 */
@Service
public class MaterialServiceImpl implements IMaterialService {

    @Autowired
    private MaterialMapper materialMapper;

    @Autowired
    private VehicleTemplateMapper vehicleTemplateMapper;

    /**
     * 查询整车物料
     */
    @Override
    public Material selectMaterialById(Long id) {
        Material material = materialMapper.selectMaterialById(id);
        material.setVehicleTemplates(vehicleTemplateMapper.selectVehicleTemplateIdByCondition(material.getMaterialNo(), null, null, null, null));
        return material;
    }

    /**
     * 查询整车物料列表
     */
    @Override
    public List<Material> selectMaterialList(Material material) {
        return materialMapper.selectMaterialList(material);
    }

    /**
     * 新增整车物料
     */
    @Override
    public int insertMaterial(Material material) {
        LoginUser loginUser = SecurityUtils.getLoginUser();
        material.setCreateBy(loginUser.getUsername());
        material.setCreateTime(new Date());
        return materialMapper.insertMaterial(material);
    }

    /**
     * 修改整车物料
     */
    @Override
    public int updateMaterial(Material material) {
        LoginUser loginUser = SecurityUtils.getLoginUser();
        material.setUpdateBy(loginUser.getUsername());
        material.setUpdateTime(new Date());
        int row = materialMapper.updateMaterial(material);

        return row;
    }

    /**
     * 批量删除整车物料
     */
    @Override
    public int deleteMaterialByIds(Long[] ids) {
        return materialMapper.deleteMaterialByIds(ids);
    }
}
