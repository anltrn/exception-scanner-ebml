package com.scanner.exception.complexity;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.ToIntFunction;

/** Loaded from YAML; drives {@link RiskCalculator}. */
public record RiskProfile(
        String profileId,
        String modelVersion,
        double compoundFactor,
        double dominantWeight,
        double blendWeight,
        List<ScoredDimension<MethodScanValues>> methodDimensions,
        List<DimensionGroup> methodDimensionGroups,
        List<ScoredDimension<ClassScanValues>> classDimensions,
        Map<String, ThresholdTriple> godMethodThresholds) {

    public record DimensionGroup(String id, double weight, List<String> memberIds) {
    }

    public RiskProfile {
        if (dominantWeight <= 0 && blendWeight <= 0) {
            double legacyBlend = compoundFactor > 0 ? compoundFactor : 0.15;
            dominantWeight = 1.0 - legacyBlend;
            blendWeight = legacyBlend;
        }
        double sum = dominantWeight + blendWeight;
        if (sum <= 0) {
            dominantWeight = 0.65;
            blendWeight = 0.35;
            sum = 1.0;
        }
        dominantWeight = dominantWeight / sum;
        blendWeight = blendWeight / sum;
    }

    public record ThresholdTriple(int dikkat, int yuksek, int kritik) {
    }

    public record ScoredDimension<T>(String id, String label, double weight, ThresholdTriple thresholds,
                                     ToIntFunction<T> value) {
        int medium() {
            return thresholds.dikkat();
        }

        int high() {
            return thresholds.yuksek();
        }

        int critical() {
            return thresholds.kritik();
        }
    }

    public record ClassScanValues(
            int codeLines,
            int wmc,
            int publicMethodCount,
            int efferentCouplingProxy,
            int efferentTypeCount,
            int lcom3Times100,
            int halsteadEffortRounded,
            int swallowedExceptionSmells,
            int genericExceptionSmells,
            int rawTypeUsage,
            int stringConcatInLoop,
            int hardcodedLiteralCount,
            int godClassIndicator) {

        public static ClassScanValues structuralOnly(int codeLines, int wmc, int publicMethodCount,
                                                     int efferentCouplingProxy) {
            return new ClassScanValues(codeLines, wmc, publicMethodCount, efferentCouplingProxy,
                    0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
    }

    public Optional<ThresholdTriple> threshold(String dimensionId) {
        return methodDimensions.stream()
                .filter(d -> d.id().equals(dimensionId))
                .findFirst()
                .map(ScoredDimension::thresholds);
    }

    /** Legacy v1 constants (no YAML). */
    public static RiskProfile v1Legacy() {
        return new RiskProfile(
                "v1-legacy",
                "v1",
                RiskCalculator.LEGACY_COMPOUND_FACTOR,
                0.65,
                0.35,
                List.of(
                        dim("branching", "Cyclomatic complexity", 0.40,
                                10, 15, 30, MethodScanValues::cyclomaticComplexity),
                        dim("length", "Lines of code", 0.20,
                                50, 100, 200, MethodScanValues::codeLines),
                        dim("nesting", "Nesting depth", 0.25,
                                3, 4, 8, MethodScanValues::maxNestingDepth),
                        dim("parameters", "Parameter count", 0.15,
                                5, 7, 12, MethodScanValues::parameterCount)),
                List.of(),
                List.of(),
                Map.of(
                        "branching", new ThresholdTriple(10, 15, 30),
                        "length", new ThresholdTriple(50, 100, 200),
                        "parameters", new ThresholdTriple(5, 7, 12)));
    }

    private static ScoredDimension<MethodScanValues> dim(
            String id, String label, double weight, int med, int high, int crit,
            ToIntFunction<MethodScanValues> value) {
        return new ScoredDimension<>(id, label, weight, new ThresholdTriple(med, high, crit), value);
    }
}
