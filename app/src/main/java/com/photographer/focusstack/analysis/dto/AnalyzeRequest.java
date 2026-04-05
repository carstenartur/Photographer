package com.photographer.focusstack.analysis.dto;

import com.photographer.focusstack.common.analysis.AnalysisResult;
import com.photographer.focusstack.common.model.StackingParameters;

import java.util.List;

public record AnalyzeRequest(
        StackingParameters parameters,
        List<AnalysisResult> history
) {}
