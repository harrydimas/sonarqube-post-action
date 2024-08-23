package com.github.harrydimas.spa;

import com.google.gson.Gson;

import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Logger;

public class Main {

    private static final Logger logger = Logger.getLogger(Main.class.getName());

    private static final Gson gson = new Gson();
    private static final String SUCCESS = "SUCCESS";
    private static final String FAILED = "FAILED";
    private static String slackWebhookUrl = null;
    private static String slackMention = null;
    private static String slackChannel = null;
    private static String token = null;
    private static String projectKey = null;
    private static String serverUrl = null;
    private static String startedAt = null;
    private static String executedAt = null;
    private static String pullRequestUrl = null;
    private static String pullRequestTitle = null;

    public static void main(String[] args) {

        logger.info("Starting Sonarqube Post Action");
        String filePath = System.getProperty("filePath");
        token = System.getProperty("sonarToken") + ":";
        slackWebhookUrl = System.getProperty("slackWebhook");
        slackMention = System.getProperty("slackMention");
        slackChannel = System.getProperty("slackChannel");
        pullRequestUrl = System.getProperty("pullRequestUrl");
        pullRequestTitle = System.getProperty("pullRequestTitle");

        try (FileInputStream fis = new FileInputStream(filePath)) {
            var properties = new Properties();
            properties.load(fis);

            projectKey = properties.getProperty("projectKey");
            serverUrl = properties.getProperty("serverUrl");
            var ceTaskUrl = properties.getProperty("ceTaskUrl");
            if (ceTaskUrl != null) {
                logger.info(() -> "ceTaskUrl: " + ceTaskUrl);
                if (isTaskSuccess(ceTaskUrl)) {
                    getAllIssues();
                }
            } else {
                logger.info("ceTaskUrl not found in the file.");
            }
        } catch (IOException | InterruptedException e) {
            logger.severe(e.getMessage());
            System.exit(1);
        }
    }

    private static boolean isTaskSuccess(String url) throws InterruptedException {
        logger.info(() -> "isTaskSuccess = " + url);
        var response = callApi(url);
        var task = (Map) response.get("task");
        var isSuccess = SUCCESS.equals(task.get("status"));
        if (isSuccess) {
            startedAt = (String) task.get("startedAt");
            executedAt = (String) task.get("executedAt");
        } else if (FAILED.equals(task.get("status"))) {
            return false;
        } else {
            Thread.sleep(15000);
            isSuccess = isTaskSuccess(url);
        }
        return isSuccess;
    }

    private static void getAllIssues() {
        var createdAfter = parseToLocalDateTime(startedAt).minusMinutes(10).format(DateTimeFormatter.ISO_DATE_TIME).concat("%2B0000");
        var createdBefore = parseToLocalDateTime(executedAt).format(DateTimeFormatter.ISO_DATE_TIME).concat("%2B0000");
        var url = serverUrl + "/api/issues/search?componentKeys=" + projectKey + "&createdAfter=" + createdAfter + "&createdBefore=" + createdBefore + "&issueStatuses=OPEN";
        var response = callApi(url);
        sendSlackMessage(response);
    }

    private static LocalDateTime parseToLocalDateTime(String dateTimeString) {
        logger.info(() -> "parseToLocalDateTime = " + dateTimeString);
        var formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ");
        var offsetDateTime = OffsetDateTime.parse(dateTimeString, formatter);
        return offsetDateTime.toLocalDateTime();
    }

    private static Map<String, Object> callApi(String url) {
        logger.info(() -> "callApi: " + url);
        try {
            var client = HttpClient.newHttpClient();
            var request = HttpRequest.newBuilder()
                    .uri(new URI(url))
                    .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(token.getBytes()))
                    .GET()
                    .build();

            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                logger.info(() -> "Response: " + response.body());
                return gson.fromJson(response.body(), Map.class);
            } else {
                logger.severe(() -> "Error: " + response.statusCode());
            }
        } catch (Exception e) {
            logger.severe(e.getMessage());
        }

        return Map.of();
    }

    private static void sendSlackMessage(Map<String, Object> response) {
        var issues = (List<Map>) response.get("issues");
        StringBuilder text = new StringBuilder(slackMention);
        text.append(" ").append(issues.size()).append(" open issues found after scanning ")
                .append("<").append(pullRequestUrl).append("|PR> with title `").append(pullRequestTitle).append("`");
        if (!issues.isEmpty()) {
            text.append("\n").append(">*New Issues*\n");
            for (var issue : issues) {
                var sqUrl = serverUrl + "/project/issues?open=" + issue.get("key") + "&id=" + projectKey;
                text.append("> - ").append(issue.get("message")).append(" <").append(sqUrl).append("|open> \n");
            }
        }

        var json = new HashMap<>();
        json.put("text", text.toString());
        json.put("channel", slackChannel);
        json.put("username", "Sonarqube DEV");
        json.put("icon_url", "https://artifacthub.io/image/949a653d-9573-4e6f-8a20-443126e55656@3x");

        sendMessage(gson.toJson(json, Map.class));
    }

    private static void sendMessage(String message) {
        try {
            var client = HttpClient.newHttpClient();
            var bodyPublisher = HttpRequest.BodyPublishers.ofString(message);
            var request = HttpRequest.newBuilder()
                    .uri(new URI(slackWebhookUrl))
                    .POST(bodyPublisher)
                    .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            logger.info(() -> "Response: " + response.body());
        } catch (Exception e) {
            logger.severe(e.getMessage());
        }

    }

}
