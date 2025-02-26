package com.tianji.learning.constants;

public interface RedisConstants {
    /**
     * 签到记录的key前缀: sign:uid:用户id:年月
     */
    String SIGN_RECORD_KEY_PREFIX = "sign:uid:";


    /**
     * 积分榜单的key前缀: boards:年月
     */
    String POINTS_BOARD_KEY_PREFIX = "boards:";
}
