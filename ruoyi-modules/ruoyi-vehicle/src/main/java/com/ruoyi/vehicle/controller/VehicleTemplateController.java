package com.ruoyi.vehicle.controller;

import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.common.core.model.ValidationReport;
import com.ruoyi.common.core.web.controller.BaseController;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.core.web.page.TableDataInfo;
import com.ruoyi.common.log.annotation.Log;
import com.ruoyi.common.log.enums.BusinessType;
import com.ruoyi.common.security.annotation.RequiresPermissions;
import com.ruoyi.system.api.RemoteTranslateService;
import com.ruoyi.system.api.enums.FileTypeEnum;
import com.ruoyi.vehicle.domain.VehicleInfo;
import com.ruoyi.vehicle.domain.VehicleTemplate;
import com.ruoyi.vehicle.service.IVehicleTemplateService;
import com.ruoyi.vehicle.utils.ExcelUtil;
import io.swagger.v3.oas.annotations.Operation;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
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

    @Autowired
    private ExcelUtil excelUtil;

    /**
     * 查询列表
     */
    @RequiresPermissions("vehicle:template:query")
    @GetMapping("/list")
    public TableDataInfo list(VehicleTemplate template) {
        startPage();
        List<VehicleTemplate> list = vehicleTemplateService.selectVehicleTemplateList(template);
        return getDataTable(list);
    }

    @RequiresPermissions("vehicle:template:query")
    @GetMapping("/expiring")
    public TableDataInfo expiringList() {
        List<VehicleTemplate> list = vehicleTemplateService.selectVehicleTemplateExpiringList();
        return getDataTable(list);
    }

    @RequiresPermissions("vehicle:template:query")
    @GetMapping("/effecting")
    public TableDataInfo effectingList() {
        List<VehicleTemplate> list = vehicleTemplateService.selectVehicleTemplateEffectingList();
        return getDataTable(list);
    }

    @RequiresPermissions("vehicle:template:query")
    @GetMapping("/options")
    public AjaxResult optionSelect() {
        List<VehicleTemplate> list = vehicleTemplateService.selectVehicleTemplateOption();
        return AjaxResult.success(list);
    }

    /**
     * 查看详情（下钻）
     */
    @RequiresPermissions("vehicle:template:get")
    @GetMapping("/{templateId}")
    public AjaxResult getInfo(@PathVariable Long templateId) {
        return AjaxResult.success(vehicleTemplateService.selectVehicleTemplateById(templateId));
    }

    /**
     * 新增
     */
    @RequiresPermissions("vehicle:template:add")
    @Log(title = "车辆模板管理", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody VehicleTemplate template) {
        return toAjax(vehicleTemplateService.insertVehicleTemplate(template));
    }

    /**
     * 编辑
     */
    @RequiresPermissions("vehicle:template:edit")
    @Log(title = "车辆模板管理", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody VehicleTemplate template) {
        return toAjax(vehicleTemplateService.updateVehicleTemplate(template));
    }

    /**
     * 删除
     */
    @RequiresPermissions("vehicle:template:remove")
    @Log(title = "车辆模板管理", businessType = BusinessType.DELETE)
    @DeleteMapping("/{templateIds}")
    public AjaxResult remove(@PathVariable Long[] templateIds) {
        return toAjax(vehicleTemplateService.deleteVehicleTemplateByIds(templateIds));
    }

    /**
     * 导入pdf
     */
    @RequiresPermissions("vehicle:template:import")
    @Log(title = "车辆模板管理", businessType = BusinessType.IMPORT)
    @PostMapping(value = "/import/pdf", produces =  MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> uploadPdfFile(@RequestParam("file") MultipartFile file) {
        try {
            if (!(validateFile(file) == FileTypeEnum.PDF)) {
                throw new ServiceException(remoteTranslateService.translate("common.upload.file.type.unsupported", null));
            }
            return vehicleTemplateService.importPdf(file);
        } catch (Exception e) {
            log.error("文件导入失败", e);
            throw new ServiceException("文件导入失败: " + e.getMessage());
        }
    }

    /**
     * 导入
     */
    @RequiresPermissions("vehicle:template:import")
    @Log(title = "车辆模板管理", businessType = BusinessType.IMPORT)
    @PostMapping("/import/excel")
    public AjaxResult uploadExcelFile(@RequestParam("file") MultipartFile file) {
        if (!(validateFile(file) == FileTypeEnum.EXCEL)) {
            throw new ServiceException(remoteTranslateService.translate("common.upload.file.type.unsupported", null));
        }
        String taskId = vehicleTemplateService.submitImportTask(file);
        return AjaxResult.success(taskId);  // 前端拿到 taskId 再去订阅 SSE
    }

    @GetMapping(value = "/import/progress/{taskId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> importProgress(@PathVariable String taskId) {
        return vehicleTemplateService.getImportFlux(taskId);
    }

    /**
     * 导出
     */
    @RequiresPermissions("vehicle:template:export")
    @Log(title = "车辆模板管理", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, @RequestBody VehicleTemplate template) throws Exception {
        List<VehicleTemplate> list = vehicleTemplateService.selectVehicleTemplateList(template);
        excelUtil.exportExcel(response, list, "vehicle_template", "Vehicle Template");
    }

    /**
     * 启用/停用
     */
    @RequiresPermissions("vehicle:template:edit")
    @Log(title = "车辆模板管理", businessType = BusinessType.UPDATE)
    @PutMapping("/status")
    public AjaxResult changeStatus(@RequestBody VehicleTemplate template) {
        return toAjax(vehicleTemplateService.updateStatus(template.getTemplateId(), template.getStatus()));
    }

    @RequiresPermissions("vehicle:template:history")
    @PostMapping("/history")
    public AjaxResult historyVersion(@RequestBody VehicleTemplate template) {
        return AjaxResult.success(vehicleTemplateService.historyVersion(template));
    }

    /**
     * 批量校验
     */
    @RequiresPermissions("vehicle:template:validate")
    @Log(title = "车辆模板管理", businessType = BusinessType.VALIDATION)
    @PostMapping("/batchValidate")
    public AjaxResult batchValidate(@RequestBody(required = false) Long[] templateIds) {
        if (templateIds == null || templateIds.length == 0) {
            return AjaxResult.error("请提供模板id");
        }
        List<ValidationReport> reports = vehicleTemplateService.batchValidate(templateIds);
        return AjaxResult.success(reports);
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

    @GetMapping("/download")
    public void downloadTemplate(HttpServletResponse response) throws IOException {
        String fileName = "COC导入模版.xlsx";
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=" + URLEncoder.encode(fileName, "UTF-8"));

        ClassPathResource resource = new ClassPathResource("assets/" + fileName);
        IOUtils.copy(resource.getInputStream(), response.getOutputStream());
        response.flushBuffer();
    }

    private FileTypeEnum validateFile(MultipartFile file) {
        String fileName = file.getOriginalFilename();
        if (file.isEmpty() || fileName == null) {
            throw new ServiceException(remoteTranslateService.translate("common.upload.file.empty", null));
        }

        String extension = fileName.substring(fileName.lastIndexOf(".") + 1).toLowerCase();

        return FileTypeEnum.getByExtension(extension);
    }

    /**
     * 根据物料号 品牌 重量  销售名称  轮胎查模板关联信息
     */
    @PostMapping("/condition")
    public AjaxResult selectVehicleTemplateIdByCondition(@RequestBody VehicleInfo vehicleInfo) {
        return AjaxResult.success(vehicleTemplateService.selectVehicleTemplateIdByCondition(vehicleInfo.getMaterialNo(),
                vehicleInfo.getBrand(), vehicleInfo.getWeight(), vehicleInfo.getSaleName(), vehicleInfo.getTire(), vehicleInfo.getTvv()));
    }

    @Operation(summary = "新增前先获取可变参数列表")
    @GetMapping("/params")
    public AjaxResult getTemplateParams() {
        return AjaxResult.success(vehicleTemplateService.getTemplateParams());
    }
}
