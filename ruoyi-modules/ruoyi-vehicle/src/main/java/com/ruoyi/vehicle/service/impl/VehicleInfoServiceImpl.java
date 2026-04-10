package com.ruoyi.vehicle.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.core.utils.DateUtils;
import com.ruoyi.common.core.utils.StringUtils;
import com.ruoyi.common.core.utils.poi.ExcelUtil;
import com.ruoyi.common.core.utils.uuid.UUID;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.system.api.RemoteTranslateService;
import com.ruoyi.vehicle.domain.VehicleInfo;
import com.ruoyi.vehicle.mapper.VehicleInfoMapper;
import com.ruoyi.vehicle.service.IVehicleInfoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service("vehicleInfoService")
public class VehicleInfoServiceImpl implements IVehicleInfoService {

    private static final Logger log = LoggerFactory.getLogger(VehicleInfoServiceImpl.class);

    @Autowired
    private VehicleInfoMapper vehicleInfoMapper;

    @Value("${ocr.python.url}")
    private String pythonUrl;

    @Value("${ocr.callback.url}")
    private String callbackUrl;

    @Autowired
    private RemoteTranslateService remoteTranslateService;

    private final Map<String, Sinks.Many<ServerSentEvent<String>>> sinks = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final ExecutorService executor = Executors.newCachedThreadPool();

    /**
     * 查询车辆信息
     *
     * @param vehicleId 车辆ID
     * @return 车辆信息
     */
    @Override
    public VehicleInfo selectVehicleInfoById(Long vehicleId) {
        return vehicleInfoMapper.selectVehicleInfoById(vehicleId);
    }

    /**
     * 查询车辆信息列表
     *
     * @param vehicleInfo 车辆信息
     * @return 车辆信息
     */
    @Override
    public List<VehicleInfo> selectVehicleInfoList(VehicleInfo vehicleInfo) {
        return vehicleInfoMapper.selectVehicleInfoList(vehicleInfo);
    }

    /**
     * 新增车辆信息
     *
     * @param vehicleInfo 车辆信息
     * @return 结果
     */
    @Override
    public int insertVehicleInfo(VehicleInfo vehicleInfo) {
        if (selectVehicleInfoByVin(vehicleInfo.getVin()) != null) {
            throw new RuntimeException("数据已存在");
        }
        vehicleInfo.setCreateTime(DateUtils.getNowDate());
        return vehicleInfoMapper.insertVehicleInfo(vehicleInfo);
    }

    private VehicleInfo selectVehicleInfoByVin(String vin) {
        return vehicleInfoMapper.selectVehicleInfoByVin(vin);
    }

    /**
     * 修改车辆信息
     *
     * @param vehicleInfo 车辆信息
     * @return 结果
     */
    @Override
    public int updateVehicleInfo(VehicleInfo vehicleInfo) {
        vehicleInfo.setUpdateTime(DateUtils.getNowDate());
        return vehicleInfoMapper.updateVehicleInfo(vehicleInfo);
    }

    /**
     * 批量删除车辆信息
     *
     * @param vehicleIds 需要删除的车辆ID
     * @return 结果
     */
    @Override
    public AjaxResult deleteVehicleInfoByIds(Long[] vehicleIds) {
        try {
            int deleteRows = vehicleInfoMapper.deleteVehicleInfoByIds(vehicleIds);
            return AjaxResult.success(deleteRows);
        } catch (Exception e){
            return AjaxResult.error(e.getMessage());
        }
    }

    /**
     * 批量恢复车辆信息
     *
     * @param vehicleIds 需要恢复的车辆主键集合
     * @return 结果
     */
    @Override
    public AjaxResult restoreVehicleInfoByIds(Long[] vehicleIds) {
        int restoreRows = vehicleInfoMapper.restoreVehicleInfoByIds(vehicleIds);
        Map<String, Object> result = new HashMap<>();
        result.put("restoreRows", restoreRows);
        return AjaxResult.success(result);
    }

    /**
     * 永久删除车辆信息
     *
     * @param vehicleId 需要永久删除的车辆主键集合
     * @return 结果
     */
    @Override
    public int permanentlyDeleteVehicleInfoById(Long vehicleId) {
        return vehicleInfoMapper.permanentlyDeleteVehicleInfoById(vehicleId);
    }

    /**
     * 批量永久删除车辆信息
     *
     * @param vehicleIds 需要永久删除的车辆主键集合
     * @return 结果
     */
    @Override
    public int permanentlyDeleteVehicleInfoByIds(Long[] vehicleIds) {
        return vehicleInfoMapper.permanentlyDeleteVehicleInfoByIds(vehicleIds);
    }

    @Override
    public AjaxResult importExcel(MultipartFile file) throws Exception {
        ExcelUtil<VehicleInfo> excelUtil = new ExcelUtil<>(VehicleInfo.class);
        List<VehicleInfo> vehicleList = excelUtil.importExcel(file.getInputStream());

        if (vehicleList.isEmpty()) {
            return AjaxResult.error(remoteTranslateService.translate("vehicle.import.data.empty", null));
        }

        int successCount = 0;
        int failCount = 0;
        StringBuilder failMsg = new StringBuilder();

        for (VehicleInfo vehicle : vehicleList) {
            try {
                // 检查vin是否已存在
                VehicleInfo query = new VehicleInfo();
                query.setVin(vehicle.getVin());
                List<VehicleInfo> existList = this.selectVehicleInfoList(query);

                if (!existList.isEmpty()) {
                    failCount++;
                    failMsg.append(remoteTranslateService.translate("vehicle.import.vin.exists", vehicle.getVin()));
                    continue;
                }
                vehicle.setImportSource("Excel上传");
                successCount++;
            } catch (Exception e) {
                failCount++;
                failMsg.append(StringUtils.format(remoteTranslateService.translate("vehicle.import.fail", null), vehicle.getVin(), e.getMessage()));
            }
        }

        Map<String, Object> resultMap = new HashMap<String, Object>();
        resultMap.put("total", vehicleList.size());
        resultMap.put("successCount", successCount);
        resultMap.put("failCount", failCount);
        resultMap.put("failMsg", failMsg.toString());
        resultMap.put("message", StringUtils.format(remoteTranslateService.translate("common.import.result", null), successCount, failCount));
        resultMap.put("data", vehicleList);
        return AjaxResult.success(resultMap);
    }

    public Flux<ServerSentEvent<String>> importPdf(MultipartFile file) {
        String taskId = UUID.randomUUID().toString();

        // ✅ 创建热流Sink
        Sinks.Many<ServerSentEvent<String>> sink = Sinks.many().unicast()
                .onBackpressureBuffer();

        sinks.put(taskId, sink);

        try {
            byte[] fileBytes = file.getBytes();
            String fileName = file.getOriginalFilename();

            executor.execute(() -> {
                try {
                    // 先发一条初始消息
                    sendProgress(taskId, new HashMap<String, Object>() {{
                        put("progress", 10);
                        put("message", "文件上传中...");
                    }}) ;

                    RestTemplate restTemplate = new RestTemplate();
                    MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
                    body.add("file", new ByteArrayResource(fileBytes) {
                        @Override
                        public String getFilename() { return fileName; }
                    });
                    body.add("callback_url", callbackUrl);
                    body.add("task_id", taskId);

                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentType(MediaType.MULTIPART_FORM_DATA);

                    restTemplate.postForEntity(pythonUrl,new HttpEntity<>(body, headers), String.class);

                    sendProgress(taskId, new HashMap<String, Object>() {{
                        put("progress", 20);
                        put("message", "Python处理中...");
                    }});

                } catch (Exception e) {
                    log.error("发送到Python失败", e);
                    sendError(taskId, new HashMap<String, Object>() {{put("message", e.getMessage());}});
                }
            });

        } catch (IOException e) {
            log.error("读取文件失败", e);
            sendError(taskId, new HashMap<String, Object>() {{put("message", "读取文件失败: " + e.getMessage());}});
        }

        // 返回 Flux，连接断开时自动清理
        return sink.asFlux()
                .doOnCancel(() -> {
                    log.info("客户端断开连接, taskId: {}", taskId);
                    sinks.remove(taskId);
                })
                .doOnComplete(() -> {
                    log.info("SSE完成, taskId: {}", taskId);
                    sinks.remove(taskId);
                });
    }

    /**
     * OCR识别进度回调
     */
    public void sendProgress(String taskId, Map<String, Object> data) {
        Sinks.Many<ServerSentEvent<String>> sink = sinks.get(taskId);
        if (sink == null) {
            log.warn("sink不存在, taskId: {}", taskId);
            return;
        }
        try {
            data = new HashMap<>(data);
            data.put("type", "progress");
            String json = objectMapper.writeValueAsString(data);

            ServerSentEvent<String> event = ServerSentEvent.<String>builder()
                    .event("progress")
                    .data(json)
                    .build();

            // 发射事件
            Sinks.EmitResult result = sink.tryEmitNext(event);
            log.info("发送进度, taskId: {}, result: {}, data: {}", taskId, result, json);

        } catch (Exception e) {
            log.error("发送进度失败", e);
        }
    }

    /**
     * OCR识别完成回调
     */
    public void sendComplete(String taskId, Map<String, Object> data) {
        Sinks.Many<ServerSentEvent<String>> sink = sinks.get(taskId);
        if (sink == null) return;
        try {
            data = new HashMap<>(data);
            data.put("type", "complete");
            String json = objectMapper.writeValueAsString(data);

            ServerSentEvent<String> event = ServerSentEvent.<String>builder()
                    .event("complete")
                    .data(json)
                    .build();

            sink.tryEmitNext(event);
            sink.tryEmitComplete();
            sinks.remove(taskId);

        } catch (Exception e) {
            log.error("发送完成失败", e);
            sink.tryEmitError(e);
            sinks.remove(taskId);
        }
    }

    /**
     * OCR识别错误回调
     */
    public void sendError(String taskId, Map<String, Object> data) {
        Sinks.Many<ServerSentEvent<String>> sink = sinks.get(taskId);
        if (sink == null) return;
        try {
            data = new HashMap<>(data);
            data.put("type", "error");
            String json = objectMapper.writeValueAsString(data);

            ServerSentEvent<String> event = ServerSentEvent.<String>builder()
                    .event("error")
                    .data(json)
                    .build();

            sink.tryEmitNext(event);
            sink.tryEmitComplete();
            sinks.remove(taskId);

        } catch (Exception e) {
            log.error("发送错误失败", e);
            sink.tryEmitError(e);
            sinks.remove(taskId);
        }
    }
}
