package com.ruoyi.vehicle.service;

import com.ruoyi.common.core.model.ValidationReport;
import com.ruoyi.vehicle.domain.VehicleTemplate;
import com.ruoyi.vehicle.domain.VehicleTemplateMaterial;
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

    List<VehicleTemplateMaterial> selectMaterialByTemplateId(Long templateId);

    int saveMaterialList(Long templateId, List<VehicleTemplateMaterial> materialList);

    Flux<ServerSentEvent<String>> importPdf(MultipartFile file);

    void sendProgress(String taskId, Map<String, Object> data);

    void sendComplete(String taskId, Map<String, Object> data);

    void sendError(String taskId, Map<String, Object> data);
}