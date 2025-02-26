package com.tianji.learning.service.impl;

import com.tianji.learning.constants.LearningConstants;
import com.tianji.learning.domain.po.PointsBoardSeason;
import com.tianji.learning.mapper.PointsBoardSeasonMapper;
import com.tianji.learning.service.IPointsBoardSeasonService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author lzj
 * @since 2025-02-06
 */
@Slf4j
@Service
public class PointsBoardSeasonServiceImpl extends ServiceImpl<PointsBoardSeasonMapper, PointsBoardSeason> implements IPointsBoardSeasonService {
    /**
     * 根据赛季id来创建我们进行分表后的上赛季榜单表
     * @param id    赛季id
     */
    @Override
    public void createPointsBoardLatestTable(Integer id) {
        log.info("创建分表后的上赛季榜单表，赛季id：{}", id);
        getBaseMapper().createPointsBoardLatestTable(LearningConstants.POINTS_BOARD_TABLE_PREFIX + id);
    }
}
