package com.ruoyi.system.api.factory;

import com.ruoyi.common.core.domain.R;
import com.ruoyi.system.api.RemoteJobService;
import com.ruoyi.system.api.domain.SysJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class RemoteJobFallbackFactory implements FallbackFactory<RemoteJobService> {
    private static final Logger log = LoggerFactory.getLogger(RemoteJobFallbackFactory.class);


    @Override
    public RemoteJobService create(Throwable throwable) {
        log.error("日志服务调用失败:{}", throwable.getMessage());
        return new RemoteJobService() {

            @Override
            public R<SysJob> deleteName(Map<String, String> params, String source) {
                return R.fail(params.toString() + " 删除定时任务失败:" + throwable.getMessage());
            }

            @Override
            public R<SysJob> deleteJobByIdsConditions(Map<String, Object> params, String source) {
                return R.fail(params.toString() + " 删除定时任务{}失败:" + throwable.getMessage());
            }

            @Override
            public R<SysJob> createJob(SysJob job, String source) {
                return R.fail(job.getJobName() + " 删除定时任务{}失败:" + throwable.getMessage());
            }
        };
    }
}
