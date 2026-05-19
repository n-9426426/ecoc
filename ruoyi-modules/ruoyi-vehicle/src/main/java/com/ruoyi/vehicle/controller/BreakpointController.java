package com.ruoyi.vehicle.controller;

import com.ruoyi.common.core.web.controller.BaseController;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.core.web.page.TableDataInfo;
import com.ruoyi.common.log.annotation.Log;
import com.ruoyi.common.log.enums.BusinessType;
import com.ruoyi.common.security.annotation.RequiresPermissions;
import com.ruoyi.vehicle.domain.Breakpoint;
import com.ruoyi.vehicle.service.IBreakpointService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 断点管理 Controller
 *
 * @author ruoyi
 */
@RestController
@RequestMapping("/breakpoint")
public class BreakpointController extends BaseController {

    @Autowired
    private IBreakpointService breakpointService;

    /**
     * 查询断点列表
     */
    @Operation(summary = "断点列表")
    @RequiresPermissions("vehicle:breakpoint:list")
    @GetMapping("/list")
    public TableDataInfo list(Breakpoint breakpoint) {
        startPage();
        List<Breakpoint> list = breakpointService.selectBreakpointList(breakpoint);
        return getDataTable(list);
    }

    /**
     * 获取断点详细信息
     */
    @Operation(summary = "获取断点信息")
    @RequiresPermissions("vehicle:breakpoint:query")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id) {
        return success(breakpointService.selectBreakpointById(id));
    }

    /**
     * 新增断点
     */
    @Operation(summary = "新增断点信息")
    @RequiresPermissions("vehicle:breakpoint:add")
    @Log(title = "断点管理", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody Breakpoint breakpoint) {
        return toAjax(breakpointService.insertBreakpoint(breakpoint));
    }

    /**
     * 修改断点
     */
    @Operation(summary = "编辑断点信息")
    @RequiresPermissions("vehicle:breakpoint:edit")
    @Log(title = "断点管理", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody Breakpoint breakpoint) {
        return toAjax(breakpointService.updateBreakpoint(breakpoint));
    }

    /**
     * 删除断点
     */
    @Operation(summary = "删除断点信息")
    @RequiresPermissions("vehicle:breakpoint:remove")
    @Log(title = "断点管理", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids) {
        return toAjax(breakpointService.deleteBreakpointByIds(ids));
    }

    /**
     * 处理断点（标记已处理）
     */
    @Operation(summary = "手动标记断点处理状态")
    @RequiresPermissions("vehicle:breakpoint:edit")
    @Log(title = "断点管理", businessType = BusinessType.UPDATE)
    @PutMapping("/dispose")
    public AjaxResult dispose(@RequestBody Breakpoint breakpoint) {


        return toAjax(breakpointService.disposeBreakpoint(breakpoint));
    }
}
