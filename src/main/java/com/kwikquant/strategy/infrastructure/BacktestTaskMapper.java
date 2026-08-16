package com.kwikquant.strategy.infrastructure;

import com.kwikquant.strategy.domain.BacktestTask;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
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
                   parameters, result, error_message, report_id, processed_bars, total_bars,
                   created_at, updated_at
            FROM backtest_tasks WHERE id = #{id}
            """)
    @Results(
            id = "backtestTaskResult",
            value = {
                @Result(column = "strategy_id", property = "strategyId"),
                @Result(column = "user_id", property = "userId"),
                @Result(column = "strategy_code_id", property = "strategyCodeId"),
                @Result(column = "report_id", property = "reportId"),
                @Result(column = "processed_bars", property = "processedBars"),
                @Result(column = "total_bars", property = "totalBars"),
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
                   parameters, result, error_message, report_id, processed_bars, total_bars,
                   created_at, updated_at
            FROM backtest_tasks WHERE strategy_id = #{strategyId}
            ORDER BY created_at DESC
            """)
    @Results({
        @Result(column = "strategy_id", property = "strategyId"),
        @Result(column = "user_id", property = "userId"),
        @Result(column = "strategy_code_id", property = "strategyCodeId"),
        @Result(column = "report_id", property = "reportId"),
        @Result(column = "processed_bars", property = "processedBars"),
        @Result(column = "total_bars", property = "totalBars"),
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
                   parameters, result, error_message, report_id, processed_bars, total_bars,
                   created_at, updated_at
            FROM backtest_tasks WHERE user_id = #{userId}
            ORDER BY created_at DESC
            """)
    @Results({
        @Result(column = "strategy_id", property = "strategyId"),
        @Result(column = "user_id", property = "userId"),
        @Result(column = "strategy_code_id", property = "strategyCodeId"),
        @Result(column = "report_id", property = "reportId"),
        @Result(column = "processed_bars", property = "processedBars"),
        @Result(column = "total_bars", property = "totalBars"),
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
            UPDATE backtest_tasks SET result = CAST(#{result} AS JSONB), report_id = #{reportId}, status = 'COMPLETED', updated_at = now()
            WHERE id = #{id} AND user_id = #{userId} AND status = 'RUNNING'
            """)
    int updateResult(
            @Param("id") long id,
            @Param("userId") long userId,
            @Param("result") String result,
            @Param("reportId") Long reportId);

    @Update(
            """
            UPDATE backtest_tasks SET error_message = #{errorMessage}, status = 'FAILED', updated_at = now()
            WHERE id = #{id} AND user_id = #{userId} AND status = 'RUNNING'
            """)
    int updateError(@Param("id") long id, @Param("userId") long userId, @Param("errorMessage") String errorMessage);

    /**
     * 逐 bar 进度上报(Worker 通道)。带 {@code status = 'RUNNING'} 守卫,防终态后被误写
     * (与 updateResult/updateError 一致,深度防御越权)。返 0 = task 非 RUNNING 或非本人。
     */
    @Update(
            """
            UPDATE backtest_tasks
            SET processed_bars = #{processedBars}, total_bars = #{totalBars}, updated_at = now()
            WHERE id = #{id} AND user_id = #{userId} AND status = 'RUNNING'
            """)
    int updateProgress(
            @Param("id") long id,
            @Param("userId") long userId,
            @Param("processedBars") int processedBars,
            @Param("totalBars") int totalBars);

    /**
     * 活动任务(PENDING/RUNNING),崩溃恢复用:应用启动时 PENDING 重新入队、RUNNING 标失败
     * (见 BacktestTaskRecovery)。按 created_at 升序,先提交先恢复。
     */
    @Select(
            """
            SELECT id, strategy_id, user_id, strategy_code_id, status,
                   symbol, exchange, interval_value, start_time, end_time,
                   parameters, result, error_message, report_id, processed_bars, total_bars,
                   created_at, updated_at
            FROM backtest_tasks WHERE status IN ('PENDING', 'RUNNING')
            ORDER BY created_at ASC
            """)
    @ResultMap("backtestTaskResult")
    List<BacktestTask> findActive();

    /**
     * 租约超时的 RUNNING 任务(updated_at 早于 before),周期回收用。
     * 进度上报(updateProgress)与结果写入均刷新 updated_at,天然充当 worker 心跳。
     */
    @Select(
            """
            SELECT id, strategy_id, user_id, strategy_code_id, status,
                   symbol, exchange, interval_value, start_time, end_time,
                   parameters, result, error_message, report_id, processed_bars, total_bars,
                   created_at, updated_at
            FROM backtest_tasks WHERE status = 'RUNNING' AND updated_at < #{before}
            ORDER BY created_at ASC
            """)
    @ResultMap("backtestTaskResult")
    List<BacktestTask> findStaleRunning(@Param("before") Instant before);

    /** 用户活动回测数(PENDING/RUNNING),提交配额校验用。 */
    @Select("SELECT count(*) FROM backtest_tasks WHERE user_id = #{userId} AND status IN ('PENDING', 'RUNNING')")
    int countActiveByUser(@Param("userId") long userId);
}
