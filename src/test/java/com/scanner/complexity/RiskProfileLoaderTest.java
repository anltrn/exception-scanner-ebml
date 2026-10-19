package com.scanner.complexity;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RiskProfileLoaderTest {

    @Test
    void loadsEnterpriseJavaProfile() throws Exception {
        Path config = Path.of("src/main/resources/config/risk-parameters-proposal.yaml");
        RiskProfile profile = RiskProfileLoader.load("enterprise-java", config);
        assertTrue(profile.modelVersion().startsWith("v3"));
        assertEquals(25, profile.methodDimensions().size());
        assertEquals(6, profile.classDimensions().size());
        assertTrue(profile.methodDimensions().stream().anyMatch(d -> "cognitive".equals(d.id())));
    }

    @Test
    void enterpriseProfileChangesScoreVsLegacy() throws Exception {
        Path config = Path.of("src/main/resources/config/risk-parameters-proposal.yaml");
        RiskCalculator legacy = new RiskCalculator();
        RiskCalculator enterprise = new RiskCalculator(RiskProfileLoader.load("enterprise-java", config));
        MethodScanValues sample = new MethodScanValues(12, 20, 5, 4, 16, 21, 6, 1, 3, 9, 0, 0, 4, 2, 0, 0, 0, 5, 2,
                0, 0, 0, 0, 0, 0, 0);
        double legacyScore = legacy.assessMethod(sample).score();
        double enterpriseScore = enterprise.assessMethod(sample).score();
        assertTrue(Math.abs(enterpriseScore - legacyScore) > 1.0,
                "legacy=" + legacyScore + " enterprise=" + enterpriseScore);
        assertTrue(enterprise.buildRiskModel().version().startsWith("v3"));
        assertEquals("v1", legacy.buildRiskModel().version());
    }
}
