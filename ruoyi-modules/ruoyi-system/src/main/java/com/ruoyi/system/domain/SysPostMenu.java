package com.ruoyi.system.domain;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class SysPostMenu implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long postId;

    private Long menuId;

    private List<Long> menuIds;

    public SysPostMenu() {}

    public SysPostMenu(Long postId, Long menuId) {
        this.postId = postId;
        this.menuId = menuId;
    }
}
