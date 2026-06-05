package com.ruoyi.vehicle.service;

import com.ruoyi.common.core.model.ValidationReport;
import com.ruoyi.vehicle.domain.VehicleTemplate;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

public interface IVehicleTemplateService {

    List<VehicleTemplate> selectVehicleTemplateList(VehicleTemplate template);

    VehicleTemplate selectVehicleTemplateById(Long templateId);

    int importVehicleTemplate(MultipartFile file) throws Exception;

    int insertVehicleTemplate(VehicleTemplate template);

    int updateVehicleTemplate(VehicleTemplate template);

    int deleteVehicleTemplateByIds(Long[] templateIds);

    int updateStatus(Long templateId, String status);

    List<ValidationReport> batchValidate(Long... templateIds);

    Flux<ServerSentEvent<String>> importPdf(MultipartFile file);

    void sendProgress(String taskId, Map<String, Object> data);

    void sendComplete(String taskId, Map<String, Object> data);

    void sendError(String taskId, Map<String, Object> data);

    List<VehicleTemplate> selectVehicleTemplateOption();

    List<VehicleTemplate> historyVersion(VehicleTemplate template);

    // 提交任务，返回 taskId（内部创建 sink 并异步执行）
    String submitImportTask(MultipartFile file);

    // 根据 taskId 返回对应的 Flux（供 SSE 接口使用）
    Flux<ServerSentEvent<String>> getImportFlux(String taskId);

    List<VehicleTemplate> selectVehicleTemplateExpiringList();

    List<VehicleTemplate> selectVehicleTemplateEffectingList();

    List<Map<String, Object>> selectVehicleTemplateIdByCondition(String materialNo, String brand, String weight, String saleName, String tire, String tvv);

    Map<String, String> getTemplateParams();
}