package com.ruoyi.vehicle.utils;

public interface EuFieldCleaner {

    /**
     * 清洗单个字段
     */
    Object clean(String dictLabel, Object rawValue);
}
