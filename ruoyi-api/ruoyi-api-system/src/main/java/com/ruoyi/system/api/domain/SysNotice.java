package com.ruoyi.system.api.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ruoyi.common.core.web.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.util.List;

/**
 * 通知公告表 sys_notice
 * 
 * @author ruoyi
 */
@Data
@ToString
@EqualsAndHashCode(callSuper = true)
public class SysNotice extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 公告ID */
    private Long noticeId;

    /** 公告标题 */
    private String noticeTitle;

    /** 公告类型（1.通知 2.公告） */
    private String noticeType;

    /** 公告内容 */
    private String noticeContent;

    /** 公告状态（0正常 1关闭） */
    private String status;

    private String model;

    private String queryParams;

    /** 是否已读 */
    @JsonProperty("isRead")
    private boolean isRead;

    private Integer pageNum;

    private Integer pageSize;

    /** 关联岗位ID列表 */
    private List<Long> postIds;

    /** 关联角色ID列表 */
    private List<Long> roleIds;

    // 系统内部发送通知时，字典中dict_type="notice_group"中的排序
    private List<Integer> sorts;

    public boolean getIsRead() {
        return isRead;
    }

    public void setIsRead(boolean isRead) {
        this.isRead = isRead;
    }
}
