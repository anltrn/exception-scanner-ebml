package com.scanner.exception.complexity;

import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.io.Writer;
import java.util.List;
import java.util.Map;

/** Gson {@code toJson} yerine doğrudan {@link Writer}'a yazar; dev JSON için ek String kopyası oluşturmaz. */
final class AnalysisReportJsonWriter {

    private AnalysisReportJsonWriter() {
    }

    static void write(AnalysisReport report, Writer out, boolean compact) throws IOException {
        JsonWriter writer = new JsonWriter(out);
        if (!compact) {
            writer.setIndent("  ");
        }
        writeReport(writer, report);
        writer.flush();
    }

    private static void writeReport(JsonWriter w, AnalysisReport report) throws IOException {
        w.beginObject();
        w.name("tool").value(report.tool());
        w.name("generatedAt").value(report.generatedAt());
        w.name("analyzedPath").value(report.analyzedPath());
        w.name("parserLanguageLevel").value(report.parserLanguageLevel());
        writeRiskModel(w, report.riskModel());
        writeSummary(w, report.summary());
        writeScanDiagnostics(w, report.scanDiagnostics());
        writeCacheStatistics(w, report.cacheStatistics());
        writeIncrementalChanges(w, report.incrementalChanges());
        writeDuplicateStatistics(w, report.duplicateStatistics());
        writeDuplicateGroups(w, report.duplicateGroups());
        writeHotspots(w, report.topRiskyMethods());
        writeFiles(w, report.files());
        writeErrors(w, report.errors());
        w.endObject();
    }

    private static void writeRiskModel(JsonWriter w, AnalysisReport.RiskModel model) throws IOException {
        w.name("riskModel");
        w.beginObject();
        w.name("version").value(model.version());
        w.name("formula").value(model.formula());
        w.name("weights");
        w.beginObject();
        for (Map.Entry<String, Double> e : model.weights().entrySet()) {
            w.name(e.getKey()).value(e.getValue());
        }
        w.endObject();
        w.name("thresholds");
        w.beginObject();
        for (Map.Entry<String, List<Integer>> e : model.thresholds().entrySet()) {
            w.name(e.getKey());
            w.beginArray();
            for (int v : e.getValue()) {
                w.value(v);
            }
            w.endArray();
        }
        w.endObject();
        w.endObject();
    }

    private static void writeSummary(JsonWriter w, AnalysisReport.Summary s) throws IOException {
        w.name("summary");
        w.beginObject();
        w.name("filesScanned").value(s.filesScanned());
        w.name("filesParsed").value(s.filesParsed());
        w.name("filesFailed").value(s.filesFailed());
        w.name("classCount").value(s.classCount());
        w.name("methodCount").value(s.methodCount());
        w.name("totalCodeLines").value(s.totalCodeLines());
        w.name("averageCyclomaticComplexity").value(s.averageCyclomaticComplexity());
        w.name("maxCyclomaticComplexity").value(s.maxCyclomaticComplexity());
        w.name("godMethodCount").value(s.godMethodCount());
        w.name("projectRiskScore").value(s.projectRiskScore());
        w.name("projectRiskLevel").value(s.projectRiskLevel().name());
        w.name("highPlusCriticalLocRatio").value(s.highPlusCriticalLocRatio());
        w.name("criticalMethodsPerKloc").value(s.criticalMethodsPerKloc());
        w.name("methodRiskScoreP95").value(s.methodRiskScoreP95());
        w.name("methodRiskScoreP99").value(s.methodRiskScoreP99());
        w.name("methodRiskDistribution");
        w.beginObject();
        for (RiskLevel level : RiskLevel.values()) {
            w.name(level.name()).value(s.methodRiskDistribution().get(level));
        }
        w.endObject();
        w.name("moduleSummaries");
        w.beginArray();
        for (AnalysisReport.ModuleRiskSummary module : s.moduleSummaries()) {
            w.beginObject();
            w.name("moduleRoot").value(module.moduleRoot());
            w.name("methodCount").value(module.methodCount());
            w.name("codeLines").value(module.codeLines());
            w.name("locWeightedRiskScore").value(module.locWeightedRiskScore());
            w.name("methodRiskScoreP95").value(module.methodRiskScoreP95());
            w.name("criticalMethodsPerKloc").value(module.criticalMethodsPerKloc());
            w.endObject();
        }
        w.endArray();
        w.endObject();
    }

    private static void writeScanDiagnostics(JsonWriter w, ScanDiagnostics d) throws IOException {
        if (d == null) {
            return;
        }
        w.name("scanDiagnostics");
        w.beginObject();
        w.name("completionStatus").value(d.completionStatus());
        w.name("lastPhaseTr").value(d.lastPhaseTr());
        if (d.fatalPhaseTr() != null) {
            w.name("fatalPhaseTr").value(d.fatalPhaseTr());
        }
        if (d.fatalMessage() != null) {
            w.name("fatalMessage").value(d.fatalMessage());
        }
        if (d.lastFileAttempted() != null) {
            w.name("lastFileAttempted").value(d.lastFileAttempted());
        }
        w.name("parseFailureRatio").value(d.parseFailureRatio());
        w.name("errorsByCategory");
        w.beginObject();
        for (Map.Entry<String, Integer> e : d.errorsByCategory().entrySet()) {
            w.name(e.getKey()).value(e.getValue());
        }
        w.endObject();
        w.name("recommendationsTr");
        w.beginArray();
        for (String tip : d.recommendationsTr()) {
            w.value(tip);
        }
        w.endArray();
        w.endObject();
    }

    private static void writeHotspots(JsonWriter w, List<AnalysisReport.RiskHotspot> hotspots) throws IOException {
        w.name("topRiskyMethods");
        w.beginArray();
        for (AnalysisReport.RiskHotspot h : hotspots) {
            w.beginObject();
            w.name("file").value(h.file());
            w.name("packageName").value(h.packageName());
            w.name("className").value(h.className());
            w.name("method").value(h.method());
            w.name("startLine").value(h.startLine());
            w.name("riskScore").value(h.riskScore());
            w.name("riskLevel").value(h.riskLevel().name());
            w.name("cyclomaticComplexity").value(h.cyclomaticComplexity());
            w.name("codeLines").value(h.codeLines());
            w.name("maxNestingDepth").value(h.maxNestingDepth());
            w.name("parameterCount").value(h.parameterCount());
            w.name("cognitiveComplexity").value(h.cognitiveComplexity());
            w.name("outboundDistinctCalls").value(h.outboundDistinctCalls());
            w.name("dominantDriver").value(h.dominantDriver());
            writeStringList(w, "riskFactors", h.riskFactors());
            w.endObject();
        }
        w.endArray();
    }

    private static void writeFiles(JsonWriter w, List<AnalysisReport.FileMetric> files) throws IOException {
        w.name("files");
        w.beginArray();
        for (AnalysisReport.FileMetric file : files) {
            w.beginObject();
            w.name("path").value(file.path());
            w.name("packageName").value(file.packageName());
            w.name("physicalLines").value(file.physicalLines());
            w.name("codeLines").value(file.codeLines());
            w.name("classCount").value(file.classCount());
            w.name("methodCount").value(file.methodCount());
            w.name("totalCyclomaticComplexity").value(file.totalCyclomaticComplexity());
            w.name("maxCyclomaticComplexity").value(file.maxCyclomaticComplexity());
            w.name("riskScore").value(file.riskScore());
            w.name("riskLevel").value(file.riskLevel().name());
            writeStringList(w, "riskFactors", file.riskFactors());
            w.name("classes");
            w.beginArray();
            for (AnalysisReport.ClassMetric type : file.classes()) {
                w.beginObject();
                w.name("name").value(type.name());
                w.name("kind").value(type.kind());
                w.name("startLine").value(type.startLine());
                w.name("endLine").value(type.endLine());
                w.name("methodCount").value(type.methodCount());
                w.name("publicMethodCount").value(type.publicMethodCount());
                w.name("codeLines").value(type.codeLines());
                w.name("efferentCouplingProxy").value(type.efferentCouplingProxy());
                w.name("weightedMethodComplexity").value(type.weightedMethodComplexity());
                w.name("maxMethodComplexity").value(type.maxMethodComplexity());
                w.name("averageMethodComplexity").value(type.averageMethodComplexity());
                w.name("riskScore").value(type.riskScore());
                w.name("riskLevel").value(type.riskLevel().name());
                writeStringList(w, "riskFactors", type.riskFactors());
                writeHalstead(w, "halstead", type.halstead());
                w.name("lcom3").value(type.lcom3());
                w.name("godClassCandidate").value(type.godClassCandidate());
                writeStringList(w, "dependentTypes", type.dependentTypes());
                w.name("methods");
                w.beginArray();
                for (MethodMetric m : type.methods()) {
                    writeMethod(w, m);
                }
                w.endArray();
                w.endObject();
            }
            w.endArray();
            w.endObject();
        }
        w.endArray();
    }

    private static void writeMethod(JsonWriter w, MethodMetric m) throws IOException {
        w.beginObject();
        w.name("name").value(m.name());
        w.name("kind").value(m.kind());
        w.name("signature").value(m.signature());
        w.name("startLine").value(m.startLine());
        w.name("endLine").value(m.endLine());
        w.name("cyclomaticComplexity").value(m.cyclomaticComplexity());
        w.name("physicalLines").value(m.physicalLines());
        w.name("codeLines").value(m.codeLines());
        w.name("logicalStatements").value(m.logicalStatements());
        w.name("cognitiveComplexity").value(m.cognitiveComplexity());
        w.name("exitPoints").value(m.exitPoints());
        w.name("catchClauses").value(m.catchClauses());
        w.name("switchCases").value(m.switchCases());
        w.name("outboundDistinctCalls").value(m.outboundDistinctCalls());
        w.name("lambdaCount").value(m.lambdaCount());
        w.name("maxTryNestingDepth").value(m.maxTryNestingDepth());
        w.name("localVariableCount").value(m.localVariableCount());
        w.name("maxMethodCallChainLength").value(m.maxMethodCallChainLength());
        w.name("emptyCatchBlocks").value(m.emptyCatchBlocks());
        w.name("catchExceptionOrThrowable").value(m.catchExceptionOrThrowable());
        w.name("catchWithOnlyPrintStackTrace").value(m.catchWithOnlyPrintStackTrace());
        w.name("primitiveObsessionIndex").value(m.primitiveObsessionIndex());
        w.name("maxBooleanOperatorsInCondition").value(m.maxBooleanOperatorsInCondition());
        w.name("maxNestingDepth").value(m.maxNestingDepth());
        w.name("parameterCount").value(m.parameterCount());
        w.name("godMethod").value(m.godMethod());
        w.name("riskScore").value(m.riskScore());
        w.name("riskLevel").value(m.riskLevel().name());
        writeStringList(w, "riskFactors", m.riskFactors());
        writeBreakdown(w, m.riskBreakdown());
        writeHalstead(w, "halstead", m.halstead());
        writeExceptionSmells(w, m.exceptionSmells());
        writeCodeSmells(w, m.codeSmells());
        w.name("methodHash").value(m.methodHash());
        w.name("analysisReused").value(m.analysisReused());
        w.name("bodyHash").value(m.bodyHash());
        w.name("structuralHash").value(m.structuralHash());
        w.endObject();
    }

    private static void writeDuplicateStatistics(JsonWriter w, AnalysisReport.DuplicateStatistics stats)
            throws IOException {
        w.name("duplicateStatistics");
        w.beginObject();
        w.name("exactGroups").value(stats.exactGroups());
        w.name("methodsInExactGroups").value(stats.methodsInExactGroups());
        w.name("nearMissGroups").value(stats.nearMissGroups());
        w.name("methodsInNearMissGroups").value(stats.methodsInNearMissGroups());
        w.endObject();
    }

    private static void writeDuplicateGroups(JsonWriter w, List<AnalysisReport.DuplicateGroup> groups)
            throws IOException {
        w.name("duplicateGroups");
        w.beginArray();
        for (AnalysisReport.DuplicateGroup group : groups) {
            w.beginObject();
            w.name("groupId").value(group.groupId());
            w.name("similarityType").value(group.similarityType());
            w.name("matchedTokenCount").value(group.matchedTokenCount());
            w.name("duplicatedLines").value(group.duplicatedLines());
            w.name("members");
            w.beginArray();
            for (AnalysisReport.DuplicateMember member : group.members()) {
                w.beginObject();
                w.name("file").value(member.file());
                w.name("className").value(member.className());
                w.name("method").value(member.method());
                w.name("startLine").value(member.startLine());
                w.name("endLine").value(member.endLine());
                w.endObject();
            }
            w.endArray();
            w.endObject();
        }
        w.endArray();
    }

    private static void writeCacheStatistics(JsonWriter w, AnalysisReport.CacheStatistics cache) throws IOException {
        if (cache == null) {
            return;
        }
        w.name("cacheStatistics");
        w.beginObject();
        w.name("totalFiles").value(cache.totalFiles());
        w.name("filesSkippedViaFileHash").value(cache.filesSkippedViaFileHash());
        w.name("filesReanalyzed").value(cache.filesReanalyzed());
        w.name("filesSemanticCosmeticOnly").value(cache.filesSemanticCosmeticOnly());
        w.name("filesSkippedViaModuleBulk").value(cache.filesSkippedViaModuleBulk());
        w.name("modulesUnchanged").value(cache.modulesUnchanged());
        w.name("totalMethods").value(cache.totalMethods());
        w.name("methodsSkippedViaHash").value(cache.methodsSkippedViaHash());
        w.name("methodsReanalyzed").value(cache.methodsReanalyzed());
        w.name("scanTimeMillis").value(cache.scanTimeMillis());
        w.endObject();
    }

    private static void writeBreakdown(JsonWriter w, RiskBreakdown b) throws IOException {
        w.name("riskBreakdown");
        w.beginObject();
        w.name("cyclomaticComplexity").value(b.cyclomaticComplexity());
        w.name("codeLines").value(b.codeLines());
        w.name("maxNestingDepth").value(b.maxNestingDepth());
        w.name("parameterCount").value(b.parameterCount());
        w.name("ccSubScore").value(b.ccSubScore());
        w.name("locSubScore").value(b.locSubScore());
        w.name("nestingSubScore").value(b.nestingSubScore());
        w.name("paramsSubScore").value(b.paramsSubScore());
        w.name("cognitiveSubScore").value(b.cognitiveSubScore());
        w.name("outboundSubScore").value(b.outboundSubScore());
        w.name("exitSubScore").value(b.exitSubScore());
        w.name("extraSubScores");
        w.beginObject();
        for (Map.Entry<String, Double> entry : b.extraSubScores().entrySet()) {
            w.name(entry.getKey()).value(entry.getValue());
        }
        w.endObject();
        w.name("dominantSubScore").value(b.dominantSubScore());
        w.name("weightedBlend").value(b.weightedBlend());
        w.name("compoundBonus").value(b.compoundBonus());
        w.name("finalScore").value(b.finalScore());
        w.endObject();
    }

    private static void writeErrors(JsonWriter w, List<AnalysisReport.FileError> errors) throws IOException {
        w.name("errors");
        w.beginArray();
        for (AnalysisReport.FileError e : errors) {
            w.beginObject();
            w.name("file").value(e.file());
            w.name("message").value(e.message());
            w.name("category").value(e.category().name());
            w.endObject();
        }
        w.endArray();
    }

    private static void writeStringList(JsonWriter w, String name, List<String> values) throws IOException {
        w.name(name);
        w.beginArray();
        for (String v : values) {
            w.value(v);
        }
        w.endArray();
    }

    private static void writeIncrementalChanges(JsonWriter w, IncrementalChanges changes) throws IOException {
        w.name("incrementalChanges");
        w.beginObject();
        w.name("comparedToPreviousScan").value(changes.comparedToPreviousScan());
        w.name("filesAdded").value(changes.filesAdded());
        w.name("filesRemoved").value(changes.filesRemoved());
        w.name("filesModified").value(changes.filesModified());
        w.name("filesCosmeticOnly").value(changes.filesCosmeticOnly());
        w.name("filesUnchanged").value(changes.filesUnchanged());
        w.name("methodsAdded").value(changes.methodsAdded());
        w.name("methodsRemoved").value(changes.methodsRemoved());
        w.name("methodsModified").value(changes.methodsModified());
        w.name("fileChanges");
        w.beginArray();
        for (IncrementalChanges.FilePathChange fc : changes.fileChanges()) {
            w.beginObject();
            w.name("path").value(fc.path());
            w.name("kind").value(fc.kind().name());
            w.endObject();
        }
        w.endArray();
        w.name("methodChanges");
        w.beginArray();
        for (IncrementalChanges.MethodPathChange mc : changes.methodChanges()) {
            w.beginObject();
            w.name("file").value(mc.file());
            w.name("className").value(mc.className());
            w.name("methodSignature").value(mc.methodSignature());
            w.name("startLine").value(mc.startLine());
            w.name("endLine").value(mc.endLine());
            w.name("kind").value(mc.kind().name());
            w.endObject();
        }
        w.endArray();
        w.endObject();
    }

    private static void writeHalstead(JsonWriter w, String name, HalsteadMetrics h) throws IOException {
        w.name(name);
        w.beginObject();
        w.name("difficulty").value(h.difficulty());
        w.name("effort").value(h.effort());
        w.name("volume").value(h.volume());
        w.endObject();
    }

    private static void writeExceptionSmells(JsonWriter w, List<ExceptionSmell> smells) throws IOException {
        w.name("exceptionSmells");
        w.beginArray();
        for (ExceptionSmell s : smells) {
            w.beginObject();
            w.name("type").value(s.type().name());
            w.name("exceptionType").value(s.exceptionType());
            w.name("line").value(s.line());
            w.endObject();
        }
        w.endArray();
    }

    private static void writeCodeSmells(JsonWriter w, List<CodeSmell> smells) throws IOException {
        w.name("codeSmells");
        w.beginArray();
        for (CodeSmell s : smells) {
            w.beginObject();
            w.name("type").value(s.type().name());
            w.name("detail").value(s.detail());
            w.name("line").value(s.line());
            w.endObject();
        }
        w.endArray();
    }
}
