package com.ruoyi.vehicle.domain;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 异常分类实体类
 * 对应表：abnormal_classify
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AbnormalClassify {

    /**
     * 主键ID
     */
    private Long id;

    /**
     * 条目ID
     */
    private String entryId;

    /**
     * 条目类型
     */
    private String entryType;

    /**
     * 规则类型
     */
    private String ruleType;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTimeStart;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTimeEnd;
}