package com.tianji.learning.service;

import com.tianji.learning.domain.vo.SignResultVO;

public interface ISignRecordService {
    /**
     * 添加签到记录
     * @return
     */
    SignResultVO addSignRecords();

    /**
     * 查询签到记录
     * @return
     */
    Byte[] querySignRecords();
}
