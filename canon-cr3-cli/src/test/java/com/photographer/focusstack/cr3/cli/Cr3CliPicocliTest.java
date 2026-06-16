package com.photographer.focusstack.cr3.cli;

import com.photographer.focusstack.cr3.testutil.Cr3TestData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class Cr3CliPicocliTest {

    @TempDir
    Path tempDir;

    @Test
    void helpExitsZero() {
        int exitCode = new CommandLine(new Cr3CliApp()).execute("--help");
        assertThat(exitCode).isZero();
    }

    @Test
    void missingInputFileExitsTwo() {
        Path missing = tempDir.resolve("missing.cr3");
        int exitCode = new CommandLine(new Cr3CliApp()).execute("info", missing.toString());
        assertThat(exitCode).isEqualTo(2);
    }

    @Test
    void missingInputForPedestalReportExitsTwo() {
        Path missing = tempDir.resolve("missing.cr3");
        int exitCode = new CommandLine(new Cr3CliApp()).execute("pedestal-report", missing.toString());
        assertThat(exitCode).isEqualTo(2);
    }

    @Test
    void missingBitsFileExitsTwo() {
        Path missing = tempDir.resolve("missing.bits");
        int exitCode = new CommandLine(new Cr3CliApp()).execute("subband-stats", "--bits", missing.toString());
        assertThat(exitCode).isEqualTo(2);
    }

    @Test
    void validInputExitsZero() throws Exception {
        Path sample = tempDir.resolve("sample.cr3");
        Files.write(sample, Cr3TestData.buildMinimalCr3WithJpeg());

        int exitCode = new CommandLine(new Cr3CliApp()).execute("info", sample.toString());
        assertThat(exitCode).isZero();
    }
}
