package com.tianji.learning.service;

import com.tianji.learning.domain.po.PointsBoardSeason;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author lzj
 * @since 2025-02-06
 */
public interface IPointsBoardSeasonService extends IService<PointsBoardSeason> {
    /**
     * 根据赛季id来创建我们进行分表后的上赛季榜单表
     * @param id    赛季id
     */
    void createPointsBoardLatestTable(Integer id);
}
