package com.zimperium.plugins.zDevJenkinsUploadPlugin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZDevUploadPluginTest {

    @Test
    void detectsFailedAssessmentStatus() {
        JsonObject failedStatus = JsonParser.parseString(
                "{\"id\":\"assessment-1\",\"zdevMetadata\":{\"analysis\":\"Failed\"}}")
                .getAsJsonObject();
        JsonObject completedStatus = JsonParser.parseString(
                "{\"id\":\"assessment-1\",\"zdevMetadata\":{\"analysis\":\"Done\"}}")
                .getAsJsonObject();

        assertTrue(ZDevUploadPlugin.isFailedAnalysis(failedStatus));
        assertFalse(ZDevUploadPlugin.isFailedAnalysis(completedStatus));
        assertFalse(ZDevUploadPlugin.isFailedAnalysis(new JsonObject()));
    }

    @Test
    void interpretsAssessmentStatusPayloads() {
        assertEquals(ZDevUploadPlugin.AnalysisState.PENDING, interpret("{\"zdevMetadata\":{\"analysis\":\"Pending\"}}"));
        assertEquals(ZDevUploadPlugin.AnalysisState.PENDING, interpret("{\"zdevMetadata\":{\"analysis\":\"Working\"}}"));
        assertEquals(ZDevUploadPlugin.AnalysisState.DONE, interpret("{\"id\":\"a1\",\"zdevMetadata\":{\"analysis\":\"Done\"}}"));
        assertEquals(ZDevUploadPlugin.AnalysisState.FAILED, interpret("{\"zdevMetadata\":{\"analysis\":\"Failed\"}}"));
        assertEquals(ZDevUploadPlugin.AnalysisState.MALFORMED, interpret("{\"zdevMetadata\":{\"analysis\":\"Done\"}}"));
        assertEquals(ZDevUploadPlugin.AnalysisState.MALFORMED, interpret("{\"zdevMetadata\":{}}"));
        assertEquals(ZDevUploadPlugin.AnalysisState.MALFORMED, interpret("{\"zdevMetadata\":null}"));
        assertEquals(ZDevUploadPlugin.AnalysisState.MALFORMED, interpret("[]"));
        assertEquals(ZDevUploadPlugin.AnalysisState.MALFORMED, interpret("not json {"));
        assertEquals(ZDevUploadPlugin.AnalysisState.MALFORMED, interpret(""));
    }

    @Test
    void readsOptionalStringsSafely() {
        JsonObject object = JsonParser.parseString("{\"a\":\"x\",\"b\":null,\"c\":{}}").getAsJsonObject();
        assertEquals("x", ZDevUploadPlugin.getStringOrEmpty(object, "a"));
        assertEquals("", ZDevUploadPlugin.getStringOrEmpty(object, "b"));
        assertEquals("", ZDevUploadPlugin.getStringOrEmpty(object, "c"));
        assertEquals("", ZDevUploadPlugin.getStringOrEmpty(object, "missing"));
        assertEquals("", ZDevUploadPlugin.getStringOrEmpty(null, "a"));
    }

    @Test
    void retriesTransientStatusErrors() {
        assertTrue(ZDevUploadPlugin.shouldRetryForStatus(404));
        assertTrue(ZDevUploadPlugin.shouldRetryForStatus(429));
        assertTrue(ZDevUploadPlugin.shouldRetryForStatus(503));
        assertFalse(ZDevUploadPlugin.shouldRetryForStatus(401));
        assertFalse(ZDevUploadPlugin.shouldRetryForStatus(403));
    }

    private static ZDevUploadPlugin.AnalysisState interpret(String text) {
        return ZDevUploadPlugin.interpretStatus(ZDevUploadPlugin.parseJsonObject(text));
    }

    @Test
    void blankTeamNameFallsBackToDefault() {
        assertEquals("Default", ZDevUploadPlugin.effectiveTeamName(null));
        assertEquals("Default", ZDevUploadPlugin.effectiveTeamName("  "));
        assertEquals("Mobile", ZDevUploadPlugin.effectiveTeamName(" Mobile "));
    }

    @Test
    void retriesReportDownloadsWhileArtifactsMayStillBeMaterializing() {
        assertTrue(ZDevUploadPlugin.shouldRetryForReportDownload(404));
        assertTrue(ZDevUploadPlugin.shouldRetryForReportDownload(408));
        assertTrue(ZDevUploadPlugin.shouldRetryForReportDownload(425));
        assertTrue(ZDevUploadPlugin.shouldRetryForReportDownload(429));
        assertTrue(ZDevUploadPlugin.shouldRetryForReportDownload(503));
        assertFalse(ZDevUploadPlugin.shouldRetryForReportDownload(400));
        assertFalse(ZDevUploadPlugin.shouldRetryForReportDownload(401));
    }
}
