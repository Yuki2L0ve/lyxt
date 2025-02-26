package com.tianji.learning.service;

import com.tianji.common.domain.dto.PageDTO;
import com.tianji.learning.domain.dto.QuestionFormDTO;
import com.tianji.learning.domain.po.InteractionQuestion;
import com.baomidou.mybatisplus.extension.service.IService;
import com.tianji.learning.domain.query.QuestionAdminPageQuery;
import com.tianji.learning.domain.query.QuestionPageQuery;
import com.tianji.learning.domain.vo.QuestionAdminVO;
import com.tianji.learning.domain.vo.QuestionVO;

/**
 * <p>
 * 互动提问的问题表 服务类
 * </p>
 *
 * @author lzj
 * @since 2025-02-04
 */
public interface IInteractionQuestionService extends IService<InteractionQuestion> {
    /**
     * 新增互动问题
     * @param questionFormDTO
     */
    void saveQuestion(QuestionFormDTO questionFormDTO);

    /**
     * 修改互动问题
     * @param id
     * @param questionFormDTO
     */
    void updateQuestion(Long id, QuestionFormDTO questionFormDTO);

    /**
     * 用户端分页查询互动问题
     * @param query
     * @return
     */
    PageDTO<QuestionVO> queryQuestionPage(QuestionPageQuery query);

    /**
     * 用户端根据问题id查询互动问题
     * @param id
     * @return
     */
    QuestionVO queryQuestionById(Long id);

    /**
     * 用户端根据问题id删除互动问题
     * @param id
     */
    void deleteQuestionById(Long id);

    /**
     * 管理端分页查询互动问题
     * @param query
     * @return
     */
    PageDTO<QuestionAdminVO> queryQuestionPageAdmin(QuestionAdminPageQuery query);

    /**
     * 管理端隐藏或显示互动问题
     * @param id
     * @param hidden
     */
    void hiddenQuestion(Long id, Boolean hidden);

    /**
     * 管理端根据问题id查询互动问题
     * @param id
     * @return
     */
    QuestionAdminVO queryQuestionByIdAdmin(Long id);
}
