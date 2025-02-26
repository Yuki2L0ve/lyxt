package com.tianji.learning.scheduleTask;

import com.tianji.common.utils.CollUtils;
import com.tianji.learning.constants.LearningConstants;
import com.tianji.learning.constants.RedisConstants;
import com.tianji.learning.domain.po.PointsBoard;
import com.tianji.learning.domain.po.PointsBoardSeason;
import com.tianji.learning.service.IPointsBoardSeasonService;
import com.tianji.learning.service.IPointsBoardService;
import com.tianji.learning.utils.TableInfoContext;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class PointsBoardPersistentHandler {
    private final IPointsBoardSeasonService pointsBoardSeasonService;
    private final IPointsBoardService pointsBoardService;
    private final StringRedisTemplate redisTemplate;


    /**
     * 创建上个赛季（上个月）的积分榜单表
     */
    // @Scheduled(cron = "0 0 3 1 * ?") // 单机版的定时任务调度 每月1号，凌晨3点执行
    @XxlJob("createTableJob")   // 采用xxl-job集群版的分布式定时任务调度
    public void createPointsBoardTableOfLastSeason(){
        log.info("创建上个赛季（上个月）的积分榜单表的SpringTask定时任务开始执行...");
        // 1. 获取上月时间
        LocalDate time = LocalDate.now().minusMonths(1);


        // 2. 查询赛季表获取赛季id
        PointsBoardSeason one = pointsBoardSeasonService.lambdaQuery()
                .le(PointsBoardSeason::getBeginTime, time)
                .ge(PointsBoardSeason::getEndTime, time)
                .one();
        log.info("上个赛季的赛季信息：{}", one);
        if (one == null) {
            log.error("未找到上个赛季的赛季信息，无法创建积分榜单表！");
            return ;
        }


        // 3. 创建表
        pointsBoardSeasonService.createPointsBoardLatestTable(one.getId());
    }


    /**
     * 持久化Redis中上个赛季（上个月）的积分榜单数据到MySQL数据库
     */
    @XxlJob("savePointsBoard2DB")
    public void savePointsBoard2DB(){
        log.info("持久化Redis中上个赛季（上个月）的积分榜单数据到MySQL数据库的SpringTask定时任务开始执行...");
        // 1. 获取上月时间
        LocalDate time = LocalDate.now().minusMonths(1);


        // 2. 查询赛季表points_board_season获取上赛季信息
        PointsBoardSeason one = pointsBoardSeasonService.lambdaQuery()
                .le(PointsBoardSeason::getBeginTime, time)
                .ge(PointsBoardSeason::getEndTime, time)
                .one();
        log.info("上个赛季的赛季信息：{}", one);
        if (one == null) {
            log.error("未找到上个赛季的赛季信息，无法创建积分榜单表！");
            return ;
        }


        // 3. 计算动态表名，并存入ThreadLocal
        String tableName = LearningConstants.POINTS_BOARD_TABLE_PREFIX + one.getId();
        log.info("动态表名：{}", tableName);
        TableInfoContext.setInfo(tableName);


        // 4. 分页获取Redis上赛季积分排行榜数据
        String format = time.format(DateTimeFormatter.ofPattern("yyyyMM"));
        String key = RedisConstants.POINTS_BOARD_KEY_PREFIX + format;   // key格式：boards:上赛季年月
        log.info("Redis中的key：{}", key);
        int shardIndex = XxlJobHelper.getShardIndex();  // 分片序号 从0开始
        int shardTotal = XxlJobHelper.getShardTotal();  // 分片总数（即机器数量）
        log.info("分片序号：{}，分片总数：{}", shardIndex, shardTotal);
        int pageNo = shardIndex + 1;       // 分片序号从0开始，所以要+1
        int pageSize = 1000;
        while (true) {
            log.info("开始处理第 {} 页数据", pageNo);
            List<PointsBoard> pointsBoardList = pointsBoardService.queryCurrentBoardList(key, pageNo, pageSize);
            if (CollUtils.isEmpty(pointsBoardList)) {
                break;
            }

            // 5. 持久化到MySQL数据库的相应赛季表中   批量新增
            for (PointsBoard pointsBoard : pointsBoardList) {
                pointsBoard.setId(Long.valueOf(pointsBoard.getRank())); // 历史赛季排行榜中id就代表了排名
                pointsBoard.setRank(null);
            }
            log.info("当前页数据：{}", pointsBoardList);
            boolean b = pointsBoardService.saveBatch(pointsBoardList);
            log.info("当前页数据持久化结果：{}", b);

            pageNo += shardTotal;   // 翻页，跳过N个页，N就是分片数量
        }


        // 6. 清除ThreadLocal
        TableInfoContext.remove();
    }


    /**
     * 清除Redis中上个赛季（上个月）的积分榜单数据
     * 当任务持久化以后，我们还要清理Redis中的上赛季的榜单数据，避免过多的内存占用。
     */
    @XxlJob("clearPointsBoardFromRedis")
    public void clearPointsBoardFromRedis(){
        // 1. 获取上月时间
        LocalDate time = LocalDate.now().minusMonths(1);

        // 2. 计算key
        String format = time.format(DateTimeFormatter.ofPattern("yyyyMM"));
        String key = RedisConstants.POINTS_BOARD_KEY_PREFIX + format;   // key格式：boards:上赛季年月


        // 3. 删除key
        redisTemplate.unlink(key);
    }
}
