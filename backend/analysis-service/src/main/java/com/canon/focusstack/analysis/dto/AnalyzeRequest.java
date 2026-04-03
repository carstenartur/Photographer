package com.canon.focusstack.analysis.dto;

import com.canon.focusstack.common.analysis.AnalysisResult;
import com.canon.focusstack.common.model.StackingParameters;

import java.util.List;

public record AnalyzeRequest(
        StackingParameters parameters,
        List<AnalysisResult> history
) {}
