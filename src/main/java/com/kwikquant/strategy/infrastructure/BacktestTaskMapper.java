package com.kwikquant.strategy.infrastructure;

import com.kwikquant.strategy.domain.BacktestTask;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface BacktestTaskMapper {

    @Insert(
            """
            INSERT INTO backtest_tasks (strategy_id, user_id, strategy_code_id, status,
                                        symbol, exchange, interval_value,
                                        start_time, end_time, parameters)
            VALUES (#{strategyId}, #{userId}, #{strategyCodeId}, #{status},
                    #{symbol}, #{exchange}, #{intervalValue},
                    #{startTime}, #{endTime}, CAST(#{parameters} AS JSONB))
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(BacktestTask task);

    @Select(
            """
            SELECT id, strategy_id, user_id, strategy_code_id, status,
                   symbol, exchange, interval_value, start_time, end_time,
                   parameters, result, error_message, created_at, updated_at
            FROM backtest_tasks WHERE id = #{id}
            """)
    @Results({
        @Result(column = "strategy_id", property = "strategyId"),
        @Result(column = "user_id", property = "userId"),
        @Result(column = "strategy_code_id", property = "strategyCodeId"),
        @Result(column = "interval_value", property = "intervalValue"),
        @Result(column = "start_time", property = "startTime"),
        @Result(column = "end_time", property = "endTime"),
        @Result(column = "error_message", property = "errorMessage"),
        @Result(column = "created_at", property = "createdAt"),
        @Result(column = "updated_at", property = "updatedAt")
    })
    BacktestTask findById(@Param("id") long id);

    @Select(
            """
            SELECT id, strategy_id, user_id, strategy_code_id, status,
                   symbol, exchange, interval_value, start_time, end_time,
                   parameters, result, error_message, created_at, updated_at
            FROM backtest_tasks WHERE strategy_id = #{strategyId}
            ORDER BY created_at DESC
            """)
    @Results({
        @Result(column = "strategy_id", property = "strategyId"),
        @Result(column = "user_id", property = "userId"),
        @Result(column = "strategy_code_id", property = "strategyCodeId"),
        @Result(column = "interval_value", property = "intervalValue"),
        @Result(column = "start_time", property = "startTime"),
        @Result(column = "end_time", property = "endTime"),
        @Result(column = "error_message", property = "errorMessage"),
        @Result(column = "created_at", property = "createdAt"),
        @Result(column = "updated_at", property = "updatedAt")
    })
    List<BacktestTask> findByStrategyId(@Param("strategyId") long strategyId);

    @Select(
            """
            SELECT id, strategy_id, user_id, strategy_code_id, status,
                   symbol, exchange, interval_value, start_time, end_time,
                   parameters, result, error_message, created_at, updated_at
            FROM backtest_tasks WHERE user_id = #{userId}
            ORDER BY created_at DESC
            """)
    @Results({
        @Result(column = "strategy_id", property = "strategyId"),
        @Result(column = "user_id", property = "userId"),
        @Result(column = "strategy_code_id", property = "strategyCodeId"),
        @Result(column = "interval_value", property = "intervalValue"),
        @Result(column = "start_time", property = "startTime"),
        @Result(column = "end_time", property = "endTime"),
        @Result(column = "error_message", property = "errorMessage"),
        @Result(column = "created_at", property = "createdAt"),
        @Result(column = "updated_at", property = "updatedAt")
    })
    List<BacktestTask> findByUserId(@Param("userId") long userId);

    /**
     * 深度防御：全部 UPDATE 都强制带 {@code user_id}，避免异步执行流程漏做所有权校验时越权改状态/结果。
     */
    @Update(
            """
            UPDATE backtest_tasks SET status = #{newStatus}, updated_at = now()
            WHERE id = #{id} AND user_id = #{userId} AND status = #{expectedStatus}
            """)
    int updateStatus(
            @Param("id") long id,
            @Param("userId") long userId,
            @Param("expectedStatus") String expectedStatus,
            @Param("newStatus") String newStatus);

    @Update(
            """
            UPDATE backtest_tasks SET result = CAST(#{result} AS JSONB), status = 'COMPLETED', updated_at = now()
            WHERE id = #{id} AND user_id = #{userId} AND status = 'RUNNING'
            """)
    int updateResult(@Param("id") long id, @Param("userId") long userId, @Param("result") String result);

    @Update(
            """
            UPDATE backtest_tasks SET error_message = #{errorMessage}, status = 'FAILED', updated_at = now()
            WHERE id = #{id} AND user_id = #{userId} AND status = 'RUNNING'
            """)
    int updateError(@Param("id") long id, @Param("userId") long userId, @Param("errorMessage") String errorMessage);
}
