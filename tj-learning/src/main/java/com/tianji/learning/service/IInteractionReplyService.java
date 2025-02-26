package com.tianji.learning.service;

import com.tianji.common.domain.dto.PageDTO;
import com.tianji.learning.domain.dto.ReplyDTO;
import com.tianji.learning.domain.po.InteractionReply;
import com.baomidou.mybatisplus.extension.service.IService;
import com.tianji.learning.domain.query.ReplyPageQuery;
import com.tianji.learning.domain.vo.ReplyVO;

/**
 * <p>
 * 互动问题的回答或评论 服务类
 * </p>
 *
 * @author lzj
 * @since 2025-02-04
 */
public interface IInteractionReplyService extends IService<InteractionReply> {
    /**
     * 用户端新增互动问题的回答或评论
     * @param replyDTO
     */
    void saveReply(ReplyDTO replyDTO);

    /**
     * 用户端分页查询互动问题的回答或评论
     * @param query
     * @return
     */
    PageDTO<ReplyVO> queryReplyPage(ReplyPageQuery query);

    /**
     * 管理端分页查询互动问题的回答或评论
     * @param query
     * @return
     */
    PageDTO<ReplyVO> queryReplyPageAdmin(ReplyPageQuery query);

    /**
     * 管理端隐藏互动问题的回答或评论
     * @param id
     * @param hidden
     */
    void hiddenReply(Long id, Boolean hidden);

    /**
     * 管理端根据id查询回答或评论
     * @param id
     * @return
     */
    ReplyVO queryReplyById(Long id);
}