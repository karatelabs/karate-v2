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

import io.karatelabs.common.FileUtils;
import io.karatelabs.common.Json;
import io.karatelabs.common.ResourceType;
import io.karatelabs.common.StringUtils;
import io.karatelabs.js.Invokable;
import io.karatelabs.js.SimpleObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class HttpResponse implements SimpleObject {

    private static final Logger logger = LoggerFactory.getLogger(HttpResponse.class);

    private int status = 200;
    private String statusText;
    private Map<String, List<String>> headers;
    private Object body;
    private ResourceType resourceType;
    private long responseTime;
    private int contentLength;
    private HttpRequest request;

    public ResourceType getResourceType() {
        if (resourceType == null) {
            String contentType = getContentType();
            if (contentType != null) {
                resourceType = ResourceType.fromContentType(contentType);
            }
        }
        return resourceType;
    }

    public String getContentType() {
        return getHeader(Http.Header.CONTENT_TYPE.key);
    }

    public String getHeader(String name) {
        List<String> values = getHeaderValues(name);
        return values == null || values.isEmpty() ? null : values.getLast();
    }

    public List<String> getHeaderValues(String name) { // TOTO optimize
        return StringUtils.getIgnoreKeyCase(headers, name);
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public void setContentLength(int contentLength) {
        this.contentLength = contentLength;
    }

    public int getContentLength() {
        return contentLength;
    }

    public String getStatusText() {
        return statusText;
    }

    public void setStatusText(String statusText) {
        this.statusText = statusText;
    }

    public Map<String, List<String>> getHeaders() {
        return headers;
    }

    public void setContentType(String contentType) {
        setHeader(Http.Header.CONTENT_TYPE.key, contentType);
    }

    public void setHeader(String name, List<String> values) {
        if (headers == null) {
            headers = new HashMap<>();
        }
        headers.put(name, values);
    }

    public HttpRequest getRequest() {
        return request;
    }

    public void setRequest(HttpRequest request) {
        this.request = request;
    }

    public void setHeader(String name, String... values) {
        setHeader(name, Arrays.asList(values));
    }

    @SuppressWarnings("unchecked")
    public void setHeaders(Map<String, ?> map) {
        if (map == null) {
            return;
        }
        map.forEach((k, v) -> {
            if (v instanceof List) {
                setHeader(k, (List<String>) v);
            } else if (v != null) {
                setHeader(k, v.toString());
            }
        });
    }

    public Object getBody() {
        return body;
    }

    public byte[] getBodyBytes() {
        return Json.toBytes(body);
    }

    public String getBodyString() {
        return FileUtils.toString(Json.toBytes(body));
    }

    public void setBody(Object body) {
        this.body = body;
    }

    public Object getBodyConverted() {
        if (body instanceof byte[] bytes) {
            ResourceType rt = getResourceType(); // derive if needed
            if (rt != null && rt.isBinary()) {
                return body;
            }
            return Http.fromBytes(bytes, false, rt);
        } else if (body instanceof String text) {
            return Http.fromString(text, false, getResourceType());
        } else {
            return body;
        }
    }

    public long getResponseTime() {
        return responseTime;
    }

    public void setResponseTime(long responseTime) {
        this.responseTime = responseTime;
    }

    public void setResourceType(ResourceType resourceType) {
        this.resourceType = resourceType;
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("status", status);
        map.put("headers", headers);
        map.put("body", getBodyConverted());
        map.put("responseTime", responseTime);
        map.put("contentLength", contentLength);
        return map;
    }

    private Invokable header() {
        return args -> {
            if (args.length > 0) {
                return getHeader(args[0] + "");
            } else {
                throw new RuntimeException("missing argument for header()");
            }
        };
    }

    private Invokable headerValues() {
        return args -> {
            if (args.length > 0) {
                return getHeaderValues(args[0] + "");
            } else {
                throw new RuntimeException("missing argument for headerValues()");
            }
        };
    }

    @Override
    public Object get(String key) {
        return switch (key) {
            case "status" -> status;
            case "statusText" -> statusText;
            case "responseTime" -> responseTime;
            case "headers" -> headers;
            case "header" -> header();
            case "headerValues" -> headerValues();
            case "body" -> getBodyConverted();
            case "bodyString" -> getBodyString();
            case "bodyBytes" -> Json.toBytes(body);
            case "request" -> request;
            default ->
                // logger.warn("get - unexpected key: {}", key);
                    null;
        };
    }

    @SuppressWarnings("unchecked")
    @Override
    public void put(String key, Object value) {
        switch (key) {
            case "body":
                body = value;
                break;
            case "status":
                status = ((Number) value).intValue();
                break;
            case "headers":
                setHeaders((Map<String, Object>) value);
                break;
            default:
                logger.warn("put - unexpected key: {}", key);
        }
    }

}
