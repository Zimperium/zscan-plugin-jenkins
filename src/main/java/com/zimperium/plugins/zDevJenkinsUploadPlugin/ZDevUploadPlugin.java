package com.zimperium.plugins.zDevJenkinsUploadPlugin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.zimperium.plugins.zDevJenkinsUploadPlugin.dtos.LoginCredentials;
import com.zimperium.plugins.zDevJenkinsUploadPlugin.dtos.LoginResponse;
import com.zimperium.plugins.zDevJenkinsUploadPlugin.dtos.RefreshCredentials;
import com.zimperium.plugins.zDevJenkinsUploadPlugin.services.UploadPluginService;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.AbstractProject;
import hudson.model.Item;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Publisher;
import hudson.tasks.Recorder;
import hudson.util.FormValidation;
import hudson.util.Secret;
import jenkins.model.Jenkins;
import jenkins.tasks.SimpleBuildStep;
import net.sf.json.JSONObject;
import okhttp3.*;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.Symbol;
import org.apache.commons.io.FilenameUtils;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.verb.POST;
import retrofit2.Call;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.Map;


public class ZDevUploadPlugin extends Recorder implements SimpleBuildStep{

    // constants
    public final static String toolId = "JKNS";
    public final static String toolName = "Jenkins";
    public final static long checkInterval = 30;
    public final static int reportTimeout = 1200;
    public final static int connectionTimeout = 2 * 60 * 1000;
    public final static int writeTimeout = 2 * 60 * 1000;
    public final static int readTimeout = 60 * 1000;
    public final static long reportDownloadTimeout = 20 * 60 * 1000;
    public final static int MAX_CONSECUTIVE_MALFORMED_STATUS = 3;

    // Effective timings in milliseconds; package-private so tests can shorten them.
    static long pollIntervalMillis = checkInterval * 1000;
    static long statusTimeoutMillis = reportTimeout * 1000L;
    static long reportDownloadTimeoutMillis = reportDownloadTimeout;

    public final static Result DEFAULT_STEP_RESULT = Result.FAILURE;
    public final static int MAX_FILES_UPLOAD = 5;
    public final static String DEFAULT_REPORT_FILE = "zscan-report.json";
    public final static String DEFAULT_TEAM_NAME = "Default";

    // plugin settings
    // console information
    private Boolean useOwnConsoleInfo;
    private String endpoint;
    private String clientId;
    private Secret clientSecret;
    private Boolean useProxy;

    // binaries
    private String sourceFile;
    private String excludedFile;

    // report settings
    private Boolean waitForReport;
    private ReportFormat reportFormat;
    private String reportFileName;

    // advanced settings
    private String teamName;

    @DataBoundConstructor
    public ZDevUploadPlugin(Boolean useOwnConsoleInfo, String endpoint, String clientId, Secret clientSecret, Boolean useProxy, 
                            String sourceFile, String excludedFile,
                            Boolean waitForReport, ReportFormat reportFormat, String reportFileName, 
                            String teamName) {
        this.useOwnConsoleInfo = useOwnConsoleInfo;
        this.endpoint = endpoint;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.useProxy = useProxy;
        this.sourceFile = sourceFile;
        this.excludedFile = excludedFile;
        this.waitForReport = waitForReport;
        this.reportFormat = reportFormat;
        this.reportFileName = reportFileName;
        this.teamName = teamName;
    }


    @DataBoundSetter
    public void setUseOwnConsoleInfo(Boolean useOwnConsoleInfo) {
        this.useOwnConsoleInfo = useOwnConsoleInfo;
    }
    public Boolean getUseOwnConsoleInfo() {
        return useOwnConsoleInfo;
    }

    @DataBoundSetter
    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }
    public String getEndpoint() {
        return endpoint;
    }

    @DataBoundSetter
    public void setClientId(String clientId) {
        this.clientId = clientId;
    }
    public String getClientId() {
        return clientId;
    }

    @DataBoundSetter
    public void setClientSecret(Secret clientSecret) {
        this.clientSecret = clientSecret;
    }
    public Secret getClientSecret() {
        return clientSecret;
    }

    @DataBoundSetter
    public void setUseProxy(Boolean useProxy) {
        this.useProxy = useProxy;
    }
    public Boolean getUseProxy() {
        return useProxy;
    }

    @DataBoundSetter
    public void setSourceFile(String sourceFile) {
        this.sourceFile = sourceFile;
    }
    public String getSourceFile() {
        return sourceFile;
    }

    @DataBoundSetter
    public void setExcludedFile(String excludedFile) {
        this.excludedFile = excludedFile;
    }
    public String getExcludedFile() {
        return excludedFile;
    }

    @DataBoundSetter
    public void setWaitForReport(Boolean waitForReport) {
        this.waitForReport = waitForReport;
    }
    public Boolean getWaitForReport() {
        return waitForReport;
    }

    @DataBoundSetter
    public void setReportFormat(ReportFormat reportFormat) {
        this.reportFormat = reportFormat;
    }
    public ReportFormat getReportFormat() {
        return reportFormat;
    }

    @DataBoundSetter
    public void setReportFileName(String reportFileName) {
        this.reportFileName = reportFileName;
    }
    public String getReportFileName() {
        return reportFileName;
    }

    @DataBoundSetter
    public void setTeamName(String teamName) {
        this.teamName = teamName;
    }
    public String getTeamName() {
        return teamName;
    }

    private void log(final PrintStream logger, final String message) {
        logger.println(StringUtils.defaultString("[" + getDescriptor().getDisplayName()) + "] " + message);
    }

    @Override
    public void perform(Run<?, ?> run, FilePath workspace, Launcher launcher, TaskListener listener) throws InterruptedException, IOException {
        final PrintStream console = listener.getLogger();

        // check if this step needs to run
        if (Result.ABORTED.equals(run.getResult())) {
            log(console, "Cancelling uploads to zScan as build was aborted");
            return;
        }

        if (Result.FAILURE.equals(run.getResult())) {
            log(console, "Cancelling uploads to zScan as build failed");
            return;
        }

        // Check if there is anything to upload
        final Map<String, String> envVars = run.getEnvironment(listener);

        final String expanded = Util.replaceMacro(sourceFile, envVars);
        final String excluded = Util.replaceMacro(excludedFile, envVars);

        // if we did not find anything to upload, return error
        if (expanded == null) {
            log(console, "Found nothing to upload!");
            run.setResult(DEFAULT_STEP_RESULT);
            return;
        }

        String effectiveEndpoint = (useOwnConsoleInfo) ? endpoint : getDescriptor().getGlobalEndpoint();
        String effectiveClientId = (useOwnConsoleInfo) ? clientId : getDescriptor().getGlobalClientId();
        Secret effectiveClientSecret = (useOwnConsoleInfo) ? clientSecret : getDescriptor().getGlobalClientSecret();
        Boolean effectiveUseProxy = (useOwnConsoleInfo) ? useProxy : getDescriptor().getGlobalUseProxy();

        // create HTTP helper objects
        OkHttpClient okHttpClient = new ZDevHTTPClient().getHttpClient(effectiveUseProxy, connectionTimeout, writeTimeout, readTimeout);

        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(effectiveEndpoint)
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build();

        UploadPluginService service = retrofit.create(UploadPluginService.class);

        // Login and obtain a token
        Call<LoginResponse> loginResponseCall = service.login(new LoginCredentials(effectiveClientId, Secret.toString(effectiveClientSecret)));
        Response<LoginResponse> response = loginResponseCall.execute();
        LoginResponse loginResponseBody = response.body();

        if (!response.isSuccessful() || loginResponseBody == null) {
            log(console, "Unable to login with provided the credentials to " + effectiveEndpoint);
            run.setResult(DEFAULT_STEP_RESULT);
            return;
        }

        String accessToken = loginResponseBody.getAccessToken();
        String refreshToken = loginResponseBody.getRefreshToken();
        String authToken = "Bearer " + accessToken;

        int totalCount = 0;
        boolean assessmentFailed = false;
        for (String startPath : expanded.split(",")) {
            if (assessmentFailed) {
                break;
            }
            for (FilePath path : workspace.list(startPath, excluded)) {

                // We don't support uploading directories
                if (path.isDirectory()) {
                    log(console, path.getBaseName() + " is a directory. Skipping.");
                    continue;
                }

                String fileName = path.getName();

                FilePath localPath = null;
                if(path.isRemote()) {
                    // copy locally
                    localPath = new FilePath(File.createTempFile(fileName, ".apk"));
                    localPath.copyFrom(path);
                }
                else {
                    localPath = path;
                }

                File file = new File(localPath.getRemote());
                log(console, "Uploading " + file.getName() + " (" + file.getAbsolutePath() + ") to " + effectiveEndpoint);

                String branchName = (envVars.get("BRANCH_NAME") != null) ? envVars.get("BRANCH_NAME") : "";
                String buildNumber = (envVars.get("BUILD_NUMBER") != null) ? envVars.get("BUILD_NUMBER") : "";

                MultipartBody.Builder multipartBody = new MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("ciToolId", toolId)
                        .addFormDataPart("ciToolName", toolName)
                        .addFormDataPart("branchName", branchName)
                        .addFormDataPart("buildNumber", buildNumber)
                        .addFormDataPart("buildFile", file.getName(), RequestBody.create(file, MediaType.parse("multipart/form-data")));
                RequestBody requestBody = multipartBody.build();
                Call<ResponseBody> uploadCall = service.upload(authToken, requestBody);

                long start = System.currentTimeMillis();
                Response<ResponseBody> uploadResponse = uploadCall.execute();
                long end = System.currentTimeMillis();

                if (uploadResponse.isSuccessful()) {
                    log(console, "Successfully uploaded " + fileName + " to " + effectiveEndpoint + " (" + (end - start) + "ms)");
                    try(ResponseBody uploadResponseBody = uploadResponse.body()) {
                        // we're inside the try() block; exceptions will be caught  
                        @SuppressWarnings("null")
                        JsonObject jsonObject = JsonParser.parseString(uploadResponseBody.string()).getAsJsonObject();

                        // Extract the appId needed for team assignment, buildId to check report status, and the current team 
                        String zdevAppId = getStringOrEmpty(jsonObject, "zdevAppId");
                        String teamId = getStringOrEmpty(jsonObject, "teamId");
                        String buildId = getStringOrEmpty(jsonObject, "buildId");
                        log(console, "Upload buildId: " + buildId + ", zdevAppId: " + zdevAppId
                                + ", teamId: " + (teamId.isEmpty() ? "(none)" : teamId));

                        // If teamID is empty, find the correct team id by name
                        if(teamId.isEmpty()) {
                            final String effectiveTeamName = effectiveTeamName(teamName);
                            log(console, "Application " + zdevAppId + " does not belong to a team. Assigning it to the " + effectiveTeamName + " team.");

                            // need to wait a bit; otherwise we can get 404
                            pause(pollIntervalMillis);

                            // get the list of teams to figure out team id
                            Call<ResponseBody> teamsCall = service.listTeams(authToken);
                            Response<ResponseBody> teamList = teamsCall.execute();
                            try {
                                ResponseBody teamListBody = teamList.body();
                                if(teamList.isSuccessful() && teamListBody != null) {
                                    JsonObject teamsObject = JsonParser.parseString(teamListBody.string()).getAsJsonObject();
                                    if(teamsObject != null && !teamsObject.isJsonNull() && !teamsObject.isEmpty() && teamsObject.get("content").isJsonArray()) {
                                        JsonArray teamArray = teamsObject.get("content").getAsJsonArray();
                                        log(console, "Found " + teamArray.size() + " teams");
                                        for (JsonElement teamElement : teamArray) {
                                            String name = teamElement.getAsJsonObject().get("name").getAsString();
                                            // log(console, "Team " + name);
                                            if(name.equals(effectiveTeamName)){
                                                teamId = teamElement.getAsJsonObject().get("id").getAsString();
                                                //log(console, "Found team with ID: " + teamId);
                                                break;
                                            }
                                        }
                                        
                                        // if we did not find the specified team, try 'Default'
                                        if(teamId.isEmpty() && !effectiveTeamName.equals(DEFAULT_TEAM_NAME)) {
                                            log(console, "Team " + effectiveTeamName + " not found.  Trying the 'Default' team.");
                                            for (JsonElement teamElement : teamArray) {
                                                String name = teamElement.getAsJsonObject().get("name").getAsString();
                                                // log(console, "Team " + name);
                                                if(name.equals(DEFAULT_TEAM_NAME)){
                                                    teamId = teamElement.getAsJsonObject().get("id").getAsString();
                                                    log(console, "Found team with ID: " + teamId);
                                                    break;
                                                }
                                            }
                                        }

                                        // Assign the app to the team
                                        if(!teamId.isEmpty()) {
                                            // create payload in the json format {"teamId": ""}
                                            JsonObject jsonPayload = new JsonObject();
                                            jsonPayload.addProperty("teamId", teamId);

                                            RequestBody assignRequestBody = RequestBody.create(jsonPayload.toString(), MediaType.parse("application/json"));
                                            Call<ResponseBody> assignCall = service.assignTeam(zdevAppId, authToken, assignRequestBody);
                                            Response<ResponseBody> assignResponse = assignCall.execute();

                                            if(assignResponse.isSuccessful()) {
                                                log(console, "Successfully assigned application to team.");
                                            }
                                            else {
                                                log(console, "Unable to assign this app to a team.  Please review team name setting and retry.");
                                                ResponseBody assignResponseBody = assignResponse.errorBody();
                                                if(assignResponseBody != null) {
                                                    log(console, "HTTP " + assignResponse.code() + ": " + assignResponseBody.string());
                                                }
                                            }
                                        }
                                        else {
                                            log(console, "Unable to assign this app to a team.  Please review team name setting and retry.");
                                        }
                                    }
                                    else {
                                        log(console, "Unable to assign this app to a team.  Unexpected response from the server.");
                                        teamListBody = teamList.body();
                                        if(teamListBody != null) {
                                            log(console, "HTTP " + teamList.code() + ": " + teamListBody.string());
                                        }
                                    }
                                }
                                else {
                                    log(console, "Unable to assign this app to a team.  Please review team name setting and credentials, and retry.");
                                    teamListBody = teamList.errorBody();
                                    if(teamListBody != null) {
                                        log(console, "HTTP " + teamList.code() + ": " + teamListBody.string());
                                    }
                                }
                            }
                            catch(RuntimeException e) {
                                log(console, "Unexpected runtime exception: " + e.getLocalizedMessage());
                                throw e;
                            }
                            catch(Exception e) {
                                log(console, "Error processing team list: " + e.getLocalizedMessage());
                                run.setResult(Result.UNSTABLE);
                            }
                        }
                        else {
                            log(console, "Upload response reports application " + zdevAppId + " is already assigned to team " + teamId + "; skipping team assignment.");
                        }

                        // report may have taken a long time; refresh the access token
                        Call<LoginResponse> refreshTokenCall = service.refreshAccess(new RefreshCredentials(refreshToken));
                        Response<LoginResponse> tokenResponse = refreshTokenCall.execute();
                        LoginResponse tokenResponseBody = tokenResponse.body();
                        if (!tokenResponse.isSuccessful() || tokenResponseBody == null) {
                            log(console, "Unable to refresh access token. Will continue using the original.");
                        }
                        else {
                            String newAccessToken = tokenResponseBody.getAccessToken();
                            refreshToken = tokenResponseBody.getRefreshToken();
                            authToken = "Bearer " + newAccessToken;
                        }
                        
                        String assessmentId = "";
                        // wait for the report, if configured
                        if(waitForReport) {
                            long statusDeadline = System.currentTimeMillis() + statusTimeoutMillis;
                            int consecutiveMalformed = 0;
                            boolean statusError = false;
                            String lastStatusText = null;
                            while( System.currentTimeMillis() < statusDeadline ) {
                                Call<ResponseBody> statusCall = service.checkStatus(buildId, authToken);
                                Response<ResponseBody> statusResponse = statusCall.execute();
                                if(statusResponse.isSuccessful()) {
                                    String statusText;
                                    try(ResponseBody statusBody = statusResponse.body()) {
                                        statusText = (statusBody != null) ? statusBody.string() : "";
                                    }
                                    lastStatusText = statusText;
                                    JsonObject statusObject = parseJsonObject(statusText);
                                    AnalysisState state = interpretStatus(statusObject);

                                    if(state == AnalysisState.MALFORMED) {
                                        consecutiveMalformed++;
                                        log(console, "Unexpected assessment status response (" + consecutiveMalformed + "/"
                                                + MAX_CONSECUTIVE_MALFORMED_STATUS + "): " + statusText);
                                        if(consecutiveMalformed >= MAX_CONSECUTIVE_MALFORMED_STATUS) {
                                            log(console, "Giving up on assessment status for buildId " + buildId + ".");
                                            run.setResult(Result.UNSTABLE);
                                            statusError = true;
                                            break;
                                        }
                                    }
                                    else {
                                        consecutiveMalformed = 0;
                                        log(console, "Scan status = " + getAnalysis(statusObject));

                                        if(state == AnalysisState.FAILED) {
                                            log(console, "zScan analysis reported Failed for buildId " + buildId
                                                    + ". The assessment did not produce reports. Status response: " + statusText);
                                            run.setResult(Result.FAILURE);
                                            assessmentFailed = true;
                                            break;
                                        }

                                        if(state == AnalysisState.DONE) {
                                            assessmentId = statusObject.get("id").getAsString();
                                            // need to pause before continuing to make sure reports are available
                                            log(console, "Waiting for the report to become available...");
                                            pause(pollIntervalMillis);
                                            break;
                                        }
                                    }
                                }
                                else if (shouldRetryForStatus(statusResponse.code())) {
                                    log(console, "Assessment status is not available yet (HTTP " + statusResponse.code() + "); retrying.");
                                    ResponseBody statusBody = statusResponse.errorBody();
                                    if(statusBody != null) {
                                        statusBody.close();
                                    }
                                }
                                else {
                                    log(console, "Unable to get assessment status for buildId " + buildId + ". Please check credentials and try again.");
                                    ResponseBody statusBody = statusResponse.errorBody();
                                    if(statusBody != null) {
                                        log(console, "HTTP " + statusResponse.code() + ": " + statusBody.string());
                                    }
                                    run.setResult(Result.UNSTABLE);
                                    statusError = true;
                                    break;
                                }

                                long remaining = statusDeadline - System.currentTimeMillis();
                                if(remaining > 0) {
                                    pause(Math.min(pollIntervalMillis, remaining));
                                }
                            }

                            if(!assessmentFailed && !statusError && assessmentId.isEmpty()) {
                                log(console, "Timeout (" + (statusTimeoutMillis / (60 * 1000)) + " min) waiting for the assessment of buildId "
                                        + buildId + " to complete. Last status response: " + lastStatusText);
                                run.setResult(Result.UNSTABLE);
                            }

                            if (!assessmentFailed && !assessmentId.isEmpty()) {
                                // report may have taken a long time; refresh the access token
                                refreshTokenCall = service.refreshAccess(new RefreshCredentials(refreshToken));
                                tokenResponse = refreshTokenCall.execute();
                                tokenResponseBody = tokenResponse.body();

                                if (!tokenResponse.isSuccessful() || tokenResponseBody == null) {
                                    log(console, "Unable to refresh access token. Will continue using the original.");
                                }
                                else {

                                    String newAccessToken = tokenResponseBody.getAccessToken();
                                    refreshToken = tokenResponseBody.getRefreshToken();
                                    authToken = "Bearer " + newAccessToken;
                                }
                            }

                            if(!assessmentFailed && !assessmentId.isEmpty()) {
                                // get the actual report
                                log(console, "Retrieving report for assessment " + assessmentId);
                                String reportFormatString = reportFormat.getDescription().toLowerCase();

                                Call<ResponseBody> reportCall = null;
                                Response<ResponseBody> reportResponse = null;
                                long downloadDeadline = System.currentTimeMillis() + reportDownloadTimeoutMillis;
                                do {
                                    reportCall = service.downloadReport(assessmentId, reportFormatString, authToken);
                                    reportResponse = reportCall.execute();
                                    if (reportResponse.isSuccessful()
                                            || !shouldRetryForReportDownload(reportResponse.code())
                                            || System.currentTimeMillis() >= downloadDeadline) {
                                        break;
                                    }

                                    log(console, "Report is not ready (HTTP " + reportResponse.code()
                                            + "). Retrying for up to " + (reportDownloadTimeoutMillis / (60 * 1000))
                                            + " minutes.");
                                    ResponseBody errorBody = reportResponse.errorBody();
                                    if (errorBody != null) {
                                        errorBody.close();
                                    }

                                    long remaining = downloadDeadline - System.currentTimeMillis();
                                    if (remaining > 0) {
                                        pause(Math.min(pollIntervalMillis, remaining));
                                    }
                                } while (System.currentTimeMillis() < downloadDeadline);

                                ResponseBody reportResponseBody = reportResponse.body();

                                if(reportResponse.isSuccessful() && reportResponseBody != null) {
                                    boolean reportSuccess = false;
                                    long bytesWritten = 0;
                                    File reportFile = File.createTempFile("zScan-report-", "." + reportFormatString);
                                    try( InputStream inputStream = reportResponseBody.byteStream(); OutputStream outputStream = new FileOutputStream(reportFile); ) {
                                        byte[] buffer = new byte[4096];

                                        while(true) {
                                            int read = inputStream.read(buffer);
                                            if( read == -1 ) {
                                                break;
                                            }

                                            outputStream.write(buffer, 0, read);
                                            bytesWritten += read;
                                        }

                                        outputStream.flush();
                                        reportSuccess = true;
                                    }
                                    catch(Exception e) {
                                        log(console, "Unable to write to a temporary file " + reportFile.getAbsolutePath() + ": " + e.getLocalizedMessage());
                                        run.setResult(Result.UNSTABLE);
                                    }

                                    if(reportSuccess && bytesWritten > 0) {
                                        // append assessment id to the filename
                                        String effectiveReportFileName = (reportFileName.isEmpty()) ? 
                                                "zScan-report-" + assessmentId + "." + reportFormatString :
                                                FilenameUtils.removeExtension(reportFileName) + "-" + assessmentId + "." + FilenameUtils.getExtension(reportFileName);

                                        try {
                                            // copy the report to the workspace
                                            FilePath reportPath = new FilePath(workspace, effectiveReportFileName);
                                            reportPath.copyFrom(new FilePath(reportFile));
                                            log(console, "Written " + bytesWritten + " bytes to file " + reportPath.getRemote());
                                        }
                                        catch(Exception e) {
                                            log(console, "Unable to copy report to workspace: " + e.getLocalizedMessage());
                                            run.setResult(Result.UNSTABLE);
                                        }
                                    }
                                    
                                    // delete the report
                                    if(!reportFile.delete()) {
                                        log(console, "Unable to delete temporary file " + reportFile.getAbsolutePath());
                                    }
                                }
                                else {
                                    log(console, "Report failed to download: HTTP" + reportResponse.code() + ": " + reportCall.request().url());
                                    ResponseBody errorBody = reportResponse.errorBody();
                                    if (errorBody != null) {
                                        log(console, "Report download error: " + errorBody.string());
                                    }
                                    run.setResult(Result.UNSTABLE);
                                }
                            }
                        }
                    }
                    catch(RuntimeException e) {
                        log(console, "Unexpected runtime exception: " + e.getLocalizedMessage());
                        throw e;
                    }
                    catch(Exception e) {
                        log(console, "Unexpected exception: " + e.getLocalizedMessage());
                        run.setResult(Result.UNSTABLE);
                    }

                    totalCount++;
                } else {
                    log(console, "An error (HTTP " + uploadResponse.code() + ") occurred while trying to upload " + fileName + " to " + effectiveEndpoint);
                    ResponseBody uploadResponseBody = uploadResponse.errorBody();
                    if(uploadResponseBody != null) {
                        log(console, "Error message: " + uploadResponseBody.string());
                    }
                    run.setResult(Result.UNSTABLE);
                }

                // delete the local copy if needed
                if(path.isRemote()) {
                    if(!localPath.delete()) {
                        log(console, "Unable to delete temporary file " + localPath.getRemote());
                    }
                }

                // safety check
                if(totalCount >= MAX_FILES_UPLOAD) {
                    break;
                }
                if (assessmentFailed) {
                    break;
                }
            }
        }
        log(console, totalCount + " file(s) were uploaded.");
        if (run.getResult() == null || Result.SUCCESS.equals(run.getResult())) {
            run.setResult(Result.SUCCESS);
        }
    }

    static String effectiveTeamName(String teamName) {
        return (teamName == null || teamName.trim().isEmpty()) ? DEFAULT_TEAM_NAME : teamName.trim();
    }

    enum AnalysisState { PENDING, DONE, FAILED, MALFORMED }

    static AnalysisState interpretStatus(JsonObject statusObject) {
        String analysis = getAnalysis(statusObject);
        if (analysis == null || analysis.isEmpty()) {
            return AnalysisState.MALFORMED;
        }
        if ("Failed".equals(analysis)) {
            return AnalysisState.FAILED;
        }
        if ("Done".equals(analysis)) {
            return getStringOrEmpty(statusObject, "id").isEmpty() ? AnalysisState.MALFORMED : AnalysisState.DONE;
        }
        return AnalysisState.PENDING;
    }

    static boolean isFailedAnalysis(JsonObject statusObject) {
        return interpretStatus(statusObject) == AnalysisState.FAILED;
    }

    static String getAnalysis(JsonObject statusObject) {
        if (statusObject == null || !statusObject.has("zdevMetadata") || !statusObject.get("zdevMetadata").isJsonObject()) {
            return null;
        }
        JsonElement analysis = statusObject.getAsJsonObject("zdevMetadata").get("analysis");
        return (analysis != null && analysis.isJsonPrimitive()) ? analysis.getAsString() : null;
    }

    static String getStringOrEmpty(JsonObject object, String key) {
        if (object == null) {
            return "";
        }
        JsonElement value = object.get(key);
        return (value != null && value.isJsonPrimitive()) ? value.getAsString() : "";
    }

    static JsonObject parseJsonObject(String text) {
        try {
            JsonElement element = JsonParser.parseString(text);
            return element.isJsonObject() ? element.getAsJsonObject() : null;
        }
        catch (JsonParseException e) {
            return null;
        }
    }

    static boolean shouldRetryForStatus(int responseCode) {
        return responseCode == 404 || responseCode == 408 || responseCode == 429
                || responseCode == 500 || responseCode == 502 || responseCode == 503 || responseCode == 504;
    }

    private static void pause(long millis) throws InterruptedException {
        if (millis > 0) {
            Thread.sleep(millis);
        }
    }

    static boolean shouldRetryForReportDownload(int responseCode) {
        return responseCode == 404 || responseCode == 408 || responseCode == 425 || responseCode == 429
                || responseCode == 500 || responseCode == 502 || responseCode == 503 || responseCode == 504;
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    public static enum ReportFormat {

        JSON("JSON"),
        SARIF("SARIF");

        private final String description;
        
        ReportFormat(String description) {
            this.description = description;
        }
        public String getDescription() {
            return description;
        }
    }

    @Extension
    @Symbol("zScanUpload")
    public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> {
        
        private String globalEndpoint;
        private String globalClientId;
        private Secret globalClientSecret;
        private Boolean globalUseProxy;

        public DescriptorImpl() {
            load();
        }

        @SuppressWarnings("rawtypes")
        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        public String getDisplayName() {
            return "Upload build artifacts to zScan";
        }

        @Override
        public boolean configure(StaplerRequest2 req, JSONObject formData) throws FormException {
            JSONObject zscanData = formData.getJSONObject("zscan");
            req.bindJSON(this, zscanData); 
            save();
            return super.configure(req, formData);
        }

        public void setGlobalEndpoint(String globalEndpoint) {
            this.globalEndpoint = globalEndpoint;
        }
        public String getGlobalEndpoint() {
            return globalEndpoint;
        }

        public void setGlobalClientId(String globalClientId) {
            this.globalClientId = globalClientId;
        }
        public String getGlobalClientId() {
            return globalClientId;
        }

        public void setGlobalClientSecret(Secret globalClientSecret) {
            this.globalClientSecret = globalClientSecret;
        }
        public Secret getGlobalClientSecret() {
            return globalClientSecret;
        }

        public void setGlobalUseProxy(Boolean globalUseProxy) {
            this.globalUseProxy = globalUseProxy;
        }
        public Boolean getGlobalUseProxy() {
            return globalUseProxy;
        }

        public String getDefaultReportFileName() {
            return DEFAULT_REPORT_FILE;
        }

        public String getDefaultTeamName() {
            return DEFAULT_TEAM_NAME;
        }

        // Validate credentials by trying to obtain access token
        // This method can be executed by anyone with job configuration permission
        // Only the response code is checked
        @POST
        public FormValidation doValidateCredentials(
            @QueryParameter("endpoint") final String endpoint,
            @QueryParameter("clientId") final String clientId, 
            @QueryParameter("clientSecret") final Secret clientSecret,
            @QueryParameter("useProxy") final Boolean useProxy,
            @AncestorInPath Item item) {

            return checkConsoleInformation(item, endpoint, clientId, globalClientSecret, useProxy);
        }

        // Validate global credentials by trying to obtain access token
        // This method can be executed by anyone with job configuration permission
        // Only the response code is checked
        @POST
        public FormValidation doValidateGlobalCredentials(
            @QueryParameter("globalEndpoint") final String globalEndpoint,
            @QueryParameter("globalClientId") final String globalClientId, 
            @QueryParameter("globalClientSecret") final Secret globalClientSecret,
            @QueryParameter("globalUseProxy") final Boolean globalUseProxy,
            @AncestorInPath Item item) {

            return checkConsoleInformation(item, globalEndpoint, globalClientId, globalClientSecret, globalUseProxy);
        }

        private FormValidation checkConsoleInformation(Item item, String endpoint, String clientId, Secret clientSecret, Boolean useProxy) {
            if (StringUtils.isBlank(endpoint)) {
                return FormValidation.error("Endpoint is required");
            }
            if (StringUtils.isBlank(clientId)) {
                return FormValidation.error("Client ID is required");
            }
            if (clientSecret == null || StringUtils.isBlank(Secret.toString(clientSecret))) {
                return FormValidation.error("Client Secret is required");
            }

            try {
                if(item == null){
                    Jenkins.get().checkPermission(Jenkins.ADMINISTER);
                }else {
                    item.checkPermission(Item.CONFIGURE);
                }

                OkHttpClient okHttpClient = new ZDevHTTPClient().getHttpClient(useProxy, connectionTimeout, writeTimeout, readTimeout);

                Retrofit retrofit = new Retrofit.Builder()
                        .baseUrl(endpoint)
                        .client(okHttpClient)
                        .addConverterFactory(GsonConverterFactory.create())
                        .build();

                UploadPluginService service = retrofit.create(UploadPluginService.class);

                Call<LoginResponse> loginResponseCall = service.login(new LoginCredentials(clientId, Secret.toString(clientSecret)));
                Response<LoginResponse> response = loginResponseCall.execute();

                if (!response.isSuccessful() || response.body() == null) {
                    return FormValidation.error("Unable to login with provided Client ID and Client Secret to " + endpoint);
                }
            }
            catch(hudson.security.AccessDeniedException3 e) {
                return FormValidation.error("User not authorized to check credentials.");
            }
            catch(java.net.UnknownHostException e) {
                return FormValidation.error("Unknown host: " + endpoint);
            }
            catch(java.io.IOException e) {
                return FormValidation.error("Unable to connect to the provided endpoint: " + endpoint + "(" + e.getLocalizedMessage() + ")");
            }
            catch(Exception e) {
                return FormValidation.error("Error validating credentials: " + e.getLocalizedMessage());
            }

            // If we get here, the credentials are valid        
            return FormValidation.ok("Credentials are valid");
        }
    }

    @Override
    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.STEP;
    }
}
