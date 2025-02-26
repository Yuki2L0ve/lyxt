package com.tianji.learning.controller;


import com.tianji.common.domain.dto.PageDTO;
import com.tianji.learning.domain.dto.QuestionFormDTO;
import com.tianji.learning.domain.query.QuestionPageQuery;
import com.tianji.learning.domain.vo.QuestionVO;
import com.tianji.learning.service.IInteractionQuestionService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;

/**
 * <p>
 * 互动提问的问题表 前端控制器
 * </p>
 *
 * @author lzj
 * @since 2025-02-04
 */
@Api(tags = "用户端互动问题相关接口")
@RestController
@RequestMapping("/questions")
@RequiredArgsConstructor
public class InteractionQuestionController {
    private final IInteractionQuestionService questionService;

    @ApiOperation(value = "用户端新增互动问题")
    @PostMapping
    public void saveQuestion(@Valid @RequestBody QuestionFormDTO questionFormDTO) {
        questionService.saveQuestion(questionFormDTO);
    }


    @ApiOperation(value = "用户端修改互动问题")
    @PutMapping("/{id}")
    public void updateQuestion(@PathVariable("id") Long id,
                               @RequestBody QuestionFormDTO questionFormDTO) {
        questionService.updateQuestion(id, questionFormDTO);
    }

    @ApiOperation(value = "用户端分页查询互动问题")
    @GetMapping("/page")
    public PageDTO<QuestionVO> queryQuestionPage(QuestionPageQuery query) {
        return questionService.queryQuestionPage(query);
    }

    @ApiOperation(value = "用户端根据问题id查询互动问题")
    @GetMapping("/{id}")
    public QuestionVO queryQuestionById(@ApiParam(value = "问题id", example = "1") @PathVariable("id") Long id) {
        return questionService.queryQuestionById(id);
    }


    @ApiOperation(value = "用户端根据问题id删除互动问题")
    @DeleteMapping("/{id}")
    public void deleteQuestionById(@ApiParam(value = "问题id", example = "1") @PathVariable("id") Long id) {
        questionService.deleteQuestionById(id);
    }
}
