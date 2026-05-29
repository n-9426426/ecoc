package com.ruoyi.vehicle.domain.vo;

import lombok.Data;

@Data
public class VehicleJsonKeyVo {
    /** dict_data 表中的 other_label */
    private String otherLabel;

    /** dict_data 表中的 other_label_system */
    private String otherLabelSystem;

    /** dict_data 表中的 coc_order */
    private String cocOrder;

    /** JSON 中的原始键名 */
    private String jsonKey;

    public VehicleJsonKeyVo() {}

    public VehicleJsonKeyVo(String jsonKey, String otherLabel,
                            String otherLabelSystem, String cocOrder) {
        this.jsonKey = jsonKey;
        this.otherLabel = otherLabel;
        this.otherLabelSystem = otherLabelSystem;
        this.cocOrder = cocOrder;
    }
}
