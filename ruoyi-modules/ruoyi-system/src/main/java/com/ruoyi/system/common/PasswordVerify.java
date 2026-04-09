package com.ruoyi.system.common;

import com.ruoyi.common.core.exception.ServiceException;

public class PasswordVerify {

    public static void passwordVerify(String password) {
        int count = 0;
        if (password.matches(".*[a-z].*")) count++;
        if (password.matches(".*[A-Z].*")) count++;
        if (password.matches(".*\\d.*")) count++;
        if (password.matches(".*[^a-zA-Z0-9].*")) count++;

        if (count < 3) {
            throw new ServiceException("密码至少包含大写字母、小写字母、数字、特殊字符中的3种");
        }
    }
}
