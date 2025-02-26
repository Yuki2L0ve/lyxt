package com.tianji.learning.controller;

import com.tianji.common.domain.dto.PageDTO;
import com.tianji.learning.domain.query.ReplyPageQuery;
import com.tianji.learning.domain.vo.ReplyVO;
import com.tianji.learning.service.IInteractionReplyService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * <p>
 * 互动问题的回答或评论 前端控制器
 * </p>
 *
 * @author lzj
 * @since 2025-02-04
 */
@Api(tags = "管理端互动问题的回答或评论管理")
@RestController
@RequestMapping("/admin/replies")
@RequiredArgsConstructor
public class InteractionReplyAdminController {
    private final IInteractionReplyService replyService;


    @ApiOperation(value = "管理端分页查询互动问题的回答或评论")
    @GetMapping("/page")
    public PageDTO<ReplyVO> queryReplyPageAdmin(ReplyPageQuery query) {
        return replyService.queryReplyPageAdmin(query);
    }


    @ApiOperation(value = "管理端隐藏互动问题的回答或评论")
    @PutMapping("/{id}/hidden/{hidden}")
    public void hiddenReply(
            @ApiParam(value = "问题id", example = "1") @PathVariable("id") Long id,
            @ApiParam(value = "是否隐藏，true/false", example = "true") @PathVariable("hidden") Boolean hidden) {
        replyService.hiddenReply(id, hidden);
    }


    @ApiOperation("管理端根据id查询回答或评论")
    @GetMapping("/{id}")
    public ReplyVO queryReplyById(@ApiParam(value = "问题id", example = "1") @PathVariable("id") Long id){
        return replyService.queryReplyById(id);
    }
}
