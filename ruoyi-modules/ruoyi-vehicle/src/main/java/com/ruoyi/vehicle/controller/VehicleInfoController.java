package com.ruoyi.vehicle.controller;

import com.alibaba.fastjson2.util.DateUtils;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.common.core.utils.StringUtils;
import com.ruoyi.common.core.web.controller.BaseController;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.core.web.page.TableDataInfo;
import com.ruoyi.common.datascope.annotation.DataScope;
import com.ruoyi.common.log.annotation.Log;
import com.ruoyi.common.log.enums.BusinessType;
import com.ruoyi.common.security.annotation.RequiresPermissions;
import com.ruoyi.common.security.service.TokenService;
import com.ruoyi.common.security.utils.SecurityUtils;
import com.ruoyi.system.api.RemoteLoginService;
import com.ruoyi.system.api.RemoteNoticeService;
import com.ruoyi.system.api.RemoteTranslateService;
import com.ruoyi.system.api.domain.LoginBody;
import com.ruoyi.system.api.model.LoginUser;
import com.ruoyi.vehicle.domain.VehicleInfo;
import com.ruoyi.vehicle.domain.dto.BatchUpdateJsonFieldsDto;
import com.ruoyi.vehicle.domain.dto.BatchUpdateTemplateDto;
import com.ruoyi.vehicle.domain.dto.VehicleDto;
import com.ruoyi.vehicle.domain.vo.VehicleJsonKeyVo;
import com.ruoyi.vehicle.service.IFirstVehicleCheckService;
import com.ruoyi.vehicle.service.IVehicleInfoService;
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
import javax.validation.ConstraintViolation;
import java.io.IOException;
import java.net.URLEncoder;
import java.util.*;
import java.util.stream.Collectors;
@RestController
@RequestMapping("/vehicle")
public class VehicleInfoController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(VehicleInfoController.class);

    @Autowired
    private IVehicleInfoService vehicleInfoService;

    @Autowired
    private RemoteTranslateService remoteTranslateService;

    @Autowired
    private RemoteNoticeService remoteNoticeService;

    @Autowired
    private RemoteLoginService remoteLoginService;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private ExcelUtil excelUtil;

    @Autowired
    private javax.validation.Validator validator;

    @Autowired
    private IFirstVehicleCheckService firstVehicleCheckService;

    @Operation(summary = "MES数据推送至本系统")
    @Log(title = "数据推送", businessType = BusinessType.INSERT)
    @PostMapping("/to-system")
    public AjaxResult MesToSystem(@RequestBody VehicleDto vehicleDto) {
        // 校验 VehicleDto 本身（username、password、vehicles）
        Set<ConstraintViolation<VehicleDto>> dtoViolations = validator.validate(vehicleDto);
        if (!dtoViolations.isEmpty()) {
            String cause = dtoViolations.stream()
                    .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                    .collect(Collectors.joining("; "));
            return AjaxResult.error(cause);
        }

        LoginBody body = new LoginBody();
        body.setUsername(vehicleDto.getUsername());
        body.setPassword(vehicleDto.getPassword());
        R<?> loginResult = remoteLoginService.login(body);
        if (loginResult.getCode() != 200) {
            throw new ServiceException(loginResult.getMsg());
        }

        String token = ((LinkedHashMap<String, String>)loginResult.getData()).get("access_token");
        LoginUser fullLoginUser = tokenService.getLoginUser(token);
        if (fullLoginUser == null) {
            throw new ServiceException("登录信息获取失败");
        }

        // 用完整的 loginUser 往下传
        Date now = new Date();
        List<Map<String, Object>> result = new LinkedList<>();
        for (VehicleDto.Vehicle vehicle : vehicleDto.getVehicles()) {
            Map<String, Object> resultItem = new LinkedHashMap<>();
            Set<ConstraintViolation<VehicleDto.Vehicle>> violations = validator.validate(vehicle);
            if (!violations.isEmpty()) {
                String cause = violations.stream()
                        .map(v -> v.getPropertyPath() + " " + v.getMessage())
                        .collect(Collectors.joining("; "));
                resultItem.put("vin", vehicle.getVin());
                resultItem.put("recordId", null);
                resultItem.put("receiveTime", DateUtils.format(now, "yyyy-MM-dd HH:mm:ss"));
                resultItem.put("cause", cause);
                result.add(resultItem);
                continue;
            }
            try {
                resultItem = vehicleInfoService.getVehicleInfoFromMes(vehicle, now, fullLoginUser);
                result.add(resultItem);
            } catch (Exception e) {
                resultItem.put("vin", vehicle.getVin());
                resultItem.put("recordId", null);
                resultItem.put("receiveTime", DateUtils.format(now, "yyyy-MM-dd HH:mm:ss"));
                resultItem.put("cause", e.getMessage());
                result.add(resultItem);
            }
        }
        return AjaxResult.success(result);
    }
    /**
     * 查询车辆信息列表
     */
    @GetMapping("/list")
    @RequiresPermissions("vehicle:info:query")
    @DataScope(tableAlias = "vi")
    public TableDataInfo list(VehicleInfo vehicleInfo) {
        // 手动输入的vin按逗号/换行拆分成vinList
        if (StringUtils.isNotBlank(vehicleInfo.getVin())) {
            List<String> vinList = Arrays.stream(vehicleInfo.getVin().split("[,，\n]"))
                    .map(String::trim)
                    .filter(StringUtils::isNotBlank)
                    .collect(Collectors.toList());
            vehicleInfo.setVinList(vinList);
            vehicleInfo.setVin(null); // 清掉vin，走vinList的IN查询
        }
        if (StringUtils.isNotBlank(vehicleInfo.getMaterialNo())) {
            List<String> materialNoList = Arrays.stream(vehicleInfo.getMaterialNo().split("[,，\n]"))
                    .map(String::trim)
                    .filter(StringUtils::isNotBlank)
                    .collect(Collectors.toList());
            vehicleInfo.setMaterialNoList(materialNoList);
            vehicleInfo.setMaterialNo(null);
        }
        startPage();
        List<VehicleInfo> list = vehicleInfoService.selectVehicleInfoList(vehicleInfo);
        return getDataTable(list);
    }

    /**
     * 获取车辆信息详细信息
     */
    @Operation(summary = "车辆信息详情")
    @RequiresPermissions("vehicle:info:query")
    @GetMapping(value = "/{vehicleId}")
    public AjaxResult getInfo(@PathVariable("vehicleId") Long vehicleId) {
        return AjaxResult.success(vehicleInfoService.selectVehicleInfoById(vehicleId));
    }

    @Operation(summary = "更新车辆信息状态")
    @RequiresPermissions("vehicle:info:edit")
    @Log(title = "车辆信息管理", businessType = BusinessType.UPDATE)
    @PutMapping("/changeStatus")
    public AjaxResult changeStatus(@RequestBody VehicleInfo vehicleInfo)
    {
        vehicleInfo.setValidationResult(0);
        vehicleInfo.setUploadStatus(0);
        vehicleInfoService.updateVehicleInfo(vehicleInfo, true);
        // handleAfterUpdate 内部自行查旧数据，只有物料号或模版变化时才触发重算
        firstVehicleCheckService.handleAfterUpdate(vehicleInfo);
        return AjaxResult.success();
    }

    /**
     * 新增车辆信息
     */
    @Operation(summary = "新增车辆信息")
    @RequiresPermissions("vehicle:info:add")
    @Log(title = "车辆信息管理", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody VehicleInfo vehicleInfo) {
        vehicleInfoService.insertVehicleInfo(vehicleInfo);
        firstVehicleCheckService.handleAfterInsert(Collections.singletonList(vehicleInfo));
        return AjaxResult.success(vehicleInfo);
    }

    @Operation(summary = "校验车辆信息")
    @RequiresPermissions("vehicle:info:validation")
    @Log(title = "车辆信息管理", businessType = BusinessType.VALIDATION)
    @PostMapping("/validation")
    public AjaxResult validation(@RequestBody List<Long> vehicleInfoId) {
        return AjaxResult.success(vehicleInfoService.validateVehicleInfo(vehicleInfoId));
    }

    /**
     * 修改车辆信息
     */
    @Operation(summary = "修改信息列表")
    @RequiresPermissions("vehicle:info:edit")
    @Log(title = "车辆信息管理", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody VehicleInfo vehicleInfo) {
        // 用户手动编辑时才重置校验状态和上传状态
        vehicleInfo.setValidationResult(0);
        vehicleInfo.setUploadStatus(0);
        return AjaxResult.success(vehicleInfoService.updateVehicleInfo(vehicleInfo, true));
    }
    /**
     * 删除车辆信息
     */
    @Operation(summary = "删除车辆信息")
    @RequiresPermissions("vehicle:info:remove")
    @Log(title = "车辆信息管理", businessType = BusinessType.DELETE)
    @DeleteMapping("/{vehicleIds}")
    public AjaxResult remove(@PathVariable Long[] vehicleIds) {
        List<VehicleInfo> snapshot = vehicleInfoService.selectVehicleInfoByIds(vehicleIds);
        vehicleInfoService.deleteVehicleInfoByIds(vehicleIds);
        firstVehicleCheckService.handleAfterDelete(snapshot);
        return AjaxResult.success();
    }

    /**
     * 恢复车辆信息
     */
    @Operation(summary = "恢复车辆信息")
    @RequiresPermissions("vehicle:info:restore")
    @Log(title = "车辆信息管理", businessType = BusinessType.RESTORE)
    @PutMapping("/restore")
    public AjaxResult recover(@RequestBody Long[] vehicleIds) {
        return vehicleInfoService.restoreVehicleInfoByIds(vehicleIds);
    }

    /**
     * 永久删除车辆信息
     */
    @Operation(summary = "永久删除车辆信息")
    @RequiresPermissions("vehicle:info:permanently")
    @Log(title = "车辆信息管理", businessType = BusinessType.PERMANENTLY_DELETE)
    @PutMapping("/permanently")
    public AjaxResult permanently(@RequestBody Long[] vehicleIds) {
        return AjaxResult.success(vehicleInfoService.permanentlyDeleteVehicleInfoByIds(vehicleIds));
    }

    @RequiresPermissions("vehicle:info:import")
    @PostMapping("/import/excel")
    public R<String> importExcel(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return R.fail("上传文件不能为空");
        }
        String taskId = vehicleInfoService.submitImportTask(file);
        return R.ok(taskId);
    }

    @GetMapping(value = "/import/excel/{taskId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> importFlux(@PathVariable("taskId") String taskId) {
        return vehicleInfoService.getImportFlux(taskId);
    }


    @RequiresPermissions("vehicle:info:export")
    @PostMapping("/export/excel")
    public void exportExcel(HttpServletResponse response, @RequestBody VehicleInfo vehicleInfo) throws Exception {
        List<VehicleInfo> list = vehicleInfoService.selectVehicleInfoList(vehicleInfo);
        excelUtil.exportExcel(response, list, "vehicle_info", "Vehicle");
    }

    @GetMapping("/download/vin")
    public void downloadVinTemplate(HttpServletResponse response) throws IOException {
        download("车辆信息VIN查询模版.xlsx", response);
    }

    @GetMapping("/download/materialNo")
    public void downloadMaterialNoTemplate(HttpServletResponse response) throws IOException {
        download("车辆信息物料号查询模版.xlsx", response);
    }

    @GetMapping("/download/template")
    public void downloadTemplate(HttpServletResponse response) throws IOException {
        download("车辆信导入息模版.xlsx", response);
    }

    private void download(String fileName, HttpServletResponse response) throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=" + URLEncoder.encode(fileName, "UTF-8"));

        ClassPathResource resource = new ClassPathResource("assets/" + fileName);
        IOUtils.copy(resource.getInputStream(), response.getOutputStream());
        response.flushBuffer();
    }

    /**
     * 获取各车辆信息关联的模版的版本，要修改的版本
     * POST /vehicle/info/batchUpdateTemplate
     */
    @PostMapping("/getTemplateVersion")
    public AjaxResult getTemplateVersion(@RequestBody BatchUpdateTemplateDto dto) {
        try {
            Map<String, List<Map<String, String>>> map = vehicleInfoService.getTemplateVersion(dto.getVehicleIds());
            return AjaxResult.success(map);
        } catch (ServiceException e) {
            return AjaxResult.error(e.getMessage());
        }
    }

    /**
     * 批量修改关联模版
     * POST /vehicle/info/batchUpdateTemplate
     */
    @PostMapping("/batchUpdateTemplate")
    public AjaxResult batchUpdateTemplate(@RequestBody BatchUpdateTemplateDto dto) {
        try {
            int count = vehicleInfoService.batchUpdateVehicleTemplate(dto.getVehicleIds());
            return AjaxResult.success("成功更新 " + count + " 辆车辆的关联模版");
        } catch (ServiceException e) {
            return AjaxResult.error(e.getMessage());
        }
    }

    /**
     * 查询多辆车 JSON 键的并集，关联字典 label 信息
     * POST /vehicle/info/jsonKeys
     * Body: {"vehicleIds": [1, 2, 3]}
     */
    @PostMapping("/jsonKeys")
    public AjaxResult listJsonKeys(@RequestBody Map<String, List<Long>> body) {
        List<Long> vehicleIds = body.get("vehicleIds");
        List<VehicleJsonKeyVo> result = vehicleInfoService.listJsonKeysByVehicleIds(vehicleIds);
        return AjaxResult.success(result);
    }

    /**
     * 批量替换车辆 JSON 中指定键的值
     * POST /vehicle/info/batchUpdateJsonFields
     * Body: {"vehicleIds": [1,2,3], "fieldValues": {"engineType":"电动","color":"白色"}}
     */
    @PostMapping("/batchUpdateJsonFields")
    public AjaxResult batchUpdateJsonFields(@RequestBody BatchUpdateJsonFieldsDto dto) {
        try {
            int count = vehicleInfoService.batchUpdateVehicleJsonFields(
                    dto.getVehicleIds(), dto.getFieldValues());
            return AjaxResult.success("成功更新 " + count + " 辆车辆的 JSON 字段");
        } catch (ServiceException e) {
            return AjaxResult.error(e.getMessage());
        }
    }

    /**
     * Tab1：整车物料号维度未确认列表
     * 在车辆信息查询条件基础上，固定过滤 first_material_flag = 1
     */
    @Operation(summary = "首台车-物料号维度未确认列表")
    @RequiresPermissions("vehicle:first:query")
    @GetMapping("/material/list")
    public TableDataInfo materialList(VehicleInfo vehicleInfo) {
        // 固定只查 first_material_flag = 1 的记录
        vehicleInfo.setFirstMaterialFlag(1);
        startPage();
        return getDataTable(vehicleInfoService.selectVehicleInfoList(vehicleInfo));
    }

    /**
     * 首台车待确认列表（物料号维度 + 模版维度统一接口）
     *
     * <p>通过 dimension 参数区分维度：
     * <ul>
     *   <li>material：物料号维度，查 first_material_flag=1 AND generate_affirm=0</li>
     *   <li>template：模版维度，查 first_template_flag=1 AND upload_affirm=0</li>
     * </ul>
     *
     * <p>其余查询条件与车辆列表保持一致，支持 VIN、物料号、模版号等过滤。
     */
    @Operation(summary = "首台车待确认列表")
    @RequiresPermissions("vehicle:first:query")
    @GetMapping("/first/list")
    public TableDataInfo firstVehicleList(
            VehicleInfo vehicleInfo,
            @RequestParam(value = "dimension", defaultValue = "material") String dimension) {

        if (!"material".equals(dimension) && !"template".equals(dimension)) {
            return getDataTable(Collections.emptyList());
        }

        // VIN 支持逗号/换行分隔批量查询
        if (StringUtils.isNotBlank(vehicleInfo.getVin())) {
            List<String> vinList = Arrays.stream(vehicleInfo.getVin().split("[,，\n]"))
                    .map(String::trim)
                    .filter(StringUtils::isNotBlank)
                    .collect(Collectors.toList());
            vehicleInfo.setVinList(vinList);
            vehicleInfo.setVin(null);
        }
        // 物料号支持逗号/换行分隔批量查询
        if (StringUtils.isNotBlank(vehicleInfo.getMaterialNo())) {
            List<String> materialNoList = Arrays.stream(vehicleInfo.getMaterialNo().split("[,，\n]"))
                    .map(String::trim)
                    .filter(StringUtils::isNotBlank)
                    .collect(Collectors.toList());
            vehicleInfo.setMaterialNoList(materialNoList);
            vehicleInfo.setMaterialNo(null);
        }

        startPage();
        List<VehicleInfo> list = vehicleInfoService.listFirstVehicleUnconfirmed(vehicleInfo, dimension);
        return getDataTable(list);
    }

    // ===================================================================
    //  确认
    // ===================================================================

    /**
     * 确认物料号首台（确认可生成）
     * <p>将该车辆 generate_affirm 置为 1，记录确认人、时间和确认原因。
     * <p>确认后该车辆不再出现在 dimension=material 的待确认列表中。
     */
    @Operation(summary = "确认物料号首台（可生成）")
    @RequiresPermissions("vehicle:first:confirm")
    @Log(title = "首台车确认", businessType = BusinessType.UPDATE)
    @PutMapping("/first/material/confirm/{vehicleId}")
    public AjaxResult confirmMaterial(
            @PathVariable Long vehicleId,
            @RequestBody(required = false) VehicleInfo request) {
        String cause = (request != null) ? request.getGenerateAffirmCause() : null;
        firstVehicleCheckService.confirmMaterial(vehicleId, SecurityUtils.getUsername(), cause);
        return AjaxResult.success();
    }

    /**
     * 确认模版首台（确认可上传）
     * <p>将该车辆 upload_affirm 置为 1，记录确认人、时间和确认原因。
     * <p>确认后该车辆不再出现在 dimension=template 的待确认列表中。
     */
    @Operation(summary = "确认模版首台（可上传）")
    @RequiresPermissions("vehicle:first:confirm")
    @Log(title = "首台车确认", businessType = BusinessType.UPDATE)
    @PutMapping("/first/template/confirm/{vehicleId}")
    public AjaxResult confirmTemplate(
            @PathVariable Long vehicleId,
            @RequestBody(required = false) VehicleInfo request) {
        String cause = (request != null) ? request.getUploadAffirmCause() : null;
        firstVehicleCheckService.confirmTemplate(vehicleId, SecurityUtils.getUsername(), cause);
        return AjaxResult.success();
    }
}
