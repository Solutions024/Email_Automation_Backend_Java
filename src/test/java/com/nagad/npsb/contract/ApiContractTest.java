package com.nagad.npsb.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nagad.npsb.api.ApiTestServer;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Black-box HTTP contract of the backend: runs ContractScenario against a freshly started backend (its own
 * in-memory database) and compares every answer with src/test/resources/contract/api-snapshot.json.
 *
 *   ./gradlew contractTest                          compare with the snapshot (fails listing every difference)
 *   ./gradlew contractTest -PcontractRecord=true    record the snapshot again (after an intended API change)
 *
 * The same scenario and snapshot are meant to be run against a reimplementation of the backend (only
 * ApiTestServer.url() and ApiTestServer.tokenFor() need to exist there). The new run is always written to
 * build/contract/api-snapshot.actual.json, and the differences to build/contract/contract-report.txt.
 */
class ApiContractTest {

    private static Path path(String property, String fallback) {
        return Paths.get(System.getProperty(property, fallback)).toAbsolutePath();
    }

    private static boolean recordMode() {
        String v = System.getProperty("npsb.contract.record");
        if (v == null || v.isBlank()) v = System.getenv("CONTRACT_RECORD");
        return v != null && (v.equalsIgnoreCase("true") || v.equals("1") || v.equalsIgnoreCase("yes"));
    }

    @Test
    void theApiAnswersAsRecorded() throws Exception {
        Path snapshotFile = path("npsb.contract.snapshot", "src/test/resources/contract/api-snapshot.json");
        Path actualFile = path("npsb.contract.actual", "build/contract/api-snapshot.actual.json");
        Path reportFile = path("npsb.contract.report", "build/contract/contract-report.txt");
        boolean record = recordMode();

        String base = ApiTestServer.url();
        ObjectNode actual;
        boolean aborted = false;
        try (FakeSmtpServer smtp = new FakeSmtpServer()) {
            Normalizer norm = new Normalizer();
            norm.literal(smtp.port(), "<smtp-port>");
            ContractRecorder rec = new ContractRecorder(base, norm);
            try {
                new ContractScenario(rec, smtp).run();
            } catch (Throwable t) {
                rec.aborted(t);
                aborted = true;
                t.printStackTrace();
            }
            actual = rec.snapshot();
        }
        String actualText = ContractRecorder.pretty(actual);
        Files.createDirectories(actualFile.getParent());
        Files.writeString(actualFile, actualText, StandardCharsets.UTF_8);

        if (record) {
            Files.createDirectories(snapshotFile.getParent());
            Files.writeString(snapshotFile, actualText, StandardCharsets.UTF_8);
            System.out.println("Contract snapshot recorded: " + snapshotFile + " (" + actual.path("steps").size() + " steps)");
            if (aborted) fail("The scenario stopped early (see the \"scenario aborted\" step in " + snapshotFile + ")");
            return;
        }

        if (!Files.exists(snapshotFile)) {
            fail("No contract snapshot at " + snapshotFile + ". Record it with: ./gradlew contractTest -PcontractRecord=true");
        }
        JsonNode expected = ContractRecorder.JSON.readTree(Files.readString(snapshotFile, StandardCharsets.UTF_8));
        List<String> differences = SnapshotDiff.compare(expected, actual);
        Files.createDirectories(reportFile.getParent());
        if (differences.isEmpty()) {
            Files.writeString(reportFile, "The API matches the contract snapshot (" + actual.path("steps").size() + " steps).\n",
                    StandardCharsets.UTF_8);
            return;
        }
        StringBuilder report = new StringBuilder();
        report.append(differences.size()).append(" step(s) differ from the contract snapshot ").append(snapshotFile).append('\n')
                .append("New run: ").append(actualFile).append('\n')
                .append("If the change is intended, record again: ./gradlew contractTest -PcontractRecord=true\n");
        for (String d : differences) report.append('\n').append(d).append('\n');
        Files.writeString(reportFile, report.toString(), StandardCharsets.UTF_8);
        fail(report.toString());
    }
}
