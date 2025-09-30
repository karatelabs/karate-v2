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
package io.karatelabs.io.http;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.cookie.BasicCookieStore;
import org.apache.hc.client5.http.cookie.StandardCookieSpec;
import org.apache.hc.client5.http.entity.EntityBuilder;
import org.apache.hc.client5.http.impl.DefaultRedirectStrategy;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactory;
import org.apache.hc.client5.http.ssl.TrustAllStrategy;
import org.apache.hc.client5.http.ssl.TrustSelfSignedStrategy;
import org.apache.hc.core5.http.*;
import org.apache.hc.core5.http.io.SocketConfig;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.support.ClassicRequestBuilder;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.ssl.SSLContextBuilder;
import org.apache.hc.core5.ssl.SSLContexts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class ApacheHttpClient implements HttpClient, HttpRequestInterceptor {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApacheHttpClient.class);

    private HttpRequest request;
    private CloseableHttpClient httpClient;

    private int readTimeout = 30000;
    private int connectTimeout = 30000;

    private boolean followRedirects = true;

    private String proxyUri;
    private String proxyUsername;
    private String proxyPassword;
    private List<String> nonProxyHosts;

    private boolean ssl = false;
    private String sslAlgorithm = "TLS";
    private String sslKeyStore;
    private String sslKeyStorePassword;
    private String sslKeyStoreType;
    private String sslTrustStore;
    private String sslTrustStorePassword;
    private String sslTrustStoreType;
    private boolean sslTrustAll = true;

    private final HttpLogger logger = new HttpLogger();

    @SuppressWarnings("unchecked")
    @Override
    public void config(String key, Object value) {
        switch (key) {
            case "ssl":
                if (value instanceof Boolean flag) {
                    ssl = flag;
                } else if (value instanceof Map) {
                    ssl = true;
                    Map<String, Object> map = (Map<String, Object>) value;
                    sslKeyStore = (String) map.get("keyStore");
                    sslKeyStorePassword = (String) map.get("keyStorePassword");
                    sslKeyStoreType = (String) map.get("keyStoreType");
                    sslTrustStore = (String) map.get("trustStore");
                    sslTrustStorePassword = (String) map.get("trustStorePassword");
                    sslTrustStoreType = (String) map.get("trustStoreType");
                    Boolean trustAll = (Boolean) map.get("trustAll");
                    if (trustAll != null) {
                        sslTrustAll = trustAll;
                    }
                    String algorithm = (String) map.get("algorithm");
                    if (algorithm != null) {
                        sslAlgorithm = algorithm;
                    }
                } else {
                    LOGGER.warn("boolean or object expected for: {}", key);
                }
                break;
            case "proxy":
                if (value == null) {
                    proxyUri = null;
                } else if (value instanceof String s) {
                    proxyUri = s;
                } else if (value instanceof Map) {
                    Map<String, Object> map = (Map<String, Object>) value;
                    proxyUri = (String) map.get("uri");
                    proxyUsername = (String) map.get("username");
                    proxyPassword = (String) map.get("password");
                    nonProxyHosts = (List<String>) map.get("nonProxyHosts");
                } else {
                    LOGGER.warn("string or object expected for: {}", key);
                }
                break;
            case "readTimeout":
                if (value instanceof Number time) {
                    readTimeout = time.intValue();
                } else {
                    LOGGER.warn("number expected for: {}", key);
                }
                break;
            case "connectTimeout":
                if (value instanceof Number time) {
                    connectTimeout = time.intValue();
                } else {
                    LOGGER.warn("number expected for: {}", key);
                }
                break;
            case "followRedirects":
                if (value instanceof Boolean flag) {
                    followRedirects = flag;
                } else {
                    LOGGER.warn("boolean expected for: {}", key);
                }
                break;
            default:
                LOGGER.warn("unexpected key: {}", key);
        }
        LOGGER.debug("http client configured: {}", key);
        httpClient = null; // will force lazy rebuild
    }

    @SuppressWarnings("deprecation")
    private void initHttpClient() {
        PoolingHttpClientConnectionManagerBuilder connectionManagerBuilder = PoolingHttpClientConnectionManagerBuilder.create();
        HttpClientBuilder clientBuilder = HttpClientBuilder.create();
        clientBuilder.useSystemProperties();
        clientBuilder.disableAutomaticRetries();
        if (followRedirects) {
            clientBuilder.setRedirectStrategy(DefaultRedirectStrategy.INSTANCE);
        } else {
            clientBuilder.disableRedirectHandling();
        }
        clientBuilder.setDefaultCookieStore(new BasicCookieStore());
        if (ssl) {
            KeyStore trustStore = getKeyStore(sslTrustStore, sslTrustStorePassword, sslTrustStoreType);
            KeyStore keyStore = getKeyStore(sslKeyStore, sslKeyStorePassword, sslKeyStoreType);
            SSLContext sslContext;
            try {
                SSLContextBuilder builder = SSLContexts.custom().setProtocol(sslAlgorithm); // will default to TLS if null
                if (trustStore == null && sslTrustAll) {
                    builder = builder.loadTrustMaterial(new TrustAllStrategy());
                } else {
                    if (sslTrustAll) {
                        builder = builder.loadTrustMaterial(trustStore, new TrustSelfSignedStrategy());
                    } else {
                        builder = builder.loadTrustMaterial(trustStore, null); // will use system / java default
                    }
                }
                if (keyStore != null) {
                    char[] keyPassword = sslKeyStorePassword == null ? null : sslKeyStorePassword.toCharArray();
                    builder = builder.loadKeyMaterial(keyStore, keyPassword);
                }
                sslContext = builder.build();
                SSLConnectionSocketFactory socketFactory;
                if (keyStore != null) {
                    socketFactory = new SSLConnectionSocketFactory(sslContext, new NoopHostnameVerifier());
                } else {
                    socketFactory = new LenientSslConnectionSocketFactory(sslContext, new NoopHostnameVerifier());
                }
                connectionManagerBuilder.setSSLSocketFactory(socketFactory);
            } catch (Exception e) {
                LOGGER.error("ssl context init failed: {}", e.getMessage());
                throw new RuntimeException(e);
            }
        } else {
            try {
                SSLContext sslContext = SSLContextBuilder.create().loadTrustMaterial(null, (chain, authType) -> true).build();
                SSLConnectionSocketFactory socketFactory = new LenientSslConnectionSocketFactory(sslContext, new NoopHostnameVerifier());
                connectionManagerBuilder.setSSLSocketFactory(socketFactory);
            } catch (Exception e) {
                LOGGER.error("ssl context init failed: {}", e.getMessage());
                throw new RuntimeException(e);
            }
        }
        connectionManagerBuilder
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setSocketTimeout(readTimeout, TimeUnit.MILLISECONDS)
                        .setConnectTimeout(connectTimeout, TimeUnit.MILLISECONDS)
                        .build())
                .setDefaultSocketConfig(SocketConfig.custom()
                        .setSoTimeout(connectTimeout, TimeUnit.MILLISECONDS).build());
        RequestConfig.Builder configBuilder = RequestConfig.custom()
                .setCookieSpec(StandardCookieSpec.STRICT);
        clientBuilder
                .setDefaultRequestConfig(configBuilder.build())
                .setConnectionManager(connectionManagerBuilder.build())
                .addRequestInterceptorLast(this);
        httpClient = clientBuilder.build();
        LOGGER.debug("http client created");
    }

    @Override
    public HttpResponse invoke(HttpRequest request) {
        this.request = request;
        try {
            ClassicRequestBuilder requestBuilder = ClassicRequestBuilder.create(request.getMethod()).setUri(request.getUrlAndPath());
            if (request.getBody() != null) {
                EntityBuilder entityBuilder = EntityBuilder.create().setBinary(request.getBody());
                List<String> transferEncoding = request.getHeaderValues(Http.Header.TRANSFER_ENCODING.key);
                if (transferEncoding != null) {
                    for (String te : transferEncoding) {
                        if (te == null) {
                            continue;
                        }
                        if (te.contains("chunked")) { // can be comma delimited as per spec
                            entityBuilder.chunked();
                        }
                        if (te.contains("gzip")) {
                            entityBuilder.gzipCompressed();
                        }
                    }
                    request.removeHeader(Http.Header.TRANSFER_ENCODING.key);
                }
                requestBuilder.setEntity(entityBuilder.build());
            }
            if (request.getHeaders() != null) {
                request.getHeaders().forEach((k, vals) -> vals.forEach(v -> requestBuilder.addHeader(k, v)));
            }
            if (httpClient == null) {
                initHttpClient();
            }
            HttpResponse finalResponse = httpClient.execute(requestBuilder.build(), response -> buildResponse(response, startTime));
            finalResponse.setRequest(request);
            logger.logResponse(finalResponse);
            return finalResponse;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private long startTime;

    @Override
    public void process(org.apache.hc.core5.http.HttpRequest hr, EntityDetails entity, HttpContext context) {
        request.setHeaders(toHeaders(hr));
        logger.logRequest(request);
        startTime = System.currentTimeMillis();
    }

    static HttpResponse buildResponse(org.apache.hc.core5.http.HttpResponse httpResponse, long startTime) {
        long endTime = System.currentTimeMillis();
        int statusCode = httpResponse.getCode();
        Map<String, List<String>> headers = toHeaders(httpResponse);
        HttpResponse response = new HttpResponse();
        response.setStartTime(startTime);
        response.setResponseTime(endTime - startTime);
        response.setStatus(statusCode);
        response.setHeaders(headers);
        if (httpResponse instanceof ClassicHttpResponse classicHttpResponse) {
            HttpEntity entity = classicHttpResponse.getEntity();
            if (entity != null) {
                try {
                    byte[] bytes = EntityUtils.toByteArray(entity);
                    response.setBody(bytes, null);
                    response.setContentLength(bytes.length);
                } catch (Exception e) {
                    LOGGER.warn("error extracting response body: {}", e.getMessage());
                }
            }
        }
        return response;
    }

    private static Map<String, List<String>> toHeaders(HttpMessage msg) {
        Header[] headers = msg.getHeaders();
        Map<String, List<String>> map = new LinkedHashMap<>(headers.length);
        for (Header outer : headers) {
            String name = outer.getName();
            Header[] inner = msg.getHeaders(name);
            List<String> list = new ArrayList<>(inner.length);
            for (Header h : inner) {
                list.add(h.getValue());
            }
            map.put(name, list);
        }
        return map;
    }

    private static KeyStore getKeyStore(String trustStoreFile, String password, String type) {
        if (trustStoreFile == null) {
            return null;
        }
        char[] passwordChars = password == null ? null : password.toCharArray();
        if (type == null) {
            type = KeyStore.getDefaultType();
        }
        try {
            KeyStore keyStore = KeyStore.getInstance(type);
            File file = new File(trustStoreFile); // TODO relative path
            InputStream is = new FileInputStream(file);
            keyStore.load(is, passwordChars);
            LOGGER.debug("key store key count for {}: {}", trustStoreFile, keyStore.size());
            return keyStore;
        } catch (Exception e) {
            LOGGER.error("key store init failed: {}", e.getMessage());
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("deprecation")
    static class LenientSslConnectionSocketFactory extends SSLConnectionSocketFactory {

        LenientSslConnectionSocketFactory(SSLContext sslContext, HostnameVerifier hostnameVerifier) {
            super(sslContext, hostnameVerifier);
        }

        @Override
        public Socket createLayeredSocket(Socket socket, String target, int port, HttpContext context) throws IOException {
            return super.createLayeredSocket(socket, "", port, context);
        }

    }

    @Override
    public void close() throws IOException {
        if (httpClient != null) {
            try {
                httpClient.close();
                LOGGER.debug("http client closed");
            } finally {
                httpClient = null;
            }
        }
    }

}
