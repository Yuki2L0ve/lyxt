package com.tianji.learning.service.impl;

import com.tianji.api.client.user.UserClient;
import com.tianji.api.dto.user.UserDTO;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.StringUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.learning.constants.RedisConstants;
import com.tianji.learning.domain.po.PointsBoard;
import com.tianji.learning.domain.query.PointsBoardQuery;
import com.tianji.learning.domain.vo.PointsBoardItemVO;
import com.tianji.learning.domain.vo.PointsBoardVO;
import com.tianji.learning.mapper.PointsBoardMapper;
import com.tianji.learning.service.IPointsBoardService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * <p>
 * 学霸天梯榜 服务实现类
 * </p>
 *
 * @author lzj
 * @since 2025-02-06
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PointsBoardServiceImpl extends ServiceImpl<PointsBoardMapper, PointsBoard> implements IPointsBoardService {
    private final StringRedisTemplate redisTemplate;
    private final UserClient userClient;


    /**
     * 查询学霸积分榜-当前赛季和历史赛季都可用
     * @param query
     * @return
     */
    @Override
    public PointsBoardVO queryPointsBoardList(PointsBoardQuery query) {
        // 1. 获取当前登录用户id
        Long userId = UserContext.getUser();
        log.info("当前登录用户id：{}", userId);


        // 2. 判断时查询当前赛季还是历史赛季，可用根据season来判断，如果season为0或null，则查询当前赛季，否则查询历史赛季
        Long season = query.getSeason();
        boolean isCurrentSeason = season == null || season == 0;
        log.info("查询当前赛季还是历史赛季：{}", isCurrentSeason);


        // 3. Redis中的key
        LocalDate now = LocalDate.now();
        String format = now.format(DateTimeFormatter.ofPattern("yyyyMM"));
        String key = RedisConstants.POINTS_BOARD_KEY_PREFIX + format;
        log.info("Redis中的key：{}", key);


        // 4. 查询我的积分和排名     根据isCurrentSeason判断时从Redis中查询当前赛季还是从数据库中历史赛季
        PointsBoard myBoard = isCurrentSeason ? queryMyCurrentBoard(key) : queryMyHistoryBoard(season);
        log.info("查询我的积分和排名：{}", myBoard);


        // 5. 分页查询赛季列表  根据isCurrentSeason判断时从Redis中分页查询当前赛季积分排行榜列表还是从数据库中分页查询历史赛季积分排行榜列表
        List<PointsBoard> list = isCurrentSeason ?
                queryCurrentBoardList(key, query.getPageNo(), query.getPageSize()) :
                queryHistoryBoardList(query);


        // 6. 远程调用用户服务，获取学生信息
        Set<Long> uIds = list.stream().map(PointsBoard::getUserId).collect(Collectors.toSet());
        List<UserDTO> users = userClient.queryUserByIds(uIds);
        Map<Long, String> userMap = new HashMap<>(uIds.size());
        if (CollUtils.isNotEmpty(users)) {
            userMap = users.stream().collect(Collectors.toMap(UserDTO::getId, UserDTO::getName));
        }


        // 7. 封装成PointsBoardVO对象
        PointsBoardVO vo = new PointsBoardVO();

        // 7.1 设置我的排名
        vo.setRank(myBoard.getRank());  // 设置我的排名

        // 7. 2 设置我的积分
        vo.setPoints(myBoard.getPoints());    // 设置我的积分

        // 7.3 设置积分排行榜列表
        List<PointsBoardItemVO> voList = new ArrayList<>();
        for (PointsBoard board : list) {
            PointsBoardItemVO itemVO = new PointsBoardItemVO();
            itemVO.setName(userMap.get(board.getUserId()));   // 设置学生名字
            itemVO.setPoints(board.getPoints());    // 设置学生积分
            itemVO.setRank(board.getRank());        // 设置学生排名
            voList.add(itemVO);
        }
        vo.setBoardList(voList);    // 设置积分排行榜列表


        // 8. 返回PointsBoardVO对象
        return vo;
    }


    /**
     * 从数据库中分页查询历史赛季积分排行榜列表
     * @param query
     * @return
     */
    private List<PointsBoard> queryHistoryBoardList(PointsBoardQuery query) {
        // TODO 从数据库中分页查询历史赛季积分排行榜列表
        return null;
    }


    /**
     * 从Redis中分页查询当前赛季积分排行榜列表
     * @param key
     * @param pageNo
     * @param pageSize
     * @return
     */
    public List<PointsBoard> queryCurrentBoardList(String key, Integer pageNo, Integer pageSize) {
        // 1. 计算start和end索引
        int start = (pageNo - 1) * pageSize;
        int end = start + pageSize - 1;
        log.info("start: {}, end: {}", start, end);


        // 2. 从Redis中分页查询当前赛季积分排行榜列表
        Set<ZSetOperations.TypedTuple<String>> tuples = redisTemplate.opsForZSet()
                .reverseRangeWithScores(key, start, end);
        log.info("Redis中分页查询当前赛季积分排行榜列表：{}", tuples);


        // 3. 封装成List<PointsBoard>对象
        int rk = start + 1; // 排名   某一页开头的第一名必然是从start+1开始
        List<PointsBoard> list = new ArrayList<>();
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            String value = tuple.getValue();
            Double score = tuple.getScore();
            log.info("value: {}, score: {}", value, score);
            if (StringUtils.isBlank(value) || score == null) {
                continue;
            }
            PointsBoard board = new PointsBoard();
            board.setUserId(Long.valueOf(value));   // 设置userId
            board.setPoints(score.intValue());    // 设置points
            board.setRank(rk ++ );    // 设置rank
            list.add(board);
        }


        // 4. 返回List<PointsBoard>对象
        return list;
    }


    /**
     * 查询历史赛季我的积分和排名
     * @param season
     * @return
     */
    private PointsBoard queryMyHistoryBoard(Long season) {
        // TODO 查询历史赛季我的积分和排名
        return null;
    }


    /**
     * 查询当前赛季我的积分和排名
     * @param key
     * @return
     */
    private PointsBoard queryMyCurrentBoard(String key) {
        // 1. 获取当前登录用户id
        Long userId = UserContext.getUser();
        log.info("当前登录用户id：{}", userId);


        // 2. 从Redis中查询我的积分 和 排名
        Double score = redisTemplate.opsForZSet().score(key, userId.toString());
        Long rank = redisTemplate.opsForZSet().reverseRank(key, userId.toString());
        log.info("查询我的积分: {}, 排名: {}", score,rank);


        // 3. 封装成PointsBoard对象
        PointsBoard board = new PointsBoard();
        board.setUserId(userId);
        board.setRank(rank == null ? 0 : rank.intValue() + 1);
        board.setPoints(score == null ? 0 : score.intValue());


        // 4. 返回PointsBoard对象
        return board;
    }
}
