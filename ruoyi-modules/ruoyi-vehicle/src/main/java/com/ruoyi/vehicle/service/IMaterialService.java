package com.ruoyi.vehicle.service;

import com.ruoyi.vehicle.domain.Material;

import java.util.List;

/**
 * 整车物料 Service 接口
 *
 * @author ruoyi
 */
public interface IMaterialService {

    /**
     * 查询整车物料
     *
     * @param id 主键
     * @return 整车物料
     */
    Material selectMaterialById(Long id);

    /**
     * 查询整车物料列表
     *
     * @param material 整车物料
     * @return 整车物料集合
     */
    List<Material> selectMaterialList(Material material);

    /**
     * 新增整车物料
     *
     * @param material 整车物料
     * @return 结果
     */
    int insertMaterial(Material material);

    /**
     * 修改整车物料
     *
     * @param material 整车物料
     * @return 结果
     */
    int updateMaterial(Material material);

    /**
     * 批量删除整车物料
     *
     * @param ids 需要删除的主键集合
     * @return 结果
     */
    int deleteMaterialByIds(Long[] ids);
}
