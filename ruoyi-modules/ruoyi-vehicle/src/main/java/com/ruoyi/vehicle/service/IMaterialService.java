package com.ruoyi.vehicle.service;

import com.ruoyi.vehicle.domain.Material;
import org.springframework.web.multipart.MultipartFile;

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

    /**
     * 导入物料号数据
     *
     * @param file          上传的 Excel 文件
     * @param updateSupport 是否允许覆盖已有数据（根据 material_no 唯一键判断）
     * @return 导入结果描述（成功/失败/跳过条数）
     * @throws Exception 文件解析异常
     */
    String importMaterial(MultipartFile file, boolean updateSupport) throws Exception;
}
