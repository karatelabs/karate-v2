/*
 * The MIT License
 *
 * Copyright 2025 Karate Labs Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package io.karatelabs.core;

import io.karatelabs.js.SimpleObject;
import io.karatelabs.log.JvmLogger;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Holds runtime configuration settings set via the 'configure' keyword.
 * <p>
 * This is distinct from:
 * <ul>
 *   <li>{@link KaratePom} - Project file (karate-pom.json) for CLI options</li>
 *   <li>karate-config.js - Runtime bootstrap that runs per-scenario</li>
 * </ul>
 * <p>
 * Example usage:
 * <pre>
 * * configure ssl = true
 * * configure proxy = 'http://proxy:8080'
 * * configure readTimeout = 60000
 * * def cfg = karate.config
 * * match cfg.sslEnabled == true
 * </pre>
 * <p>
 * Implements {@link SimpleObject} so it can be accessed as a JavaScript object
 * via {@code karate.config}.
 */
public class KarateConfig implements SimpleObject {

    // Keys exposed via SimpleObject
    private static final List<String> KEYS = List.of(
            // HTTP client settings
            "url", "readTimeout", "connectTimeout", "followRedirects", "localAddress", "charset",
            // SSL
            "sslEnabled", "sslAlgorithm", "sslKeyStore", "sslKeyStorePassword", "sslKeyStoreType",
            "sslTrustStore", "sslTrustStorePassword", "sslTrustStoreType", "sslTrustAll",
            // Proxy
            "proxyUri", "proxyUsername", "proxyPassword", "nonProxyHosts",
            // Headers/Cookies
            "headers", "cookies",
            // Logging
            "lowerCaseResponseHeaders", "logPrettyRequest", "logPrettyResponse", "printEnabled",
            // Retry
            "retryInterval", "retryCount", "httpRetryEnabled",
            // Report
            "showLog", "showAllSteps",
            // callSingleCache
            "callSingleCacheMinutes", "callSingleCacheDir",
            // Execution control
            "continueOnStepFailure", "abortedStepsShouldPass", "abortSuiteOnFailure",
            // NTLM Auth
            "ntlmUsername", "ntlmPassword", "ntlmDomain", "ntlmWorkstation"
    );

    // ===== HTTP Client (requires rebuild when changed) =====
    private String url;
    private int readTimeout = 30000;
    private int connectTimeout = 30000;
    private boolean followRedirects = true;
    private String localAddress;
    private Charset charset = StandardCharsets.UTF_8;

    // SSL (can be boolean true, String algorithm, or Map with details)
    private boolean sslEnabled;
    private String sslAlgorithm = "TLS";
    private String sslKeyStore;
    private String sslKeyStorePassword;
    private String sslKeyStoreType;
    private String sslTrustStore;
    private String sslTrustStorePassword;
    private String sslTrustStoreType;
    private boolean sslTrustAll = true;

    // Proxy (can be String URI or Map with details)
    private String proxyUri;
    private String proxyUsername;
    private String proxyPassword;
    private List<String> nonProxyHosts;

    // ===== Non-HTTP (no client rebuild needed) =====
    private Object headers;  // Map<String,Object> or JS function
    private Object cookies;  // Map<String,Object>

    // Logging
    private boolean lowerCaseResponseHeaders;
    private boolean logPrettyRequest;
    private boolean logPrettyResponse;
    private boolean printEnabled = true;

    // Retry (Map with interval, count)
    private int retryInterval = 3000;
    private int retryCount = 3;
    private boolean httpRetryEnabled;

    // Report (Map with showLog, showAllSteps)
    private boolean showLog = true;
    private boolean showAllSteps = true;

    // callSingleCache (Map with minutes, dir)
    private int callSingleCacheMinutes;
    private String callSingleCacheDir;

    // Execution control
    private boolean continueOnStepFailure;
    private boolean abortedStepsShouldPass;
    private boolean abortSuiteOnFailure;

    // NTLM Auth (Map with username, password, domain, workstation)
    private String ntlmUsername;
    private String ntlmPassword;
    private String ntlmDomain;
    private String ntlmWorkstation;

    /**
     * Create a deep copy of this configuration.
     * Used to snapshot config state for callonce/callSingle isolation.
     *
     * @return a new KarateConfig with copied values
     */
    public KarateConfig copy() {
        KarateConfig copy = new KarateConfig();
        // HTTP client settings
        copy.url = this.url;
        copy.readTimeout = this.readTimeout;
        copy.connectTimeout = this.connectTimeout;
        copy.followRedirects = this.followRedirects;
        copy.localAddress = this.localAddress;
        copy.charset = this.charset;
        // SSL
        copy.sslEnabled = this.sslEnabled;
        copy.sslAlgorithm = this.sslAlgorithm;
        copy.sslKeyStore = this.sslKeyStore;
        copy.sslKeyStorePassword = this.sslKeyStorePassword;
        copy.sslKeyStoreType = this.sslKeyStoreType;
        copy.sslTrustStore = this.sslTrustStore;
        copy.sslTrustStorePassword = this.sslTrustStorePassword;
        copy.sslTrustStoreType = this.sslTrustStoreType;
        copy.sslTrustAll = this.sslTrustAll;
        // Proxy
        copy.proxyUri = this.proxyUri;
        copy.proxyUsername = this.proxyUsername;
        copy.proxyPassword = this.proxyPassword;
        copy.nonProxyHosts = this.nonProxyHosts != null ? new ArrayList<>(this.nonProxyHosts) : null;
        // Headers/Cookies (shallow copy - could be function refs)
        copy.headers = this.headers;
        copy.cookies = this.cookies;
        // Logging
        copy.lowerCaseResponseHeaders = this.lowerCaseResponseHeaders;
        copy.logPrettyRequest = this.logPrettyRequest;
        copy.logPrettyResponse = this.logPrettyResponse;
        copy.printEnabled = this.printEnabled;
        // Retry
        copy.retryInterval = this.retryInterval;
        copy.retryCount = this.retryCount;
        copy.httpRetryEnabled = this.httpRetryEnabled;
        // Report
        copy.showLog = this.showLog;
        copy.showAllSteps = this.showAllSteps;
        // callSingleCache
        copy.callSingleCacheMinutes = this.callSingleCacheMinutes;
        copy.callSingleCacheDir = this.callSingleCacheDir;
        // Execution control
        copy.continueOnStepFailure = this.continueOnStepFailure;
        copy.abortedStepsShouldPass = this.abortedStepsShouldPass;
        copy.abortSuiteOnFailure = this.abortSuiteOnFailure;
        // NTLM Auth
        copy.ntlmUsername = this.ntlmUsername;
        copy.ntlmPassword = this.ntlmPassword;
        copy.ntlmDomain = this.ntlmDomain;
        copy.ntlmWorkstation = this.ntlmWorkstation;
        return copy;
    }

    /**
     * Apply configuration from a key-value pair.
     *
     * @param key   the configure key (e.g., "ssl", "proxy", "readTimeout")
     * @param value the value to set
     * @return true if HTTP client needs to be rebuilt, false otherwise
     * @throws RuntimeException if key is not recognized
     */
    public boolean configure(String key, Object value) {
        key = key != null ? key.trim() : "";
        return switch (key) {
            // HTTP client settings (require rebuild)
            case "ssl" -> {
                configureSsl(value);
                yield true;
            }
            case "proxy" -> {
                configureProxy(value);
                yield true;
            }
            case "readTimeout" -> {
                this.readTimeout = toInt(value);
                yield true;
            }
            case "connectTimeout" -> {
                this.connectTimeout = toInt(value);
                yield true;
            }
            case "followRedirects" -> {
                this.followRedirects = toBoolean(value);
                yield true;
            }
            case "localAddress" -> {
                this.localAddress = toString(value);
                yield true;
            }
            case "charset" -> {
                this.charset = Charset.forName(toString(value));
                yield true;
            }
            case "ntlmAuth" -> {
                configureNtlm(value);
                yield true;
            }

            // Non-HTTP settings (no rebuild)
            case "url" -> {
                this.url = toString(value);
                yield false;
            }
            case "headers" -> {
                this.headers = value;
                yield false;
            }
            case "cookies" -> {
                this.cookies = value;
                yield false;
            }
            case "lowerCaseResponseHeaders" -> {
                this.lowerCaseResponseHeaders = toBoolean(value);
                yield false;
            }
            case "logPrettyRequest" -> {
                this.logPrettyRequest = toBoolean(value);
                yield false;
            }
            case "logPrettyResponse" -> {
                this.logPrettyResponse = toBoolean(value);
                yield false;
            }
            case "printEnabled" -> {
                this.printEnabled = toBoolean(value);
                yield false;
            }
            case "retry" -> {
                configureRetry(value);
                yield false;
            }
            case "httpRetryEnabled" -> {
                this.httpRetryEnabled = toBoolean(value);
                yield true; // Requires HTTP client rebuild
            }
            case "report" -> {
                configureReport(value);
                yield false;
            }
            case "callSingleCache" -> {
                configureCallSingleCache(value);
                yield false;
            }
            case "continueOnStepFailure" -> {
                this.continueOnStepFailure = toBoolean(value);
                yield false;
            }
            case "abortedStepsShouldPass" -> {
                this.abortedStepsShouldPass = toBoolean(value);
                yield false;
            }
            case "abortSuiteOnFailure" -> {
                this.abortSuiteOnFailure = toBoolean(value);
                yield false;
            }

            default -> throw new RuntimeException("unexpected 'configure' key: '" + key + "'");
        };
    }

    private void configureSsl(Object value) {
        if (value == null) {
            this.sslEnabled = false;
            return;
        }
        if (value instanceof Boolean b) {
            this.sslEnabled = b;
            return;
        }
        if (value instanceof String s) {
            this.sslEnabled = true;
            this.sslAlgorithm = s;
            return;
        }
        if (value instanceof Map<?, ?> map) {
            this.sslEnabled = true;
            if (map.containsKey("algorithm")) {
                this.sslAlgorithm = toString(map.get("algorithm"));
            }
            if (map.containsKey("keyStore")) {
                this.sslKeyStore = toString(map.get("keyStore"));
            }
            if (map.containsKey("keyStorePassword")) {
                this.sslKeyStorePassword = toString(map.get("keyStorePassword"));
            }
            if (map.containsKey("keyStoreType")) {
                this.sslKeyStoreType = toString(map.get("keyStoreType"));
            }
            if (map.containsKey("trustStore")) {
                this.sslTrustStore = toString(map.get("trustStore"));
            }
            if (map.containsKey("trustStorePassword")) {
                this.sslTrustStorePassword = toString(map.get("trustStorePassword"));
            }
            if (map.containsKey("trustStoreType")) {
                this.sslTrustStoreType = toString(map.get("trustStoreType"));
            }
            if (map.containsKey("trustAll")) {
                this.sslTrustAll = toBoolean(map.get("trustAll"));
            }
        }
    }

    private void configureProxy(Object value) {
        if (value == null) {
            this.proxyUri = null;
            return;
        }
        if (value instanceof String s) {
            this.proxyUri = s;
            return;
        }
        if (value instanceof Map<?, ?> map) {
            if (map.containsKey("uri")) {
                this.proxyUri = toString(map.get("uri"));
            }
            if (map.containsKey("username")) {
                this.proxyUsername = toString(map.get("username"));
            }
            if (map.containsKey("password")) {
                this.proxyPassword = toString(map.get("password"));
            }
            if (map.containsKey("nonProxyHosts")) {
                Object nph = map.get("nonProxyHosts");
                if (nph instanceof List<?> list) {
                    this.nonProxyHosts = new ArrayList<>();
                    for (Object item : list) {
                        this.nonProxyHosts.add(toString(item));
                    }
                }
            }
        }
    }

    private void configureNtlm(Object value) {
        if (value == null) {
            this.ntlmUsername = null;
            this.ntlmPassword = null;
            this.ntlmDomain = null;
            this.ntlmWorkstation = null;
            return;
        }
        if (value instanceof Map<?, ?> map) {
            if (map.containsKey("username")) {
                this.ntlmUsername = toString(map.get("username"));
            }
            if (map.containsKey("password")) {
                this.ntlmPassword = toString(map.get("password"));
            }
            if (map.containsKey("domain")) {
                this.ntlmDomain = toString(map.get("domain"));
            }
            if (map.containsKey("workstation")) {
                this.ntlmWorkstation = toString(map.get("workstation"));
            }
        }
    }

    private void configureRetry(Object value) {
        if (value instanceof Map<?, ?> map) {
            if (map.containsKey("interval")) {
                this.retryInterval = toInt(map.get("interval"));
            }
            if (map.containsKey("count")) {
                this.retryCount = toInt(map.get("count"));
            }
        }
    }

    private void configureReport(Object value) {
        if (value instanceof Boolean b) {
            this.showLog = b;
            this.showAllSteps = b;
            return;
        }
        if (value instanceof Map<?, ?> map) {
            if (map.containsKey("showLog")) {
                this.showLog = toBoolean(map.get("showLog"));
            }
            if (map.containsKey("showAllSteps")) {
                this.showAllSteps = toBoolean(map.get("showAllSteps"));
            }
        }
    }

    private void configureCallSingleCache(Object value) {
        if (value instanceof Map<?, ?> map) {
            if (map.containsKey("minutes")) {
                this.callSingleCacheMinutes = toInt(map.get("minutes"));
            }
            if (map.containsKey("dir")) {
                this.callSingleCacheDir = toString(map.get("dir"));
            }
        }
    }

    // ===== Type conversion helpers =====

    private static String toString(Object value) {
        return value == null ? null : value.toString();
    }

    private static boolean toBoolean(Object value) {
        if (value == null) return false;
        if (value instanceof Boolean b) return b;
        return Boolean.parseBoolean(value.toString());
    }

    private static int toInt(Object value) {
        if (value == null) return 0;
        if (value instanceof Number n) return n.intValue();
        return Integer.parseInt(value.toString());
    }

    // ===== SimpleObject implementation =====

    @Override
    public Collection<String> keys() {
        return KEYS;
    }

    @Override
    public Object jsGet(String name) {
        return switch (name) {
            case "url" -> url;
            case "readTimeout" -> readTimeout;
            case "connectTimeout" -> connectTimeout;
            case "followRedirects" -> followRedirects;
            case "localAddress" -> localAddress;
            case "charset" -> charset != null ? charset.name() : null;
            case "sslEnabled" -> sslEnabled;
            case "sslAlgorithm" -> sslAlgorithm;
            case "sslKeyStore" -> sslKeyStore;
            case "sslKeyStorePassword" -> sslKeyStorePassword;
            case "sslKeyStoreType" -> sslKeyStoreType;
            case "sslTrustStore" -> sslTrustStore;
            case "sslTrustStorePassword" -> sslTrustStorePassword;
            case "sslTrustStoreType" -> sslTrustStoreType;
            case "sslTrustAll" -> sslTrustAll;
            case "proxyUri" -> proxyUri;
            case "proxyUsername" -> proxyUsername;
            case "proxyPassword" -> proxyPassword;
            case "nonProxyHosts" -> nonProxyHosts;
            case "headers" -> headers;
            case "cookies" -> cookies;
            case "lowerCaseResponseHeaders" -> lowerCaseResponseHeaders;
            case "logPrettyRequest" -> logPrettyRequest;
            case "logPrettyResponse" -> logPrettyResponse;
            case "printEnabled" -> printEnabled;
            case "retryInterval" -> retryInterval;
            case "retryCount" -> retryCount;
            case "httpRetryEnabled" -> httpRetryEnabled;
            case "showLog" -> showLog;
            case "showAllSteps" -> showAllSteps;
            case "callSingleCacheMinutes" -> callSingleCacheMinutes;
            case "callSingleCacheDir" -> callSingleCacheDir;
            case "continueOnStepFailure" -> continueOnStepFailure;
            case "abortedStepsShouldPass" -> abortedStepsShouldPass;
            case "abortSuiteOnFailure" -> abortSuiteOnFailure;
            case "ntlmUsername" -> ntlmUsername;
            case "ntlmPassword" -> ntlmPassword;
            case "ntlmDomain" -> ntlmDomain;
            case "ntlmWorkstation" -> ntlmWorkstation;
            default -> null;
        };
    }

    // ===== Getters =====

    public String getUrl() {
        return url;
    }

    public int getReadTimeout() {
        return readTimeout;
    }

    public int getConnectTimeout() {
        return connectTimeout;
    }

    public boolean isFollowRedirects() {
        return followRedirects;
    }

    public String getLocalAddress() {
        return localAddress;
    }

    public Charset getCharset() {
        return charset;
    }

    public boolean isSslEnabled() {
        return sslEnabled;
    }

    public String getSslAlgorithm() {
        return sslAlgorithm;
    }

    public String getSslKeyStore() {
        return sslKeyStore;
    }

    public String getSslKeyStorePassword() {
        return sslKeyStorePassword;
    }

    public String getSslKeyStoreType() {
        return sslKeyStoreType;
    }

    public String getSslTrustStore() {
        return sslTrustStore;
    }

    public String getSslTrustStorePassword() {
        return sslTrustStorePassword;
    }

    public String getSslTrustStoreType() {
        return sslTrustStoreType;
    }

    public boolean isSslTrustAll() {
        return sslTrustAll;
    }

    public String getProxyUri() {
        return proxyUri;
    }

    public String getProxyUsername() {
        return proxyUsername;
    }

    public String getProxyPassword() {
        return proxyPassword;
    }

    public List<String> getNonProxyHosts() {
        return nonProxyHosts;
    }

    public Object getHeaders() {
        return headers;
    }

    public Object getCookies() {
        return cookies;
    }

    public boolean isLowerCaseResponseHeaders() {
        return lowerCaseResponseHeaders;
    }

    public boolean isLogPrettyRequest() {
        return logPrettyRequest;
    }

    public boolean isLogPrettyResponse() {
        return logPrettyResponse;
    }

    public boolean isPrintEnabled() {
        return printEnabled;
    }

    public int getRetryInterval() {
        return retryInterval;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public boolean isHttpRetryEnabled() {
        return httpRetryEnabled;
    }

    public boolean isShowLog() {
        return showLog;
    }

    public boolean isShowAllSteps() {
        return showAllSteps;
    }

    public int getCallSingleCacheMinutes() {
        return callSingleCacheMinutes;
    }

    public String getCallSingleCacheDir() {
        return callSingleCacheDir;
    }

    public boolean isContinueOnStepFailure() {
        return continueOnStepFailure;
    }

    public boolean isAbortedStepsShouldPass() {
        return abortedStepsShouldPass;
    }

    public boolean isAbortSuiteOnFailure() {
        return abortSuiteOnFailure;
    }

    public String getNtlmUsername() {
        return ntlmUsername;
    }

    public String getNtlmPassword() {
        return ntlmPassword;
    }

    public String getNtlmDomain() {
        return ntlmDomain;
    }

    public String getNtlmWorkstation() {
        return ntlmWorkstation;
    }

}
