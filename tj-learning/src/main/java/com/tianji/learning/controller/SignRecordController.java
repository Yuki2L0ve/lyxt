package com.tianji.learning.controller;


import com.tianji.learning.domain.vo.SignResultVO;
import com.tianji.learning.service.ISignRecordService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 签到相关接口
 */
@Api(tags = "签到相关接口")
@RestController
@RequiredArgsConstructor
@RequestMapping("/sign-records")
public class SignRecordController {
    private final ISignRecordService signRecordService;


    @ApiOperation(value = "添加签到记录")
    @PostMapping
    public SignResultVO addSignRecords() {
        return signRecordService.addSignRecords();
    }


    @ApiOperation(value = "查询签到记录")
    @GetMapping
    public Byte[] querySignRecords() {
        return signRecordService.querySignRecords();
    }
}
