package com.tianji.learning.mapper;

import com.tianji.learning.domain.po.PointsBoardSeason;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author lzj
 * @since 2025-02-06
 */
public interface PointsBoardSeasonMapper extends BaseMapper<PointsBoardSeason> {
    /**
     * 根据赛季id来创建我们进行分表后的上赛季榜单表
     * @param tableName
     */
    @Insert("        CREATE TABLE `${tableName}`\n" +
            "        (\n" +
            "            `id`      BIGINT NOT NULL AUTO_INCREMENT COMMENT '榜单id',\n" +
            "            `user_id` BIGINT NOT NULL COMMENT '学生id',\n" +
            "            `points`  INT    NOT NULL COMMENT '积分值',\n" +
            "            PRIMARY KEY (`id`) USING BTREE,\n" +
            "            INDEX `idx_user_id` (`user_id`) USING BTREE\n" +
            "        )\n" +
            "            COMMENT ='学霸天梯榜'\n" +
            "            COLLATE = 'utf8mb4_0900_ai_ci'\n" +
            "            ENGINE = InnoDB\n" +
            "            ROW_FORMAT = DYNAMIC")
    void createPointsBoardLatestTable(@Param("tableName") String tableName);
}
