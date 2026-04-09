package com.ruoyi.system.controller;

import com.ruoyi.common.core.web.controller.BaseController;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.core.web.page.TableDataInfo;
import com.ruoyi.common.log.annotation.Log;
import com.ruoyi.common.log.enums.BusinessType;
import com.ruoyi.common.security.annotation.RequiresPermissions;
import com.ruoyi.common.security.utils.SecurityUtils;
import com.ruoyi.system.api.model.LoginUser;
import com.ruoyi.system.domain.SysNotice;
import com.ruoyi.system.service.ISysNoticeReadService;
import com.ruoyi.system.service.ISysNoticeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 公告 信息操作处理
 * 
 * @author ruoyi
 */
@RestController
@RequestMapping("/notice")
public class SysNoticeController extends BaseController
{
    @Autowired
    private ISysNoticeService noticeService;

    @Autowired
    private ISysNoticeReadService noticeReadService;

    /**
     * 获取通知公告列表
     */
    @Operation(summary = "通知消息列表")
    @RequiresPermissions("system:notice:list")
    @GetMapping("/list")
    public TableDataInfo list(SysNotice notice)
    {
        startPage();
        List<SysNotice> list = noticeService.selectNoticeList(notice);
        return getDataTable(list);
    }

    /**
     * 根据通知公告编号获取详细信息
     */
    @Operation(summary = "通知消息详情")
    @RequiresPermissions("system:notice:query")
    @GetMapping(value = "/{noticeId}")
    public AjaxResult getInfo(@PathVariable Long noticeId)
    {
        return success(noticeService.selectNoticeById(noticeId));
    }

    /**
     * 新增通知公告
     */
    @Operation(summary = "新增通知消息")
    @RequiresPermissions("system:notice:add")
    @Log(title = "通知公告", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@Validated @RequestBody SysNotice notice)
    {
        notice.setCreateBy(SecurityUtils.getUsername());
        return toAjax(noticeService.insertNotice(notice));
    }

    /**
     * 修改通知公告
     */
    @Operation(summary = "修改通知公告")
    @RequiresPermissions("system:notice:edit")
    @Log(title = "通知公告", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@Validated @RequestBody SysNotice notice)
    {
        notice.setUpdateBy(SecurityUtils.getUsername());
        return toAjax(noticeService.updateNotice(notice));
    }

    /**
     * 首页顶部公告列表（返回全部正常公告，带当前用户已读标记，最多5条）
     */
    @Operation(summary = "首页顶部公告列表")
    @ApiResponse(description = "返回全部正常公告，带当前用户已读标记，最多5条")
    @GetMapping("/listTop")
    @ResponseBody
    public AjaxResult listTop()
    {
        Long userId = SecurityUtils.getUserId();
        List<SysNotice> list = noticeReadService.selectNoticeListWithReadStatus(userId, 10);
        long unreadCount = list.stream().filter(n -> !n.getIsRead()).count();
        AjaxResult result = AjaxResult.success(list);
        result.put("unreadCount", unreadCount);
        return result;
    }

    /**
     * 标记公告已读
     */
    @Operation(summary = "通知已读")
    @PostMapping("/markRead")
    @ResponseBody
    public AjaxResult markRead(@RequestParam Long noticeId)
    {
        Long userId = SecurityUtils.getUserId();
        noticeReadService.markRead(noticeId, userId);
        return success();
    }

    /**
     * 批量标记已读
     */
    @Operation(summary = "通知批量已读")
    @PostMapping("/markReadAll")
    @ResponseBody
    public AjaxResult markReadAll(Long[] noticeIds)
    {
        Long userId = SecurityUtils.getUserId();
        noticeReadService.markReadBatch(userId, noticeIds);
        return success();
    }

    /**
     * 删除通知公告
     */
    @Operation(summary = "删除通知")
    @RequiresPermissions("system:notice:remove")
    @Log(title = "通知公告", businessType = BusinessType.DELETE)
    @DeleteMapping("/{noticeIds}")
    public AjaxResult remove(@PathVariable Long[] noticeIds)
    {
        noticeReadService.deleteByNoticeIds(noticeIds);
        return toAjax(noticeService.deleteNoticeByIds(noticeIds));
    }

    private static final Map<String, Sinks.Many<ServerSentEvent<Object>>> sinks = new ConcurrentHashMap<>();

    @Operation(summary = "建立SSE单向通信连接")
    @RequiresPermissions("system:notice:query")
    @GetMapping(value = "/subscribe", produces =  MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> subscribe() {
        LoginUser loginUser = SecurityUtils.getLoginUser();
        String userId = loginUser.getUserid().toString();
        if (sinks.containsKey(userId)) {
            sinks.get(userId).tryEmitComplete();
            sinks.remove(userId);
        }
        Sinks.Many<ServerSentEvent<Object>> sink = Sinks
                .many()
                .multicast()
                .onBackpressureBuffer();

        sinks.put(userId, sink);

        return sink.asFlux()
                .mergeWith(Flux.interval(Duration.ofSeconds(30))
                        .map(i -> ServerSentEvent.builder()
                                .comment("heartbeat")
                                .build())
                )
                .doOnCancel(() -> {
                    sinks.remove(userId);
                    System.out.println("[SSE] 用户断开连接: " + userId);
                })
                .doOnComplete(() -> {
                    sinks.remove(userId);
                    System.out.println("[SSE] 连接完成: " + userId);
                })
                .doOnError(e -> {
                    sinks.remove(userId);
                    System.out.println("[SSE] 连接异常: " + userId + ", " + e.getMessage());
                });
    }

    public static void broadcast(Object data) {
        sinks.forEach((userId, sink) -> {
            ServerSentEvent<Object> event = ServerSentEvent.builder()
                    .data(data)
                    .event("message")
                    .id(String.valueOf(System.currentTimeMillis()))
                    .build();

            Sinks.EmitResult result = sink.tryEmitNext(event);
            if (result.isFailure()) {
                System.out.println("[SSE] 发送失败, userId: " + userId + ", result: " + result);
                sinks.remove(userId);
            }
        });
    }
}
