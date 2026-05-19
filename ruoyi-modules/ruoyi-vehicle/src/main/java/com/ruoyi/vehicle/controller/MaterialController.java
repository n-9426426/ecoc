package com.ruoyi.vehicle.controller;

import com.ruoyi.common.core.web.controller.BaseController;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.core.web.page.TableDataInfo;
import com.ruoyi.common.log.annotation.Log;
import com.ruoyi.common.log.enums.BusinessType;
import com.ruoyi.common.security.annotation.RequiresPermissions;
import com.ruoyi.system.api.RemoteDictService;
import com.ruoyi.vehicle.domain.Material;
import com.ruoyi.vehicle.service.IMaterialService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 整车物料 Controller
 *
 * @author ruoyi
 */
@RestController
@RequestMapping("/material")
public class MaterialController extends BaseController {

    @Autowired
    private IMaterialService materialService;

    @Autowired
    private RemoteDictService remoteDictService;

    /**
     * 查询整车物料列表
     */
    @Operation(summary = "整车物料号列表")
    @RequiresPermissions("vehicle:material:list")
    @GetMapping("/list")
    public TableDataInfo list(Material material) {
        startPage();
        List<Material> list = materialService.selectMaterialList(material);
        return getDataTable(list);
    }

    /**
     * 获取整车物料详细信息
     */
    @Operation(summary = "获取整车物料号信息")
    @RequiresPermissions("vehicle:material:query")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id) {
        return success(materialService.selectMaterialById(id));
    }

    /**
     * 新增整车物料
     */
    @Operation(summary = "新增整车物料号信息")
    @RequiresPermissions("vehicle:material:add")
    @Log(title = "整车物料", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody Material material) {
        return toAjax(materialService.insertMaterial(material));
    }

    /**
     * 修改整车物料
     */
    @Operation(summary = "编辑整车物料号信息")
    @RequiresPermissions("vehicle:material:edit")
    @Log(title = "整车物料", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody Material material) {
        return toAjax(materialService.updateMaterial(material));
    }

    /**
     * 删除整车物料
     */
    @Operation(summary = "删除整车物料号信息")
    @RequiresPermissions("vehicle:material:remove")
    @Log(title = "整车物料", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids) {
        return toAjax(materialService.deleteMaterialByIds(ids));
    }
}
