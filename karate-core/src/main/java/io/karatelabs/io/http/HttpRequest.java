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
import io.karatelabs.common.Pair;
import io.karatelabs.common.ResourceType;
import io.karatelabs.common.StringUtils;
import io.karatelabs.js.Invokable;
import io.karatelabs.js.SimpleObject;
import io.netty.handler.codec.http.QueryStringDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class HttpRequest implements SimpleObject {

    private static final Logger logger = LoggerFactory.getLogger(HttpRequest.class);

    private String method;
    private String urlAndPath;
    private String urlBase;
    private String path;
    private String pathOriginal;
    private Map<String, List<String>> params;
    private Map<String, List<String>> headers;
    private byte[] body;
    private String bodyDisplay;
    private ResourceType resourceType;
    private Map<String, String> pathParams;
    private String pathPattern;

    public void setUrl(String url) {
        urlAndPath = url;
        Pair<String> pair = Http.parseUriIntoUrlBaseAndPath(url);
        urlBase = pair.left;
        QueryStringDecoder qsd = new QueryStringDecoder(pair.right);
        setPath(qsd.path());
        setParams(qsd.parameters());
    }

    public String getUrlAndPath() {
        return urlAndPath != null ? urlAndPath : (urlBase != null ? urlBase : "") + path;
    }

    public void setPath(String path) {
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        if (path.charAt(0) != '/') { // mocks and synthetic situations
            path = "/" + path;
        }
        this.path = path;
        if (pathOriginal == null) {
            pathOriginal = path;
        }
    }

    public void setUrlBase(String urlBase) {
        this.urlBase = urlBase;
    }

    public void setParams(Map<String, List<String>> params) {
        this.params = params;
    }

    public String getPath() {
        return path;
    }

    public String getPathRaw() {
        if (urlBase != null && urlAndPath != null) {
            if (urlAndPath.charAt(0) == '/') {
                return urlAndPath;
            } else {
                return urlAndPath.substring(urlBase.length());
            }
        } else {
            return path;
        }
    }

    public boolean pathMatches(String pattern) {
        Map<String, String> temp = Http.parseUriPattern(pattern, path);
        if (temp == null) {
            return false;
        }
        pathParams = temp;
        pathPattern = pattern;
        return true;
    }

    public Map<String, String> getPathParams() {
        return pathParams == null ? Collections.emptyMap() : pathParams;
    }

    public void putHeader(String name, String... values) {
        putHeader(name, Arrays.asList(values));
    }

    public void putHeader(String name, List<String> values) {
        if (headers == null) {
            headers = new HashMap<>();
        }
        for (String key : headers.keySet()) {
            if (key.equalsIgnoreCase(name)) {
                name = key;
                break;
            }
        }
        headers.put(name, values);
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method.toUpperCase();
    }

    public Map<String, List<String>> getHeaders() {
        return headers;
    }

    public void setHeaders(Map<String, List<String>> headers) {
        this.headers = headers;
    }

    public byte[] getBody() {
        return body;
    }

    public String getBodyString() {
        return FileUtils.toString(body);
    }

    public void setBody(byte[] body) {
        this.body = body;
    }

    public String getBodyDisplay() {
        return bodyDisplay;
    }

    public void setBodyDisplay(String bodyDisplay) {
        this.bodyDisplay = bodyDisplay;
    }

    public List<String> getHeaderValues(String name) { // TOTO optimize
        return StringUtils.getIgnoreKeyCase(headers, name);
    }

    public List<String> getParamValues(String name) {
        return params.get(name);
    }

    public String getParam(String name) {
        List<String> values = getParamValues(name);
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.getFirst();
    }

    public void removeHeader(String name) {
        if (headers == null) {
            return;
        }
        for (String key : headers.keySet()) {
            if (key.equalsIgnoreCase(name)) {
                name = key;
                break;
            }
        }
        headers.remove(name);
    }

    public String getHeader(String name) {
        List<String> values = getHeaderValues(name);
        return values == null || values.isEmpty() ? null : values.getLast();
    }

    public String getContentType() {
        return getHeader(Http.Header.CONTENT_TYPE.key);
    }

    public void setContentType(String contentType) {
        putHeader(Http.Header.CONTENT_TYPE.key, contentType);
    }

    public ResourceType getResourceType() {
        if (resourceType == null) {
            String contentType = getContentType();
            if (contentType != null) {
                resourceType = ResourceType.fromContentType(contentType);
            }
        }
        return resourceType;
    }

    public Object getBodyConverted() {
        ResourceType rt = getResourceType(); // derive if needed
        if (rt != null && rt.isBinary()) {
            return body;
        }
        return Http.fromBytes(body, false, rt);
    }

    public Map<String, Object> toBlockData() {
        Map<String, Object> request = new HashMap<>();
        request.put("method", method);
        request.put("url", urlBase);
        if (path != null) {
            request.put("path", path);
        }
        List<Map<String, Object>> paramsList = new ArrayList<>();
        request.put("params", paramsList);
        if (params != null) {
            for (String name : params.keySet()) {
                List<String> values = params.get(name);
                if (values == null || values.isEmpty()) {
                    continue;
                }
                Map<String, Object> map = new HashMap<>();
                paramsList.add(map);
                map.put("name", name);
                map.put("value", String.join(",", values));
            }
        }
        List<Map<String, Object>> headersList = new ArrayList<>();
        request.put("headers", headersList);
        if (headers != null) {
            for (String name : headers.keySet()) {
                List<String> values = headers.get(name);
                if (values == null || values.isEmpty()) {
                    continue;
                }
                Map<String, Object> map = new HashMap<>();
                headersList.add(map);
                map.put("name", name);
                map.put("value", String.join(",", values));
            }
        }
        if (body != null) {
            request.put("body", getBodyConverted());
        }
        Map<String, Object> data = new HashMap<>();
        data.put("request", request);
        return data;
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("url", urlAndPath);
        map.put("urlBase", urlBase);
        map.put("path", path);
        map.put("pathRaw", getPathRaw());
        map.put("method", method);
        map.put("headers", headers);
        map.put("params", params);
        map.put("body", getBodyConverted());
        return map;
    }

    @Override
    public String toString() {
        return method + " " + urlAndPath;
    }

    public HttpRequestBuilder toHttpRequestBuilder(HttpClient client) {
        HttpRequestBuilder builder = new HttpRequestBuilder(client);
        builder.method(method);
        if (urlBase != null) {
            builder.url(urlBase);
        }
        if (path != null) {
            builder.path(path);
        }
        if (params != null) {
            builder.params(params);
        }
        if (headers != null) {
            headers.forEach((name, values) -> {
                if (values != null && !values.isEmpty()) {
                    builder.header(name, values);
                }
            });
        }
        if (body != null) {
            builder.body(getBodyConverted());
        }
        return builder;
    }

    private Invokable param() {
        return args -> {
            if (args.length > 0) {
                return getParam(args[0] + "");
            } else {
                throw new RuntimeException("missing argument for param()");
            }
        };
    }

    private Invokable paramValues() {
        return args -> {
            if (args.length > 0) {
                return getParamValues(args[0] + "");
            } else {
                throw new RuntimeException("missing argument for paramValues()");
            }
        };
    }

    private Invokable paramInt() {
        return args -> {
            if (args.length > 0) {
                String val = getParam(args[0] + "");
                return val == null ? null : Integer.parseInt(val);
            } else {
                throw new RuntimeException("missing argument for paramInt()");
            }
        };
    }

    private Invokable paramJson() {
        return args -> {
            if (args.length > 0) {
                String val = getParam(args[0] + "");
                return val == null ? null : Json.of(val).value();
            } else {
                throw new RuntimeException("missing argument for paramJson()");
            }
        };
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

    private Invokable pathMatches() {
        return args -> {
            if (args.length > 0) {
                return pathMatches(args[0] + "");
            } else {
                throw new RuntimeException("missing argument for pathMatches()");
            }
        };
    }

    @Override
    public Object jsGet(String key) {
        switch (key) {
            case "method":
                return method;
            case "body":
                return getBodyConverted();
            case "bodyString":
                return getBodyString();
            case "bodyBytes":
                return body;
            case "url":
                return urlAndPath;
            case "urlBase":
                return urlBase;
            case "path":
                return path;
            case "pathRaw":
                return getPathRaw();
            case "params":
                return StringUtils.simplify(params, true);
            case "param":
                return param();
            case "paramInt":
                return paramInt();
            case "paramJson":
                return paramJson();
            case "paramValues":
                return paramValues();
            case "headers":
                return headers;
            case "header":
                return header();
            case "headerValues":
                return headerValues();
            case "pathMatches":
                return pathMatches();
            case "pathParams":
                return getPathParams();
            case "get":
            case "post":
            case "put":
            case "delete":
            case "patch":
            case "head":
            case "options":
            case "trace":
                return method.toLowerCase().equals(key);
            default:
                logger.warn("get - unexpected key: {}", key);
        }
        return null;
    }

}
