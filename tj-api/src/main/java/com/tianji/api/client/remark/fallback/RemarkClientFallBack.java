package com.tianji.api.client.remark.fallback;

import com.tianji.api.client.remark.RemarkClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;

import java.util.List;
import java.util.Set;

/**
 * RemarkClient降级类
 */
@Slf4j
public class RemarkClientFallBack implements FallbackFactory<RemarkClient> {
    // 如果remark-service服务不可用或者其他服务调用该服务超时，则会自动调用此降级类
    @Override
    public RemarkClient create(Throwable cause) {
        log.error("RemarkClient调用失败，原因：{}", cause.getMessage());
        return new RemarkClient() {
            @Override
            public Set<Long> isBizLiked(List<Long> bizIds) {
                return null;
            }
        };
    }
}
