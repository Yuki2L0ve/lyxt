package com.tianji.learning.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tianji.api.client.remark.RemarkClient;
import com.tianji.api.client.user.UserClient;
import com.tianji.api.dto.user.UserDTO;
import com.tianji.common.autoconfigure.mq.RabbitMqHelper;
import com.tianji.common.constants.MqConstants;
import com.tianji.common.domain.dto.PageDTO;
import com.tianji.common.exceptions.BizIllegalException;
import com.tianji.common.utils.BeanUtils;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.learning.domain.dto.ReplyDTO;
import com.tianji.learning.domain.po.InteractionQuestion;
import com.tianji.learning.domain.po.InteractionReply;
import com.tianji.learning.domain.query.ReplyPageQuery;
import com.tianji.learning.domain.vo.ReplyVO;
import com.tianji.learning.enums.QuestionStatus;
import com.tianji.learning.mapper.InteractionQuestionMapper;
import com.tianji.learning.mapper.InteractionReplyMapper;
import com.tianji.learning.service.IInteractionReplyService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static com.tianji.common.constants.Constant.DATA_FIELD_NAME_CREATE_TIME;
import static com.tianji.common.constants.Constant.DATA_FIELD_NAME_LIKED_TIME;

/**
 * <p>
 * 互动问题的回答或评论 服务实现类
 * </p>
 *
 * @author lzj
 * @since 2025-02-04
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InteractionReplyServiceImpl extends ServiceImpl<InteractionReplyMapper, InteractionReply> implements IInteractionReplyService {
    private final InteractionQuestionMapper questionMapper;
    private final UserClient userClient;
    private final RemarkClient remarkClient;
    private final RabbitMqHelper rabbitMqHelper;


    /**
     * 用户端新增互动问题的回答或评论
     * @param replyDTO
     */
    @Override
    public void saveReply(ReplyDTO replyDTO) {
        // 1. 获取当前登录用户
        Long userId = UserContext.getUser();
        log.info("当前登录用户id: {}", userId);

        // 2. 新增互动问题的回答或评论, 保存到interaction_reply表中
        InteractionReply reply = BeanUtils.copyBean(replyDTO, InteractionReply.class);
        reply.setUserId(userId);
        this.save(reply);

        // 3. 根据answer_id判断是否是回答, 为空则表示是回答，不为空则表示是评论
        // 3.1 如果是评论, 则累加interaction_reply表中的reply_times字段
        // 3.2 如果是回答, 则更新interaction_question表中的latest_answer_id字段, 然后累加interaction_question表中的answer_times字段

        // 4. 根据isStudent判断是否为学生提交，为true则表示是学生提交，为false则表示是老师提交
        // 4.1 如果是学生提交，则修改interaction_question表中的status字段为0, 表示未查看

        boolean isAnswer = replyDTO.getAnswerId() == null;  // 判断是回答还是评论
        if (!isAnswer) {    // 说明是评论
            lambdaUpdate()
                    .setSql("reply_times = reply_times + 1")    // 累加interaction_reply表中的reply_times字段
                    .eq(InteractionReply::getId, replyDTO.getAnswerId())
                    .update();
        }

        UpdateWrapper<InteractionQuestion> wrapper = new UpdateWrapper<>();
        wrapper.set(isAnswer, "latest_answer_id", reply.getId()) // 是回答，更新interaction_question表中的latest_answer_id字段
                .setSql(isAnswer, "answer_times = answer_times + 1") // 是回答，累加interaction_question表中的answer_times字段
                .set(replyDTO.getIsStudent(), "status", QuestionStatus.UN_CHECK) // 更新 status 字段
                .eq("id", replyDTO.getQuestionId()); // 条件：id = replyDTO.getQuestionId()

        questionMapper.update(null, wrapper);


        // 5. 尝试累加积分
        if (replyDTO.getIsStudent()) {  // 学生才需要累加积分
            rabbitMqHelper.send(
                    MqConstants.Exchange.LEARNING_EXCHANGE,
                    MqConstants.Key.WRITE_REPLY,
                    5);
        }
    }


    /**
     * 用户端分页查询互动问题的回答或评论
     * @param query
     * @return
     */
    @Override
    public PageDTO<ReplyVO> queryReplyPage(ReplyPageQuery query) {
        // 1. 问题id和回答id至少要有一个，先做参数判断
        Long questionId = query.getQuestionId();
        Long answerId = query.getAnswerId();
        log.info("问题id: {}, 回答id: {}", questionId, answerId);
        if (questionId == null && answerId == null) {
            log.error("问题id和回答id至少要有一个");
            throw new BizIllegalException("问题或回答id不能都为空");
        }

        // 2. 分页查询reply
        Page<InteractionReply> page = this.lambdaQuery()
                .eq(questionId != null, InteractionReply::getQuestionId, questionId)
                .eq(InteractionReply::getAnswerId, answerId != null ? answerId : 0L)
                .eq(InteractionReply::getHidden, false)
                .page(query.toMpPage( // 先根据点赞数排序，点赞数相同，再按照创建时间排序
                        new OrderItem(DATA_FIELD_NAME_LIKED_TIME, false),
                        new OrderItem(DATA_FIELD_NAME_CREATE_TIME, true))
                );
        List<InteractionReply> records = page.getRecords();
        if (CollUtils.isEmpty(records)) {
            log.info("查询结果为空");
            return PageDTO.empty(page);
        }


        // 3. 数据处理，需要查询：提问者信息、回复目标信息、当前用户是否点赞
        Set<Long> userIds = new HashSet<>();    // 用户id集合
        Set<Long> targetReplyIds = new HashSet<>(); // 回复的目标id集合
        Set<Long> answerIds = new HashSet<>(); // 回答或评论id集合

        // 3.1. 获取提问者id 、回复的目标id、当前回答或评论id（统计点赞信息）
        for (InteractionReply record : records) {
            if (!record.getAnonymity()) {    // 非匿名用户
                userIds.add(record.getUserId());
                userIds.add(record.getTargetUserId());
            }
            if (record.getTargetReplyId() != null && record.getTargetReplyId() > 0) {
                targetReplyIds.add(record.getTargetReplyId());
            }
            answerIds.add(record.getId());
        }

        // 3.2. 查询目标回复，如果目标回复不是匿名，则需要查询出目标回复的用户信息
        targetReplyIds.remove(0L);
        targetReplyIds.remove(null);
        if (targetReplyIds.size() > 0) {
            List<InteractionReply> targetReplies = this.listByIds(targetReplyIds);
            Set<Long> targetUserIds = targetReplies.stream()
                    .filter(Predicate.not(InteractionReply::getAnonymity))
                    .map(InteractionReply::getUserId)
                    .collect(Collectors.toSet());
            userIds.addAll(targetUserIds);
        }

        // 3.3. 查询用户
        Map<Long, UserDTO> userMap = new HashMap<>(userIds.size());
        if (userIds.size() > 0) {
            // 远程调用用户服务，查询用户信息
            List<UserDTO> users = userClient.queryUserByIds(userIds);
            userMap = users.stream().collect(Collectors.toMap(UserDTO::getId, u -> u));
        }


        // 3.4 查询用户点赞状态
        Set<Long> bizLiked = remarkClient.isBizLiked(answerIds.stream().collect(Collectors.toList()));

        // 4. 处理VO
        List<ReplyVO> voList = new ArrayList<>(records.size());
        for (InteractionReply record : records) {
            // 4.1. 拷贝基础属性
            ReplyVO vo = BeanUtils.toBean(record, ReplyVO.class);

            // 4.2. 回复人信息
            if (!record.getAnonymity()) {
                UserDTO userDTO = userMap.get(record.getUserId());
                if (userDTO != null) {
                    vo.setUserIcon(userDTO.getIcon());  // 设置用户头像
                    vo.setUserName(userDTO.getName());  // 设置用户名
                    vo.setUserType(userDTO.getType());  // 设置用户类型
                }
            }

            // 4.3. 如果存在评论的目标，则需要设置目标用户信息
            if (record.getTargetReplyId() != null) {
                UserDTO targetUser = userMap.get(record.getTargetUserId());
                if (targetUser != null) {
                    vo.setTargetUserName(targetUser.getName());  // 设置目标用户名
                }
            }

            // 4.4. 点赞状态
            vo.setLiked(bizLiked.contains(record.getId()));

            voList.add(vo); // 添加到VO列表中
        }

        return PageDTO.of(page, voList);
    }


    /**
     * 管理端分页查询互动问题的回答或评论
     * 与用户端的区别主要有两点：
     * （1）- 管理端在统计评论数量的时候，被隐藏的评论也要统计（用户端不统计隐藏回答）
     * （2）- 管理端无视匿名，所有评论都要返回用户信息；用户端匿名评论不返回用户信息。
     * @param query
     * @return
     */
    @Override
    public PageDTO<ReplyVO> queryReplyPageAdmin(ReplyPageQuery query) {
        // 1. 问题id和回答id至少要有一个，先做参数判断
        Long questionId = query.getQuestionId();
        Long answerId = query.getAnswerId();
        log.info("问题id: {}, 回答id: {}", questionId, answerId);
        if (questionId == null && answerId == null) {
            log.error("问题id和回答id至少要有一个");
            throw new BizIllegalException("问题或回答id不能都为空");
        }

        // 2. 分页查询reply
        Page<InteractionReply> page = this.lambdaQuery()
                .eq(questionId != null, InteractionReply::getQuestionId, questionId)
                .eq(InteractionReply::getAnswerId, answerId != null ? answerId : 0L)
                .page(query.toMpPage( // 先根据点赞数排序，点赞数相同，再按照创建时间排序
                        new OrderItem(DATA_FIELD_NAME_LIKED_TIME, false),
                        new OrderItem(DATA_FIELD_NAME_CREATE_TIME, true))
                );
        List<InteractionReply> records = page.getRecords();
        if (CollUtils.isEmpty(records)) {
            log.info("查询结果为空");
            return PageDTO.empty(page);
        }


        // 3. 数据处理，需要查询：提问者信息、回复目标信息、当前用户是否点赞
        Set<Long> userIds = new HashSet<>();    // 用户id集合
        Set<Long> targetReplyIds = new HashSet<>(); // 回复的目标id集合
        Set<Long> answerIds = new HashSet<>(); // 回答或评论id集合

        // 3.1. 获取提问者id 、回复的目标id、当前回答或评论id（统计点赞信息）
        for (InteractionReply record : records) {
            userIds.add(record.getUserId());
            userIds.add(record.getTargetUserId());
            if (record.getTargetReplyId() != null && record.getTargetReplyId() > 0) {
                targetReplyIds.add(record.getTargetReplyId());
            }
            answerIds.add(record.getId());
        }

        // 3.2. 查询目标回复，如果目标回复不是匿名，则需要查询出目标回复的用户信息
        targetReplyIds.remove(0L);
        targetReplyIds.remove(null);
        if (targetReplyIds.size() > 0) {
            List<InteractionReply> targetReplies = this.listByIds(targetReplyIds);
            Set<Long> targetUserIds = targetReplies.stream()
                    .map(InteractionReply::getUserId)
                    .collect(Collectors.toSet());
            userIds.addAll(targetUserIds);
        }

        // 3.3. 查询用户
        Map<Long, UserDTO> userMap = new HashMap<>(userIds.size());
        if (userIds.size() > 0) {
            // 远程调用用户服务，查询用户信息
            List<UserDTO> users = userClient.queryUserByIds(userIds);
            userMap = users.stream().collect(Collectors.toMap(UserDTO::getId, u -> u));
        }

        // 3.4 查询用户点赞状态
        Set<Long> bizLiked = remarkClient.isBizLiked(answerIds.stream().collect(Collectors.toList()));


        // 4. 处理VO
        List<ReplyVO> voList = new ArrayList<>(records.size());
        for (InteractionReply record : records) {
            // 4.1. 拷贝基础属性
            ReplyVO vo = BeanUtils.toBean(record, ReplyVO.class);

            // 4.2. 回复人信息
            UserDTO userDTO = userMap.get(record.getUserId());
            if (userDTO != null) {
                vo.setUserIcon(userDTO.getIcon());  // 设置用户头像
                vo.setUserName(userDTO.getName());  // 设置用户名
                vo.setUserType(userDTO.getType());  // 设置用户类型
            }

            // 4.3. 如果存在评论的目标，则需要设置目标用户信息
            if (record.getTargetReplyId() != null) {
                UserDTO targetUser = userMap.get(record.getTargetUserId());
                if (targetUser != null) {
                    vo.setTargetUserName(targetUser.getName());  // 设置目标用户名
                }
            }

            // 4.4. 点赞状态
            vo.setLiked(bizLiked.contains(record.getId()));

            voList.add(vo); // 添加到VO列表中
        }

        return PageDTO.of(page, voList);
    }


    /**
     * 管理端隐藏互动问题的回答或评论
     * @param id
     * @param hidden
     */
    @Override
    public void hiddenReply(Long id, Boolean hidden) {
        log.info("隐藏互动问题的回答或评论, id: {}, hidden: {}", id, hidden);

        // 1. 查询interaction_reply表
        InteractionReply old = this.getById(id);
        if (old == null) {
            log.error("互动问题的回答或评论不存在, id: {}", id);
            throw new BizIllegalException("互动问题的回答或评论不存在");
        }


        // 2. 隐藏回答
        InteractionReply reply = new InteractionReply();
        reply.setId(id);
        reply.setHidden(hidden);
        this.updateById(reply);


        // 3. 隐藏评论，先判断是否是回答，回答才需要隐藏下属评论
        if (old.getAnswerId() != null && old.getAnswerId() != 0) {
            return ; // 3.1 有answerId，说明自己是评论，无需处理
        }

        // 3.2 没有answerId，说明自己是回答，需要隐藏回答下的评论
        this.lambdaUpdate()
                .set(InteractionReply::getHidden, hidden)
                .eq(InteractionReply::getAnswerId, id)
                .update();
    }


    /**
     * 管理端根据id查询回答或评论
     * @param id
     * @return
     */
    @Override
    public ReplyVO queryReplyById(Long id) {
        // 1. 根据id查询
        InteractionReply reply = this.getById(id);


        // 2. 数据处理，需要查询用户信息、评论目标信息、当前用户是否点赞
        Set<Long> userIds = new HashSet<>();

        // 2.1. 获取用户id
        userIds.add(reply.getUserId());

        // 2.2. 查询评论目标，如果评论目标不是匿名，则需要查询出目标回复的用户id
        if (reply.getTargetReplyId() != null && reply.getTargetReplyId() != 0) {
            InteractionReply target = this.getById(reply.getTargetReplyId());
            if (!target.getAnonymity()) {
                userIds.add(target.getUserId());
            }
        }

        // 2.3. 查询用户详细
        Map<Long, UserDTO> userMap = new HashMap<>(userIds.size());
        if (userIds.size() > 0) {
            // 远程调用用户服务，查询用户信息
            List<UserDTO> users = userClient.queryUserByIds(userIds);
            userMap = users.stream().collect(Collectors.toMap(UserDTO::getId, u -> u));
        }


        // 2.4 查询用户点赞状态
        Set<Long> bizLiked = remarkClient.isBizLiked(Collections.singletonList(id));

        // 3.处理VO
        // 3.1. 拷贝基础属性
        ReplyVO vo = BeanUtils.toBean(reply, ReplyVO.class);

        // 3.2. 回复人信息
        UserDTO userDTO = userMap.get(reply.getUserId());
        if (userDTO != null) {
            vo.setUserIcon(userDTO.getIcon());   // 设置用户头像
            vo.setUserName(userDTO.getName());   // 设置用户名
            vo.setUserType(userDTO.getType());   // 设置用户类型
        }

        // 3.3. 目标用户
        UserDTO targetUser = userMap.get(reply.getTargetUserId());
        if (targetUser != null) {
            vo.setTargetUserName(targetUser.getName());   // 设置目标用户名
        }

        // 3.4. 点赞状态
        vo.setLiked(bizLiked.contains(id));

        return vo;
    }
}