package com.tianji.remark.controller;


import com.tianji.remark.domain.dto.LikeRecordFormDTO;
import com.tianji.remark.service.ILikedRecordService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;
import java.util.Set;

/**
 * <p>
 * 点赞记录表 前端控制器
 * </p>
 *
 * @author lzj
 * @since 2025-02-05
 */
@Api(tags = "点赞业务相关接口")
@RestController
@RequestMapping("/likes")
@RequiredArgsConstructor
public class LikedRecordController {
    private final ILikedRecordService likedRecordService;


    @ApiOperation(value = "点赞或取消点赞")
    @PostMapping
    public void addLikeRecord(@Valid @RequestBody LikeRecordFormDTO dto) {
        likedRecordService.addLikeRecord(dto);
    }

    @ApiOperation(value = "批量查询指定业务id的点赞状态")
    @GetMapping("/list")
    public Set<Long> isBizLiked(@RequestParam("bizIds") List<Long> bizIds) {
        return likedRecordService.isBizLiked(bizIds);
    }
}
