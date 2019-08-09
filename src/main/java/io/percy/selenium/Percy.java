package io.percy.selenium;

import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.HttpClientBuilder;

import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.Nullable;

import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebDriverException;

/**
 * Percy client for visual testing.
 */
public class Percy {

    private static final Logger LOGGER = Logger.getLogger(Percy.class.getName());

    // We'll expect this file to exist at the root of our classpath, as a resource.
    private static final String AGENTJS_FILE = "percy-agent.js";

    // Selenium WebDriver we'll use for accessing the web pages to snapshot.
    private WebDriver driver;

    // The JavaScript contained in percy-agent.js
    private String percyAgentJs;

    // Environment information like Java, browser, & SDK versions
    private Environment env;

    // Is the Percy Agent process running or not
    private boolean percyIsRunning = true;

    /**
     * @param driver The Selenium WebDriver object that will hold the browser
     *               session to snapshot.
     */
    public Percy(WebDriver driver) {
        this.driver = driver;
        this.env = new Environment(driver);
        this.percyAgentJs = loadPercyAgentJs();
    }

    /**
     * Attempts to load percy-agent.js from the resources in this Jar. The file
     * comes from the node module @percy/agent, which is installed and packaged into
     * this Jar as part of the Maven build.
     *
     * Bundling the percy-agent.js file with this library does run the minor risk of
     * a future incompatibility between the bundled percy-agent.js in this library,
     * and the version of @percy/agent being run by the library's client.
     *
     * An alternative to consider would be to try to load percy-agent.js at runtime
     * from a running percy agent server on the standard port.
     */
    @Nullable
    private String loadPercyAgentJs() {
        try {
            InputStream stream = getClass().getClassLoader().getResourceAsStream(AGENTJS_FILE);
            byte[] agentBytes = new byte[stream.available()];
            stream.read(agentBytes);
            return new String(agentBytes);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Something went wrong trying to load {}. Snapshotting will not work.",
                    AGENTJS_FILE);
            return null;
        }
    }

    /**
     * Take a snapshot and upload it to Percy.
     *
     * @param name The human-readable name of the snapshot. Should be unique.
     *
     */
    public void snapshot(String name) {
        snapshot(name, null, null, false);
    }

    /**
     * Take a snapshot and upload it to Percy.
     *
     * @param name   The human-readable name of the snapshot. Should be unique.
     * @param widths The browser widths at which you want to take the snapshot. In
     *               pixels.
     */
    public void snapshot(String name, List<Integer> widths) {
        snapshot(name, widths, null, false);
    }

    /**
     * Take a snapshot and upload it to Percy.
     *
     * @param name   The human-readable name of the snapshot. Should be unique.
     * @param widths The browser widths at which you want to take the snapshot. In
     *               pixels.
     * @param minHeight The minimum height of the resulting snapshot. In pixels.
     */
    public void snapshot(String name, List<Integer> widths, Integer minHeight) {
        snapshot(name, widths, minHeight, false);
    }

    /**
     * Take a snapshot and upload it to Percy.
     *
     * @param name      The human-readable name of the snapshot. Should be unique.
     * @param widths    The browser widths at which you want to take the snapshot.
     *                  In pixels.
     * @param minHeight The minimum height of the resulting snapshot. In pixels.
     * @param enableJavaScript Enable JavaScript in the Percy rendering environment
     */
    public void snapshot(String name, @Nullable List<Integer> widths, Integer minHeight, boolean enableJavaScript) {
        String domSnapshot = "";

        if (percyAgentJs == null) {
            // This would happen if we couldn't load percy-agent.js in the constructor.
            LOGGER.log(Level.WARNING, "percy-agent.js is not available. Snapshotting is disabled.");
            return;
        }

        try {
            JavascriptExecutor jse = (JavascriptExecutor) driver;
            jse.executeScript(percyAgentJs);
            domSnapshot = (String) jse.executeScript(buildSnapshotJS());
        } catch (WebDriverException e) {
            // For some reason, the execution in the browser failed.
            System.out.println("[percy] Something went wrong attempting to take a snapshot: " + e.getMessage());
        }

        postSnapshot(domSnapshot, name, widths, minHeight, driver.getCurrentUrl(), enableJavaScript);
    }

    /**
     * POST the DOM taken from the test browser to the Percy Agent node process.
     *
     * @param domSnapshot Stringified & serialized version of the site/applications DOM
     * @param name        The human-readable name of the snapshot. Should be unique.
     * @param widths      The browser widths at which you want to take the snapshot.
     *                    In pixels.
     * @param minHeight   The minimum height of the resulting snapshot. In pixels.
     * @param enableJavaScript Enable JavaScript in the Percy rendering environment
     */
    private void postSnapshot(String domSnapshot, String name, @Nullable List<Integer> widths, Integer minHeight, String url, boolean enableJavaScript) {
        if (percyIsRunning == false) {
            return;
        }

        // Build a JSON object to POST back to the agent node process
        JSONObject json = new JSONObject();
        json.put("url", url);
        json.put("name", name);
        json.put("minHeight", minHeight);
        json.put("domSnapshot", domSnapshot);
        json.put("clientInfo", env.getClientInfo());
        json.put("enableJavaScript", enableJavaScript);
        json.put("environmentInfo", env.getEnvironmentInfo());
        // Sending an empty array of widths to agent breaks asset discovery
        if (widths != null && widths.size() != 0) {
            json.put("widths", widths);
        }

        StringEntity entity = new StringEntity(json.toString(), ContentType.APPLICATION_JSON);
        HttpClient httpClient = HttpClientBuilder.create().build();

        try {
            HttpPost request = new HttpPost("http://localhost:5338/percy/snapshot");
            request.setEntity(entity);
            // We don't really care about the response -- as long as their test suite doesn't fail
            HttpResponse response = httpClient.execute(request);
        } catch (Exception ex) {
            System.out.println("[percy] An error occured when sending the DOM to agent: " + ex);
            percyIsRunning = false;
            System.out.println("[percy] Percy has been disabled");
        }

    }

    private String getAgentOptions() {
        StringBuilder info = new StringBuilder();
        info.append("{ ");
        info.append(String.format("handleAgentCommunication: false"));
        info.append(" }");
        return info.toString();
    }

    /**
     * @return A String containing the JavaScript needed to instantiate a PercyAgent
     *         and take a snapshot.
     */
    private String buildSnapshotJS() {
        StringBuilder jsBuilder = new StringBuilder();
        jsBuilder.append(String.format("var percyAgentClient = new PercyAgent(%s)\n", getAgentOptions()));
        jsBuilder.append(String.format("return percyAgentClient.snapshot('not used')"));

        return jsBuilder.toString();
    }
}
