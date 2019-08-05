package com.alibaba.easyexcel.test.core.exception;

import com.alibaba.excel.annotation.ExcelProperty;

import lombok.Data;

/**
 * @author zhuangjiaju
 */
@Data
public class ExceptionData {
    @ExcelProperty("姓名")
    private String name;
}
