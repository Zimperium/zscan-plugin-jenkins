package com.zimperium.plugins.zDevJenkinsUploadPlugin;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.util.Secret;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestBuilder;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@WithJenkins
class ZDevUploadPluginEndToEndTest {

    private static final String LOGIN = "/api/auth/v1/api_keys/login";
    private static final String REFRESH = "/api/auth/v1/api_keys/access";
    private static final String UPLOAD = "/api/zdev-upload/public/v1/uploads/build";
    private static final String STATUS = "/api/zdev-app/public/v1/assessments/status";
    private static final String REPORT = "/api/zdev-app/public/v1/assessments/a1/json";
    private static final String TEAMS = "/api/auth/public/v1/teams";
    private static final String ASSIGN = "/api/zdev-app/public/v1/apps/app-1/upload";

    private static final String TOKENS = "{\"accessToken\":\"access\",\"refreshToken\":\"refresh\"}";
    private static final String UPLOADED = "{\"buildId\":\"build-123\",\"zdevAppId\":\"app-1\",\"teamId\":\"team-1\"}";

    private HttpServer server;
    private final Map<String, Deque<String[]>> scripted = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
    private final Map<String, String> lastBodies = new ConcurrentHashMap<>();

    @BeforeEach
    void startFakeConsole() throws IOException {
        ZDevUploadPlugin.pollIntervalMillis = 10;
        ZDevUploadPlugin.statusTimeoutMillis = 2_000;
        ZDevUploadPlugin.reportDownloadTimeoutMillis = 2_000;

        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.start();

        script(LOGIN, 200, TOKENS);
        script(REFRESH, 200, TOKENS);
        script(UPLOAD, 200, UPLOADED);
    }

    @AfterEach
    void stopFakeConsole() {
        server.stop(0);
        ZDevUploadPlugin.pollIntervalMillis = ZDevUploadPlugin.checkInterval * 1000;
        ZDevUploadPlugin.statusTimeoutMillis = ZDevUploadPlugin.reportTimeout * 1000L;
        ZDevUploadPlugin.reportDownloadTimeoutMillis = ZDevUploadPlugin.reportDownloadTimeout;
    }

    @Test
    void failedAnalysisFailsTheBuildImmediately(JenkinsRule j) throws Exception {
        script(STATUS, 200, status("Pending"));
        script(STATUS, 200, "{\"id\":\"a1\",\"zdevMetadata\":{\"analysis\":\"Failed\",\"reason\":\"boom\"}}");

        FreeStyleBuild build = runBuild(j);

        j.assertBuildStatus(Result.FAILURE, build);
        assertEquals(2, calls(STATUS));
        assertEquals(0, calls(REPORT));
        j.assertLogContains("Upload buildId: build-123", build);
        j.assertLogContains("zScan analysis reported Failed for buildId build-123", build);
        j.assertLogContains("\"reason\":\"boom\"", build);
    }

    @Test
    void reportDownloadRetriesUntilTheReportIsAvailable(JenkinsRule j) throws Exception {
        script(STATUS, 503, "unavailable");
        script(STATUS, 200, status("Pending"));
        script(STATUS, 200, "{\"id\":\"a1\",\"zdevMetadata\":{\"analysis\":\"Done\"}}");
        script(REPORT, 425, "too early");
        script(REPORT, 404, "not found");
        script(REPORT, 200, "{\"report\":true}");

        FreeStyleBuild build = runBuild(j);

        j.assertBuildStatus(Result.SUCCESS, build);
        assertEquals(3, calls(REPORT));
        FilePath report = j.jenkins.getWorkspaceFor(build.getParent()).child("zscan-report-a1.json");
        assertTrue(report.exists());
        assertEquals("{\"report\":true}", report.readToString());
    }

    @Test
    void statusTimeoutMarksTheBuildUnstable(JenkinsRule j) throws Exception {
        ZDevUploadPlugin.statusTimeoutMillis = 300;
        script(STATUS, 200, status("Pending"));

        FreeStyleBuild build = runBuild(j);

        j.assertBuildStatus(Result.UNSTABLE, build);
        j.assertLogContains("waiting for the assessment of buildId build-123", build);
        assertEquals(0, calls(REPORT));
    }

    @Test
    void repeatedMalformedStatusMarksTheBuildUnstable(JenkinsRule j) throws Exception {
        script(STATUS, 200, "{\"unexpected\":true}");

        FreeStyleBuild build = runBuild(j);

        j.assertBuildStatus(Result.UNSTABLE, build);
        assertEquals(ZDevUploadPlugin.MAX_CONSECUTIVE_MALFORMED_STATUS, calls(STATUS));
        j.assertLogContains("Giving up on assessment status for buildId build-123", build);
    }

    @Test
    void uploadResponseWithoutOptionalFieldsDoesNotCrash(JenkinsRule j) throws Exception {
        scripted.get(UPLOAD).clear();
        script(UPLOAD, 200, "{\"buildId\":\"build-123\",\"teamId\":\"team-1\"}");
        script(STATUS, 200, "{\"id\":\"a1\",\"zdevMetadata\":{\"analysis\":\"Done\"}}");
        script(REPORT, 200, "{}");

        FreeStyleBuild build = runBuild(j);

        j.assertBuildStatus(Result.SUCCESS, build);
        j.assertLogContains("Upload buildId: build-123, zdevAppId: , teamId: team-1", build);
    }

    @Test
    void appWithoutTeamIsAssignedToDefaultWhenTeamNameIsOmitted(JenkinsRule j) throws Exception {
        scripted.get(UPLOAD).clear();
        script(UPLOAD, 200, "{\"buildId\":\"build-123\",\"zdevAppId\":\"app-1\",\"teamId\":null}");
        script(TEAMS, 200, "{\"content\":[{\"id\":\"t-other\",\"name\":\"Other\"},{\"id\":\"t-default\",\"name\":\"Default\"}]}");
        script(ASSIGN, 200, "{}");
        script(STATUS, 200, "{\"id\":\"a1\",\"zdevMetadata\":{\"analysis\":\"Done\"}}");
        script(REPORT, 200, "{}");

        FreeStyleBuild build = runBuild(j, null);

        j.assertBuildStatus(Result.SUCCESS, build);
        assertEquals(1, calls(ASSIGN));
        assertEquals("{\"teamId\":\"t-default\"}", lastBodies.get(ASSIGN));
        j.assertLogContains("Assigning it to the Default team", build);
    }

    private FreeStyleBuild runBuild(JenkinsRule j) throws Exception {
        return runBuild(j, "Default");
    }

    private FreeStyleBuild runBuild(JenkinsRule j, String teamName) throws Exception {
        FreeStyleProject project = j.createFreeStyleProject();
        project.getBuildersList().add(new TestBuilder() {
            @Override
            public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
                    throws InterruptedException, IOException {
                build.getWorkspace().child("app.apk").write("fake apk", "UTF-8");
                return true;
            }
        });
        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        project.getPublishersList().add(new ZDevUploadPlugin(true, endpoint, "client", Secret.fromString("secret"), false,
                "*.apk", "", true, ZDevUploadPlugin.ReportFormat.JSON, "zscan-report.json", teamName));
        return project.scheduleBuild2(0).get();
    }

    private static String status(String analysis) {
        return "{\"zdevMetadata\":{\"analysis\":\"" + analysis + "\"}}";
    }

    private void script(String path, int code, String body) {
        scripted.computeIfAbsent(path, k -> new ArrayDeque<>()).add(new String[] {String.valueOf(code), body});
    }

    private int calls(String path) {
        AtomicInteger count = calls.get(path);
        return count == null ? 0 : count.get();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        calls.computeIfAbsent(path, k -> new AtomicInteger()).incrementAndGet();
        try (InputStream in = exchange.getRequestBody()) {
            lastBodies.put(path, new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }

        int code = 404;
        String body = "{}";
        Deque<String[]> queue = scripted.get(path);
        if (queue != null) {
            synchronized (queue) {
                // the last scripted response repeats once the queue is drained
                String[] next = queue.size() > 1 ? queue.poll() : queue.peek();
                if (next != null) {
                    code = Integer.parseInt(next[0]);
                    body = next[1];
                }
            }
        }

        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
