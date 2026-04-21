package com.ruoyi.vehicle.mapper;

import com.ruoyi.vehicle.domain.vo.ChartDataXmlTotalVo;


/**
 * 统计图表
 */
public interface ChartDataMapper {
    // 主页XML汇总图表
    ChartDataXmlTotalVo selectXmlTotal();

    ChartDataXmlTotalVo selectXmlValidateTotal();
}