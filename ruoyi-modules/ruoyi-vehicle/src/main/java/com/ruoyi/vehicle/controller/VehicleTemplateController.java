package com.ruoyi.vehicle.controller;

import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.common.core.model.ValidationReport;
import com.ruoyi.common.core.utils.poi.ExcelUtil;
import com.ruoyi.common.core.web.controller.BaseController;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.core.web.page.TableDataInfo;
import com.ruoyi.common.log.annotation.Log;
import com.ruoyi.common.log.enums.BusinessType;
import com.ruoyi.common.security.annotation.RequiresPermissions;
import com.ruoyi.system.api.RemoteTranslateService;
import com.ruoyi.system.api.enums.FileTypeEnum;
import com.ruoyi.vehicle.domain.VehicleTemplate;
import com.ruoyi.vehicle.domain.VehicleTemplateMaterial;
import com.ruoyi.vehicle.service.IVehicleTemplateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import javax.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/vehicle/template")
public class VehicleTemplateController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(VehicleTemplateController.class);

    @Autowired
    private IVehicleTemplateService vehicleTemplateService;

    @Autowired
    private RemoteTranslateService remoteTranslateService;

    /**
     * 查询列表
     */
    @RequiresPermissions("vehicle:template:list")
    @GetMapping("/list")
    public TableDataInfo list(VehicleTemplate template) {
        startPage();
        List<VehicleTemplate> list = vehicleTemplateService.selectVehicleTemplateList(template);
        return getDataTable(list);
    }

    /**
     * 查看详情（下钻）
     */
    @RequiresPermissions("vehicle:template:query")
    @GetMapping("/{templateId}")
    public AjaxResult getInfo(@PathVariable Long templateId) {
        return AjaxResult.success(vehicleTemplateService.selectVehicleTemplateById(templateId));
    }

    /**
     * 新增
     */
    @RequiresPermissions("vehicle:template:add")
    @Log(title = "车辆模板", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody VehicleTemplate template) {
        return toAjax(vehicleTemplateService.insertVehicleTemplate(template));
    }

    /**
     * 编辑
     */
    @RequiresPermissions("vehicle:template:edit")
    @Log(title = "车辆模板", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody VehicleTemplate template) {
        return toAjax(vehicleTemplateService.updateVehicleTemplate(template));
    }

    /**
     * 删除
     */
    @RequiresPermissions("vehicle:template:remove")
    @Log(title = "车辆模板", businessType = BusinessType.DELETE)
    @DeleteMapping("/{templateIds}")
    public AjaxResult remove(@PathVariable Long[] templateIds) {
        return toAjax(vehicleTemplateService.deleteVehicleTemplateByIds(templateIds));
    }

    /**
     * 导入
     */
    @RequiresPermissions("vehicle:template:import")
    @Log(title = "车辆信息管理", businessType = BusinessType.IMPORT)
    @PostMapping(value = "/import", produces =  MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> uploadPdfFile(@RequestParam("file") MultipartFile file) {
        try {
            String fileName = file.getOriginalFilename();
            if (file.isEmpty() || fileName == null) {
                throw new ServiceException(remoteTranslateService.translate("common.upload.file.empty", null));
            }

            String extension = fileName.substring(fileName.lastIndexOf(".") + 1).toLowerCase();

            FileTypeEnum fileType = FileTypeEnum.getByExtension(extension);
            if (!(fileType == FileTypeEnum.PDF)) {
                throw new ServiceException(remoteTranslateService.translate("common.upload.file.type.unsupported", null));
            }

            return vehicleTemplateService.importPdf(file);
        } catch (Exception e) {
            log.error("文件导入失败", e);
            throw new ServiceException("文件导入失败: " + e.getMessage());
        }
    }

    /**
     * 导出
     */
    @RequiresPermissions("vehicle:template:export")
    @Log(title = "车辆模板", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, @RequestBody VehicleTemplate template) {
        List<VehicleTemplate> list = vehicleTemplateService.selectVehicleTemplateList(template);
        ExcelUtil<VehicleTemplate> util = new ExcelUtil<>(VehicleTemplate.class);
        util.exportExcel(response, list, "车辆模板数据");
    }

    /**
     * 启用/停用
     */
    @RequiresPermissions("vehicle:template:edit")
    @Log(title = "车辆模板状态", businessType = BusinessType.UPDATE)
    @PutMapping("/status")
    public AjaxResult changeStatus(@RequestBody VehicleTemplate template) {
        return toAjax(vehicleTemplateService.updateStatus(template.getTemplateId(), template.getStatus()));
    }

    /**
     * ✅ 批量校验（支持单个或多个 ID）
     * 示例：
     *   POST /vehicle/template/batchValidate?templateIds=1
     *   POST /vehicle/template/batchValidate?templateIds=1,2,3
     *   POST /vehicle/template/batchValidate (body: [1,2,3])
     */
    @RequiresPermissions("vehicle:template:validate")
    @Log(title = "车辆模板校验", businessType = BusinessType.OTHER)
    @PostMapping("/batchValidate")
    public AjaxResult batchValidate(@RequestBody(required = false) Long[] templateIds) {
        if (templateIds == null || templateIds.length == 0) {
            return AjaxResult.error("请提供模板id");
        }
        List<ValidationReport> reports = vehicleTemplateService.batchValidate(templateIds);
        return AjaxResult.success(reports);
    }

    /**
     * 查询物料号列表
     */
    @GetMapping("/material/{templateId}")
    public AjaxResult getMaterialList(@PathVariable Long templateId) {
        return AjaxResult.success(vehicleTemplateService.selectMaterialByTemplateId(templateId));
    }

    /**
     * 保存物料号列表（1模板对N物料号）
     */
    @PostMapping("/material/{templateId}")
    public AjaxResult saveMaterialList(
            @PathVariable Long templateId,
            @RequestBody List<VehicleTemplateMaterial> materialList) {
        return toAjax(vehicleTemplateService.saveMaterialList(templateId, materialList));
    }

    @PostMapping("/callback")
    public AjaxResult callback(@RequestBody Map<String, Object> data) {
        String taskId = (String) data.get("task_id");
        String type = (String) data.get("type");

        if ("progress".equals(type)) {
            vehicleTemplateService.sendProgress(taskId, data);
        } else if ("complete".equals(type)) {
            vehicleTemplateService.sendComplete(taskId, data);
        } else if ("error".equals(type)) {
            vehicleTemplateService.sendError(taskId, data);
        }
        return AjaxResult.success();
    }
}