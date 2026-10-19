package com.scanner.complexity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliOutputPathCoalesceTest {

    @TempDir
    Path temp;

    @Test
    void emptyOutputFlagDoesNotCrashAndWritesUnderAnalysisOutput() throws Exception {
        Path scanRoot = temp.resolve("proj");
        Files.createDirectories(scanRoot);
        int code = Java6CodeAnalyzerMain.run(new String[]{
                "--path=" + scanRoot,
                "--output=",
                "--markdown=",
                "--no-default-ignores",
        });
        assertTrue(code == 0 || code == 2 || code == 3);
        assertTrue(Files.isRegularFile(scanRoot.resolve("analysis-output/standalone.json")));
        assertTrue(Files.isRegularFile(scanRoot.resolve("analysis-output/parser-raporu.md")));
    }

    @Test
    void noOutputFlagsLeavesStdoutModeWithoutDefaultFiles() throws Exception {
        Path scanRoot = temp.resolve("empty");
        Files.createDirectories(scanRoot);
        Path outJson = scanRoot.resolve("analysis-output/standalone.json");
        int code = Java6CodeAnalyzerMain.run(new String[]{
                "--path=" + scanRoot,
        });
        assertTrue(code == 0 || code == 2 || code == 3);
        assertEquals(false, Files.exists(outJson));
    }
}
