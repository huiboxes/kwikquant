package com.kwikquant.strategy.infrastructure;

import com.kwikquant.strategy.domain.StrategyCode;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface StrategyCodeMapper {

    @Insert(
            """
            INSERT INTO strategy_codes (strategy_id, version_number, source_code, status, language, changelog)
            VALUES (#{strategyId}, #{versionNumber}, #{sourceCode}, #{status}, #{language}, #{changelog})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(StrategyCode code);

    @Select(
            """
            SELECT id, strategy_id, version_number, source_code, status, language, changelog,
                   created_at, updated_at
            FROM strategy_codes WHERE id = #{id}
            """)
    @Results({
        @Result(column = "strategy_id", property = "strategyId"),
        @Result(column = "version_number", property = "versionNumber"),
        @Result(column = "source_code", property = "sourceCode"),
        @Result(column = "created_at", property = "createdAt"),
        @Result(column = "updated_at", property = "updatedAt")
    })
    StrategyCode findById(@Param("id") long id);

    @Select(
            """
            SELECT id, strategy_id, version_number, source_code, status, language, changelog,
                   created_at, updated_at
            FROM strategy_codes WHERE strategy_id = #{strategyId}
            ORDER BY version_number DESC
            """)
    @Results({
        @Result(column = "strategy_id", property = "strategyId"),
        @Result(column = "version_number", property = "versionNumber"),
        @Result(column = "source_code", property = "sourceCode"),
        @Result(column = "created_at", property = "createdAt"),
        @Result(column = "updated_at", property = "updatedAt")
    })
    List<StrategyCode> findByStrategyId(@Param("strategyId") long strategyId);

    @Select("SELECT COALESCE(MAX(version_number), 0) FROM strategy_codes WHERE strategy_id = #{strategyId}")
    int findMaxVersionNumber(@Param("strategyId") long strategyId);

    /**
     * 深度防御：{@code strategy_codes} 无 user_id 列，通过 EXISTS 子查询关联 strategies 校验 owner。
     */
    @Update(
            """
            UPDATE strategy_codes SET status = 'ARCHIVED', updated_at = now()
            WHERE strategy_id = #{strategyId} AND status = 'PUBLISHED'
              AND EXISTS (SELECT 1 FROM strategies s
                          WHERE s.id = strategy_codes.strategy_id AND s.user_id = #{userId} AND s.deleted = FALSE)
            """)
    int archiveCurrentPublished(@Param("strategyId") long strategyId, @Param("userId") long userId);

    @Update(
            """
            UPDATE strategy_codes SET status = #{newStatus}, updated_at = now()
            WHERE id = #{id} AND status = #{expectedStatus}
              AND EXISTS (SELECT 1 FROM strategies s
                          WHERE s.id = strategy_codes.strategy_id AND s.user_id = #{userId} AND s.deleted = FALSE)
            """)
    int updateStatus(
            @Param("id") long id,
            @Param("userId") long userId,
            @Param("expectedStatus") String expectedStatus,
            @Param("newStatus") String newStatus);

    @Update(
            """
            UPDATE strategy_codes SET source_code = #{sourceCode}, changelog = #{changelog}, updated_at = now()
            WHERE id = #{id} AND status = 'DRAFT'
              AND EXISTS (SELECT 1 FROM strategies s
                          WHERE s.id = strategy_codes.strategy_id AND s.user_id = #{userId} AND s.deleted = FALSE)
            """)
    int updateDraft(
            @Param("id") long id,
            @Param("userId") long userId,
            @Param("sourceCode") String sourceCode,
            @Param("changelog") String changelog);

    /**
     * 删除 DRAFT 草稿。深度防御:WHERE 含 status='DRAFT' + EXISTS strategy owner 校验。
     * 返回 0 说明并发发布/归档或 owner 变更,Service 抛 4009。
     */
    @Delete(
            """
            DELETE FROM strategy_codes WHERE id = #{id} AND status = 'DRAFT'
              AND EXISTS (SELECT 1 FROM strategies s
                          WHERE s.id = strategy_codes.strategy_id AND s.user_id = #{userId} AND s.deleted = FALSE)
            """)
    int deleteDraft(@Param("id") long id, @Param("userId") long userId);

    @Select(
            """
            SELECT id, strategy_id, version_number, source_code, status, language, changelog,
                   created_at, updated_at
            FROM strategy_codes WHERE strategy_id = #{strategyId} AND status = 'PUBLISHED'
            """)
    @Results({
        @Result(column = "strategy_id", property = "strategyId"),
        @Result(column = "version_number", property = "versionNumber"),
        @Result(column = "source_code", property = "sourceCode"),
        @Result(column = "created_at", property = "createdAt"),
        @Result(column = "updated_at", property = "updatedAt")
    })
    StrategyCode findPublishedByStrategyId(@Param("strategyId") long strategyId);
}
