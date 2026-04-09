package com.ruoyi.auth.service;

import com.ruoyi.common.core.constant.CacheConstants;
import com.ruoyi.common.core.constant.Constants;
import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.common.core.utils.StringUtils;
import com.ruoyi.common.redis.service.RedisService;
import com.ruoyi.common.security.utils.SecurityUtils;
import com.ruoyi.system.api.RemoteTranslateService;
import com.ruoyi.system.api.domain.SysUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 登录密码方法
 * 
 * @author ruoyi
 */
@Component
public class SysPasswordService
{
    @Autowired
    private RedisService redisService;

    private int maxRetryCount = CacheConstants.PASSWORD_MAX_RETRY_COUNT;

    private Long lockTime = CacheConstants.PASSWORD_LOCK_TIME;

    @Autowired
    private SysRecordLogService recordLogService;

    @Autowired
    private RemoteTranslateService remoteTranslateService;

    /**
     * 登录账户密码错误次数缓存键名
     * 
     * @param username 用户名
     * @return 缓存键key
     */
    private String getCacheKey(String username)
    {
        return CacheConstants.PWD_ERR_CNT_KEY + username;
    }

    public void validate(SysUser user, String password)
    {
        String username = user.getUserName();
        Long residueLockTime = redisService.getExpire(CacheConstants.USERNAME_LOCK_KEY + username);
        if (residueLockTime > 0) {
            throw new ServiceException(StringUtils.format(remoteTranslateService.translate("user.account.lock.remain.time", null), String.valueOf(residueLockTime)));
        }

        Integer retryCount = redisService.getCacheObject(getCacheKey(username));
        if (retryCount == null)
        {
            retryCount = 0;
        }

        if (retryCount >= maxRetryCount)
        {
            String errMsg = StringUtils.format(remoteTranslateService.translate("user.password.retry.limit.exceed", null), String.valueOf(maxRetryCount), String.valueOf(lockTime));;
            redisService.setCacheObject(CacheConstants.USERNAME_LOCK_KEY + username, lockTime, lockTime, TimeUnit.SECONDS);
            recordLogService.recordLogininfor(username, Constants.LOGIN_FAIL, errMsg);
            throw new ServiceException(errMsg);
        }

        if (!matches(user, password))
        {
            retryCount = retryCount + 1;
            recordLogService.recordLogininfor(username, Constants.LOGIN_FAIL, StringUtils.format(remoteTranslateService.translate("user.password.retry.count", null), String.valueOf(retryCount)));
            redisService.setCacheObject(getCacheKey(username), retryCount, lockTime, TimeUnit.SECONDS);
            throw new ServiceException(StringUtils.format(remoteTranslateService.translate("user.password.retry.count", null), String.valueOf(retryCount)));
        }
        else
        {
            clearLoginRecordCache(username);
        }
    }

    public boolean matches(SysUser user, String rawPassword)
    {
        return SecurityUtils.matchesPassword(rawPassword, user.getPassword());
    }

    public void clearLoginRecordCache(String loginName)
    {
        if (redisService.hasKey(getCacheKey(loginName)))
        {
            redisService.deleteObject(getCacheKey(loginName));
        }
    }
}
