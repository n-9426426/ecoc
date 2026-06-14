package com.ruoyi.vehicle.mapper;

import com.ruoyi.vehicle.domain.Material;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 整车物料 Mapper 接口
 *
 * @author ruoyi
 */
public interface MaterialMapper {

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
     * @param ids 需要删除的数据主键集合
     * @return 结果
     */
    int deleteMaterialByIds(Long[] ids);

    Material selectByMaterialNo(String materialNo);

    void updateMaterialStatus(Material update);

    void resetAffirmByTemplateUuid(@Param("uuid") String uuid);

    void updateGenerateAffirmByMaterialNo(@Param("materialNo") String materialNo, @Param("generateAffirm") int generateAffirm);

    void updateUploadAffirmByMaterialNo(@Param("materialNo") String materialNo, @Param("uploadAffirm") int uploadAffirm);

    void deleteByMaterialNo(@Param("materialNo") String materialNo);

    void updateVehicleTemplateIdByUuid(@Param("uuid") String uuid,
                                       @Param("templateId") Long templateId);
}
