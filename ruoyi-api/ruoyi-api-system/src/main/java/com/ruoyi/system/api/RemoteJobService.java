package com.ruoyi.system.api;

import com.ruoyi.common.core.constant.SecurityConstants;
import com.ruoyi.common.core.constant.ServiceNameConstants;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.system.api.domain.SysJob;
import com.ruoyi.system.api.factory.RemoteJobFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@FeignClient(contextId = "remoteJobService", value = ServiceNameConstants.JOB_SERVICE, fallbackFactory = RemoteJobFallbackFactory.class)
public interface RemoteJobService {

    @DeleteMapping("/job/delete-name")
    public R<SysJob> deleteName(@RequestParam("params") Map<String, String> params, @RequestHeader(SecurityConstants.FROM_SOURCE) String source);

    @DeleteMapping("/job/delete-ids")
    public R<SysJob> deleteJobByIdsConditions(@RequestParam("params") Map<String, Object> params, @RequestHeader(SecurityConstants.FROM_SOURCE) String source);

    @PostMapping("/job/add")
    public R<SysJob> createJob(@RequestBody SysJob job, @RequestHeader(SecurityConstants.FROM_SOURCE) String source);
}
