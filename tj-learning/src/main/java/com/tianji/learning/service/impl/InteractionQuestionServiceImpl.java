package com.tianji.learning.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tianji.api.cache.CategoryCache;
import com.tianji.api.client.course.CatalogueClient;
import com.tianji.api.client.course.CourseClient;
import com.tianji.api.client.search.SearchClient;
import com.tianji.api.client.user.UserClient;
import com.tianji.api.dto.course.CataSimpleInfoDTO;
import com.tianji.api.dto.course.CourseFullInfoDTO;
import com.tianji.api.dto.course.CourseSimpleInfoDTO;
import com.tianji.api.dto.user.UserDTO;
import com.tianji.common.domain.dto.PageDTO;
import com.tianji.common.exceptions.BizIllegalException;
import com.tianji.common.utils.BeanUtils;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.StringUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.learning.domain.dto.QuestionFormDTO;
import com.tianji.learning.domain.po.InteractionQuestion;
import com.tianji.learning.domain.po.InteractionReply;
import com.tianji.learning.domain.query.QuestionAdminPageQuery;
import com.tianji.learning.domain.query.QuestionPageQuery;
import com.tianji.learning.domain.vo.QuestionAdminVO;
import com.tianji.learning.domain.vo.QuestionVO;
import com.tianji.learning.enums.QuestionStatus;
import com.tianji.learning.mapper.InteractionQuestionMapper;
import com.tianji.learning.mapper.InteractionReplyMapper;
import com.tianji.learning.service.IInteractionQuestionService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.learning.service.IInteractionReplyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * <p>
 * 互动提问的问题表 服务实现类
 * </p>
 *
 * @author lzj
 * @since 2025-02-04
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class InteractionQuestionServiceImpl extends ServiceImpl<InteractionQuestionMapper, InteractionQuestion> implements IInteractionQuestionService {
    private final IInteractionReplyService replyService;
    private final UserClient userClient;
    private final InteractionReplyMapper replyMapper;
    private final SearchClient searchClient;
    private final CourseClient courseClient;
    private final CatalogueClient catalogueClient;
    private final CategoryCache categoryCache;


    /**
     * 新增互动问题
     * @param questionFormDTO
     */
    @Override
    public void saveQuestion(QuestionFormDTO questionFormDTO) {
        // 1. 获取登录用户
        Long userId = UserContext.getUser();
        log.info("新增提问，用户id：{}", userId);

        // 2. 数据转换
        InteractionQuestion question = BeanUtils.copyBean(questionFormDTO, InteractionQuestion.class);

        // 3. 补充设置用户id
        question.setUserId(userId);

        // 4. 保存问题
        this.save(question);
    }


    /**
     * 修改互动问题
     * @param id
     * @param questionFormDTO
     */
    @Override
    public void updateQuestion(Long id, QuestionFormDTO questionFormDTO) {
        if (StringUtils.isBlank(questionFormDTO.getTitle()) || StringUtils.isBlank(questionFormDTO.getDescription())
                || questionFormDTO.getAnonymity() == null) {
            log.error("修改提问失败，参数不完整：{}", questionFormDTO);
            throw new IllegalArgumentException("参数不完整");
        }

        // 1. 获取登录用户
        Long userId = UserContext.getUser();
        log.info("修改提问，用户id：{}", userId);

        // 2. 根据问题id，查询当前互动问题
        InteractionQuestion q = this.getById(id);
        if (q == null) {
            log.error("修改提问失败，提问不存在，问题id：{}", id);
            throw new IllegalArgumentException("提问不存在");
        }

        // 3. 判断是否为当前用户的问题
        if (!q.getUserId().equals(userId)) {
            log.error("修改提问失败，不是当前用户的问题，无权修改他人的问题，问题id：{}", id);
            throw new IllegalArgumentException("不是当前用户的问题，无权修改他人的问题");
        }

        // 4. 修改问题
        InteractionQuestion question  = BeanUtils.copyBean(questionFormDTO, InteractionQuestion.class);
        question.setId(id); // 补充设置用户id
        this.updateById(question);
    }


    /**
     * 用户端分页查询互动问题
     * @param query
     * @return
     */
    @Override
    public PageDTO<QuestionVO> queryQuestionPage(QuestionPageQuery query) {
        // 1. 参数校验，课程id和小节id不能都为空
        Long courseId = query.getCourseId();
        Long sectionId = query.getSectionId();
        if (courseId == null && sectionId == null) {
            log.error("分页查询提问失败，课程id和小节id不能都为空");
            throw new IllegalArgumentException("课程id和小节id不能都为空");
        }


        // 2.分页查询
        /**
         * SELECT id, user_id, course_id, section_id, hidden, create_time, ...
         * FROM interaction_question
         * WHERE user_id = <当前用户的ID>
         *   AND course_id = <指定的课程ID>
         *   AND section_id = <指定的章节ID>
         *   AND hidden = false
         * ORDER BY create_time DESC
         * LIMIT <offset>, <pageSize>;
         */
        Page<InteractionQuestion> page = lambdaQuery()
                .select(InteractionQuestion.class, info -> !info.getProperty().equals("description"))   // 排除description字段
                .eq(query.getOnlyMine(), InteractionQuestion::getUserId, UserContext.getUser())
                .eq(courseId != null, InteractionQuestion::getCourseId, courseId)
                .eq(sectionId != null, InteractionQuestion::getSectionId, sectionId)
                .eq(InteractionQuestion::getHidden, false)
                .page(query.toMpPageDefaultSortByCreateTimeDesc());
        List<InteractionQuestion> records = page.getRecords();
        if (CollUtils.isEmpty(records)) {
            log.info("分页查询提问，没有数据，查询条件：{}", query);
            return PageDTO.empty(page);
        }


        // 3. 根据id查询提问者和最近一次回答的信息
        Set<Long> userIds = new HashSet<>();    // 互动问题的用户id集合
        Set<Long> latestAnswerIds  = new HashSet<>();   // 互动问题的最新回答id集合

        // 3.1 得到问题当中的提问者id和最近一次回答的id
        for (InteractionQuestion q : records) {
            if (!q.getAnonymity()) {
                userIds.add(q.getUserId());
            }
            if (q.getLatestAnswerId() != null) {
                latestAnswerIds.add(q.getLatestAnswerId());
            }
        }

        // 3.2 根据最新回答id去Interaction_reply表中查询最近一次回答
        latestAnswerIds.remove(null);
        Map<Long, InteractionReply> replyMap = new HashMap<>(latestAnswerIds.size());   // <最新回答id, 最新回答>
        if (!CollUtils.isEmpty(latestAnswerIds)) {
            List<InteractionReply> replyList = replyService.list(Wrappers.<InteractionReply>lambdaQuery()
                    .in(InteractionReply::getId, latestAnswerIds)
                    .eq(InteractionReply::getHidden, false));   // 过滤掉隐藏的回答
            for (InteractionReply reply : replyList) {
                if (!reply.getAnonymity()) {
                    userIds.add(reply.getUserId()); // 将最新回答的用户id加入userIds集合
                }
                replyMap.put(reply.getId(), reply);
            }
        }

        // 3.3 根据id查询用户信息（提问者）
        userIds.remove(null);
        Map<Long, UserDTO> userMap = new HashMap<>(userIds.size()); // <用户id, 用户信息>
        if (!CollUtils.isEmpty(userIds)) {
            // 远程调用用户服务，批量查询用户信息
            List<UserDTO> userDTOList = userClient.queryUserByIds(userIds);
            userMap = userDTOList.stream()
                    .collect(Collectors.toMap(UserDTO::getId, u -> u));
        }

        // 4. 组装VO返回结果
        List<QuestionVO> voList = new ArrayList<>(records.size());
        for (InteractionQuestion record : records) {
            // 4.1 将PO转为VO
            QuestionVO vo = BeanUtils.copyBean(record, QuestionVO.class);

            // 4.2 封装提问者信息
            if (!record.getAnonymity()) {
                UserDTO userDTO = userMap.get(record.getUserId());
                if (userDTO != null) {
                    vo.setUserName(userDTO.getName());  // 封装提问者姓名
                    vo.setUserIcon(userDTO.getIcon());  // 封装提问者头像
                }
            }

            // 4.3 封装最近一次回答的信息
            InteractionReply reply = replyMap.get(record.getLatestAnswerId());
            if (reply != null) {
                if (!reply.getAnonymity()) {
                    UserDTO userDTO = userMap.get(reply.getUserId());
                    if (userDTO != null) {
                        vo.setLatestReplyUser(userDTO.getName());   // 封装最新回答者的昵称
                    }
                }
                vo.setLatestReplyContent(reply.getContent());  // 封装最新回答的内容
            }

            // 4.4 封装VO
            voList.add(vo);
        }

        return PageDTO.of(page, voList);
    }

    /**
     * 用户端根据问题id查询互动问题
     * @param id
     * @return
     */
    @Override
    public QuestionVO queryQuestionById(Long id) {
        // 1. 参数校验
        if (id == null) {
            log.error("查询提问失败，问题id不能为空");
            throw new IllegalArgumentException("问题id不能为空");
        }

        // 2. 根据问题id查询问题
        InteractionQuestion question = this.getById(id);
        if (question == null) {
            log.error("查询提问失败，提问不存在，问题id：{}", id);
            throw new IllegalArgumentException("提问不存在");
        }

        // 3. 如果该问题被隐藏，则不允许查看，直接返回null
        if (question.getHidden()) {
            log.error("查询提问失败，提问已被隐藏，问题id：{}", id);
            return null;
        }

        // 4. 组装VO返回结果
        QuestionVO vo = BeanUtils.copyBean(question, QuestionVO.class);

        // 5. 如果提问者是匿名用户，则不查询用户信息
        if (!question.getAnonymity()) {
            // 远程调用用户服务，查询用户信息
            UserDTO userDTO = userClient.queryUserById(question.getUserId());
            if (userDTO != null) {
                vo.setUserName(userDTO.getName());  // 封装提问者姓名
                vo.setUserIcon(userDTO.getIcon());  // 封装提问者头像
            }
        }

        return vo;
    }


    /**
     * 用户端根据问题id删除互动问题
     * @param id
     */
    @Override
    public void deleteQuestionById(Long id) {
        // 1. 参数校验
        if (id == null) {
            log.error("删除提问失败，问题id不能为空");
            throw new IllegalArgumentException("问题id不能为空");
        }

        // 2. 根据问题id查询问题
        InteractionQuestion question = this.getById(id);
        if (question == null) {
            log.error("删除提问失败，提问不存在，问题id：{}", id);
            throw new IllegalArgumentException("提问不存在");
        }

        // 3. 判断是否为当前用户的问题
        Long userId = UserContext.getUser();
        if (!question.getUserId().equals(userId)) {
            log.error("删除提问失败，不是当前用户的问题，无权删除他人的问题，问题id：{}", id);
            throw new IllegalArgumentException("不是当前用户的问题，无权删除他人的问题");
        }

        // 4. 删除问题
        this.removeById(id);

        // 5. 删除问题下的所有回答和评论
        replyMapper.delete(
                new QueryWrapper<InteractionReply>().lambda().eq(InteractionReply::getQuestionId, id)
        );
    }


    /**
     * 管理端分页查询互动问题
     * @param query
     * @return
     */
    @Override
    public PageDTO<QuestionAdminVO> queryQuestionPageAdmin(QuestionAdminPageQuery query) {
        // 1. 处理课程名称，得到课程id
        List<Long> courseIds = null;
        if (StringUtils.isNotBlank(query.getCourseName())) {
            // 远程调用搜索服务，根据课程名称查询出课程id
            courseIds = searchClient.queryCoursesIdByName(query.getCourseName());
            if (CollUtils.isEmpty(courseIds)) {
                log.info("没有查询到课程，查询条件：{}", query);
                return PageDTO.empty(0L, 0L);
            }
        }


        // 2. 分页查询
        Integer status = query.getStatus();
        LocalDateTime beginTime = query.getBeginTime();
        LocalDateTime endTime = query.getEndTime();
        Page<InteractionQuestion> page = lambdaQuery()
                .in(courseIds != null, InteractionQuestion::getCourseId, courseIds)
                .eq(status != null, InteractionQuestion::getStatus, status)
                .gt(beginTime != null, InteractionQuestion::getCreateTime, beginTime)
                .lt(endTime != null, InteractionQuestion::getCreateTime, endTime)
                .page(query.toMpPageDefaultSortByCreateTimeDesc());
        List<InteractionQuestion> records = page.getRecords();
        if (CollUtils.isEmpty(records)) {
            log.info("管理端分页查询互动问题，没有数据，查询条件：{}", query);
            return PageDTO.empty(page);
        }


        // 3. 准备VO需要的数据：用户数据、课程数据、章节数据
        Set<Long> userIds = new HashSet<>();    // 互动问题的用户id集合
        Set<Long> cIds = new HashSet<>();   // 互动问题的课程id集合
        Set<Long> cataIds = new HashSet<>();   // 互动问题的章节id集合
        // 3.1. 获取各种数据的id集合
        for (InteractionQuestion record : records) {
            userIds.add(record.getUserId());
            cIds.add(record.getCourseId());
            cataIds.add(record.getSectionId());
        }

        // 3.2. 远程调用用户服务，根据id查询用户
        List<UserDTO> userDTOS = userClient.queryUserByIds(userIds);
        Map<Long, UserDTO> userMap = new HashMap<>(userDTOS.size());
        if (!CollUtils.isEmpty(userDTOS)) {
            userMap = userDTOS.stream().collect(Collectors.toMap(UserDTO::getId, u -> u));
        }

        // 3.3 远程调用课程服务，根据id查询课程
        List<CourseSimpleInfoDTO> cInfos = courseClient.getSimpleInfoList(cIds);
        Map<Long, CourseSimpleInfoDTO> cInfoMap = new HashMap<>(cInfos.size());
        if (!CollUtils.isEmpty(cInfos)) {
            cInfoMap = cInfos.stream().collect(Collectors.toMap(CourseSimpleInfoDTO::getId, c -> c));
        }

        // 3.4 根据id查询章节
        List<CataSimpleInfoDTO> catas = catalogueClient.batchQueryCatalogue(cataIds);
        Map<Long, String> cataMap = new HashMap<>(catas.size());
        if (!CollUtils.isEmpty(catas)) {
            cataMap = catas.stream().collect(Collectors.toMap(CataSimpleInfoDTO::getId, CataSimpleInfoDTO::getName));
        }


        // 4. 组装VO返回结果
        List<QuestionAdminVO> voList = new ArrayList<>(records.size());
        for (InteractionQuestion record : records) {
            // 4.1 将PO转为VO
            QuestionAdminVO vo = BeanUtils.copyBean(record, QuestionAdminVO.class);

            // 4.2 封装用户信息
            UserDTO userDTO = userMap.get(record.getUserId());
            if (userDTO != null) {
                vo.setUserName(userDTO.getName());  // 封装提问者姓名
            }

            // 4.3 封装课程信息以及分类信息
            CourseSimpleInfoDTO cInfo = cInfoMap.get(record.getCourseId());
            if (cInfo != null) {
                vo.setCourseName(cInfo.getName());  // 封装课程名称
                List<Long> categoryIds = cInfo.getCategoryIds();    // 课程分类id列表, 包含一级分类、二级分类、三级分类
                String categoryNames = categoryCache.getCategoryNames(categoryIds);
                vo.setCategoryName(categoryNames);    // 封装课程分类名称
            }

            // 4.4 封装章节信息
            vo.setChapterName(cataMap.getOrDefault(record.getChapterId(), ""));    // 封装章名称
            vo.setSectionName(cataMap.getOrDefault(record.getSectionId(), ""));    // 封装小节名称

            // 4.5 封装VO
            voList.add(vo);
        }

        return PageDTO.of(page, voList);
    }


    /**
     * 管理端隐藏或显示互动问题
     * @param id
     * @param hidden
     */
    @Override
    public void hiddenQuestion(Long id, Boolean hidden) {
        log.info("管理端隐藏或显示互动问题，问题id：{}, 隐藏状态：{}", id, hidden);
        InteractionQuestion question = new InteractionQuestion();
        question.setId(id);
        question.setHidden(hidden);
        this.updateById(question);
    }


    /**
     * 管理端根据问题id查询互动问题
     * @param id
     * @return
     */
    @Override
    public QuestionAdminVO queryQuestionByIdAdmin(Long id) {
        // 1. 根据id查询问题
        InteractionQuestion question = this.getById(id);
        if (question == null) {
            return null;
        }


        // 2. 转PO为VO
        QuestionAdminVO vo = BeanUtils.copyBean(question, QuestionAdminVO.class);


        // 3. 远程调用用户服务，查询提问者信息
        UserDTO user = userClient.queryUserById(question.getUserId());
        if (user != null) {
            vo.setUserName(user.getName()); // 设置提问者姓名
            vo.setUserIcon(user.getIcon()); // 设置提问者头像
        }


        // 4. 远程调用课程服务，查询课程信息
        CourseFullInfoDTO cInfo = courseClient.getCourseInfoById(question.getCourseId(), false, true);
        if (cInfo != null) {
            // 4.1. 课程名称信息
            vo.setCourseName(cInfo.getName());  // 设置课程名称

            // 4.2. 分类信息
            vo.setCategoryName(categoryCache.getCategoryNames(cInfo.getCategoryIds())); // 设置课程分类名称

            // 4.3. 教师信息
            List<Long> teacherIds = cInfo.getTeacherIds();
            // 远程调用用户服务，查询教师信息
            List<UserDTO> teachers = userClient.queryUserByIds(teacherIds);
            if (CollUtils.isNotEmpty(teachers)) {
                vo.setTeacherName(teachers.stream()
                        .map(UserDTO::getName).collect(Collectors.joining("/")));   // 设置教师名称
            }
        }


        // 5. 查询章节信息
        List<CataSimpleInfoDTO> catas = catalogueClient.batchQueryCatalogue(
                List.of(question.getChapterId(), question.getSectionId()));
        Map<Long, String> cataMap = new HashMap<>(catas.size());
        if (CollUtils.isNotEmpty(catas)) {
            cataMap = catas.stream()
                    .collect(Collectors.toMap(CataSimpleInfoDTO::getId, CataSimpleInfoDTO::getName));
        }
        vo.setChapterName(cataMap.getOrDefault(question.getChapterId(), ""));    // 设置章名称
        vo.setSectionName(cataMap.getOrDefault(question.getSectionId(), ""));    // 设置小节名称

        // 6. 修改问题状态，将状态设置为已查看，也就是将interactions_question表中的status字段设置为1
        // 问题表中有一个status字段，标记管理员是否已经查看过该问题。因此每当调用根据id查询问题接口，我们可以认为管理员查看了该问题，应该将问题status标记为已查看。
        question.setStatus(QuestionStatus.CHECKED);
        this.updateById(question);

        // 7.封装返回结果VO
        return vo;
    }
}
