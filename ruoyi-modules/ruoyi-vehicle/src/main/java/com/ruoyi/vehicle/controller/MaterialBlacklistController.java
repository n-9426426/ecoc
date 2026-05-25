package com.ruoyi.vehicle.controller;

import com.ruoyi.common.core.web.controller.BaseController;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.core.web.page.TableDataInfo;
import com.ruoyi.common.log.annotation.Log;
import com.ruoyi.common.log.enums.BusinessType;
import com.ruoyi.common.security.annotation.RequiresPermissions;
import com.ruoyi.vehicle.domain.MaterialBlacklist;
import com.ruoyi.vehicle.service.IMaterialBlacklistService;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 物料黑名单 Controller
 */
@RestController
@RequestMapping("/material/blacklist")
@RequiredArgsConstructor
public class MaterialBlacklistController extends BaseController {

    private final IMaterialBlacklistService materialBlacklistService;

    /** 查询列表 */
    @RequiresPermissions("material:blacklist:list")
    @GetMapping("/list")
    public TableDataInfo list(MaterialBlacklist materialBlacklist) {
        startPage();
        List<MaterialBlacklist> list = materialBlacklistService.selectMaterialBlacklistList(materialBlacklist);
        return getDataTable(list);
    }

    /** 根据ID查询 */
    @RequiresPermissions("material:blacklist:query")
    @GetMapping("/{id}")
    public AjaxResult getInfo(@PathVariable Long id) {
        return success(materialBlacklistService.selectMaterialBlacklistById(id));
    }

    /** 新增 */
    @RequiresPermissions("material:blacklist:add")
    @Log(title = "物料黑名单", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@Validated @RequestBody MaterialBlacklist materialBlacklist) {
        return toAjax(materialBlacklistService.insertMaterialBlacklist(materialBlacklist));
    }

    /** 修改 */
    @RequiresPermissions("material:blacklist:edit")
    @Log(title = "物料黑名单", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@Validated @RequestBody MaterialBlacklist materialBlacklist) {
        return toAjax(materialBlacklistService.updateMaterialBlacklist(materialBlacklist));
    }

    /** 删除（批量） */
    @RequiresPermissions("material:blacklist:remove")
    @Log(title = "物料黑名单", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids) {
        return toAjax(materialBlacklistService.deleteMaterialBlacklistByIds(ids));
    }

    @RequiresPermissions("material:blacklist:edit")
    @Log(title = "物料黑名单", businessType = BusinessType.UPDATE)
    @PutMapping("/status/{id}")
    public AjaxResult updateStatus(@PathVariable Long id) {
        return success(materialBlacklistService.updateMaterialBlacklistStatus(id));
    }
}
