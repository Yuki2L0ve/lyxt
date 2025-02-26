package com.tianji.remark.service;

import com.tianji.remark.domain.dto.LikeRecordFormDTO;
import com.tianji.remark.domain.po.LikedRecord;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;
import java.util.Set;

/**
 * <p>
 * 点赞记录表 服务类
 * </p>
 *
 * @author lzj
 * @since 2025-02-05
 */
public interface ILikedRecordService extends IService<LikedRecord> {
    /**
     * 点赞或取消点赞
     * @param dto
     */
    void addLikeRecord(LikeRecordFormDTO dto);

    /**
     * 批量查询指定业务id的点赞状态, 封装好业务id塞到Set集合中, 以便供其他微服务远程调用
     * @param bizIds
     * @return
     */
    Set<Long> isBizLiked(List<Long> bizIds);

    /**
     * 将Redis中的点赞总数交由消息队列来异步更新到数据库中
     * @param bizType
     * @param maxBizSize
     */
    void readLikedTimesAndSendMessage(String bizType, int maxBizSize);
}
