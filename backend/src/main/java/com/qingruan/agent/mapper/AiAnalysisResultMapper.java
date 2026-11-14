package com.qingruan.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qingruan.agent.entity.AiAnalysisResult;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI告警分析结果 Mapper（MySQL: ai_analysis_result）
 */
@Mapper
public interface AiAnalysisResultMapper extends BaseMapper<AiAnalysisResult> {
}
