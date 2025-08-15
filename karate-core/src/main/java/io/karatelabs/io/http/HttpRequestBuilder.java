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

import io.karatelabs.common.Json;
import io.karatelabs.common.ResourceType;
import io.karatelabs.common.StringUtils;
import io.karatelabs.js.Invokable;
import io.karatelabs.js.SimpleObject;
import io.netty.handler.codec.http.cookie.ClientCookieEncoder;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import org.apache.hc.core5.net.URIBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.util.*;
import java.util.stream.Collectors;

public class HttpRequestBuilder implements SimpleObject {

    private static final Logger logger = LoggerFactory.getLogger(HttpRequestBuilder.class);

    private String url;
    private String method;
    private List<String> paths;
    private Map<String, List<String>> params;
    private Map<String, List<String>> headers;
    private MultiPartBuilder multiPart;
    private Object body;
    private Set<Cookie> cookies;
    private String charset;

    private final HttpClient client;

    public HttpRequestBuilder(HttpClient client) {
        this.client = client;
    }

    public HttpRequestBuilder() {
        this(null);
    }

    private void reset() {
        // url = null;
        method = null;
        paths = null;
        params = null;
        headers = null;
        multiPart = null;
        body = null;
        cookies = null;
        charset = null;
    }

    public HttpRequestBuilder copy() {
        HttpRequestBuilder hrb = new HttpRequestBuilder();
        hrb.url = url;
        hrb.method = method;
        hrb.paths = paths;
        hrb.params = params;
        hrb.headers = headers;
        hrb.multiPart = multiPart;
        hrb.body = body;
        hrb.cookies = cookies;
        hrb.charset = charset;
        return hrb;
    }

    public HttpRequest build() {
        buildInternal();
        HttpRequest request = new HttpRequest();
        request.setMethod(method);
        request.setUrl(getUri());
        if (multiPart != null) {
            request.setBodyDisplay(multiPart.getBodyForDisplay());
        }
        if (body != null) {
            request.setBody(Json.toBytes(body));
        }
        request.setHeaders(headers);
        return request;
    }

    public HttpResponse invoke() {
        HttpRequest request = build();
        if (client == null) {
            throw new RuntimeException("http client not set");
        }
        reset();
        return client.invoke(request);
    }

    public HttpResponse invoke(String method) {
        this.method = method;
        return invoke();
    }

    public HttpResponse invoke(String method, Object body) {
        this.method = method;
        this.body = body;
        return invoke();
    }

    public HttpRequestBuilder charset(String charset) {
        this.charset = charset;
        return this;
    }

    public HttpRequestBuilder url(String value) {
        url = value;
        return this;
    }

    public HttpRequestBuilder method(String method) {
        this.method = method;
        return this;
    }

    public HttpRequestBuilder paths(String... paths) {
        for (String path : paths) {
            path(path);
        }
        return this;
    }

    public HttpRequestBuilder path(String path) {
        if (path == null) {
            return this;
        }
        if (paths == null) {
            paths = new ArrayList<>();
        }
        paths.add(path);
        return this;
    }

    public List<String> getPaths() {
        return paths;
    }

    public Object getBody() {
        return body;
    }

    public HttpRequestBuilder body(Object body) {
        this.body = body;
        return this;
    }

    public HttpRequestBuilder bodyJson(String json) {
        this.body = Json.of(json).value();
        return this;
    }

    public Map<String, String> getHeaders() {
        if (headers == null) {
            return new LinkedHashMap<>(0);
        }
        Map<String, String> map = new LinkedHashMap<>(headers.size());
        headers.forEach((k, v) -> {
            if (v != null && !v.isEmpty()) {
                Object value = v.getFirst();
                if (value != null) {
                    map.put(k, value.toString());
                }
            }
        });
        return map;
    }

    public List<String> getHeaderValues(String name) {
        return StringUtils.getIgnoreKeyCase(headers, name);
    }

    public String getHeader(String name) {
        List<String> list = getHeaderValues(name);
        if (list == null || list.isEmpty()) {
            return null;
        } else {
            return list.getFirst();
        }
    }

    public String getContentType() {
        return getHeader(Http.Header.CONTENT_TYPE.key);
    }

    public HttpRequestBuilder removeHeader(String name) {
        if (headers != null) {
            StringUtils.removeIgnoreKeyCase(headers, name);
        }
        return this;
    }

    public HttpRequestBuilder header(String name, String... values) {
        return header(name, Arrays.asList(values));
    }

    public HttpRequestBuilder header(String name, List<String> values) {
        if (headers == null) {
            headers = new LinkedHashMap<>();
        }
        for (String key : headers.keySet()) {
            if (key.equalsIgnoreCase(name)) {
                name = key;
                break;
            }
        }
        headers.put(name, values);
        return this;
    }

    public HttpRequestBuilder header(String name, String value) {
        return header(name, Collections.singletonList(value));
    }

    @SuppressWarnings("unchecked")
    public HttpRequestBuilder headers(Map<String, Object> map) {
        map.forEach((k, v) -> {
            if (v instanceof List) {
                header(k, (List<String>) v);
            } else if (v != null) {
                header(k, v.toString());
            }
        });
        return this;
    }

    public HttpRequestBuilder contentType(String contentType) {
        if (contentType != null) {
            header(Http.Header.CONTENT_TYPE.key, contentType);
        }
        return this;
    }

    public List<String> getParam(String name) {
        if (params == null || name == null) {
            return null;
        }
        return params.get(name);
    }

    public HttpRequestBuilder param(String name, String... values) {
        return param(name, Arrays.asList(values));
    }

    public HttpRequestBuilder param(String name, List<String> values) {
        if (params == null) {
            params = new LinkedHashMap<>();
        }
        List<String> notNullValues = values.stream().filter(Objects::nonNull).collect(Collectors.toList());
        if (!notNullValues.isEmpty()) {
            params.put(name, notNullValues);
        }
        return this;
    }

    public HttpRequestBuilder params(Map<String, List<String>> params) {
        this.params = params;
        return this;
    }

    public HttpRequestBuilder cookies(Collection<Map<String, Object>> cookies) {
        for (Map<String, Object> map : cookies) {
            cookie(map);
        }
        return this;
    }

    public HttpRequestBuilder cookie(Map<String, Object> map) {
        return cookie(Cookies.fromMap(map));
    }

    public HttpRequestBuilder cookie(Cookie cookie) {
        if (cookies == null) {
            cookies = new HashSet<>();
        }
        cookies.add(cookie);
        return this;
    }

    public HttpRequestBuilder cookie(String name, String value) {
        return cookie(new DefaultCookie(name, value));
    }

    public HttpRequestBuilder formField(String name, Object value) {
        if (multiPart == null) {
            multiPart = new MultiPartBuilder(false, charset);
        }
        multiPart.part(name, value);
        return this;
    }

    public HttpRequestBuilder multiPartJson(String json) {
        return multiPart(Json.of(json).value());
    }

    public HttpRequestBuilder multiPart(Map<String, Object> map) {
        if (multiPart == null) {
            multiPart = new MultiPartBuilder(true, charset);
        }
        multiPart.part(map);
        return this;
    }

    public String getUri() {
        try {
            URIBuilder builder;
            if (url == null) {
                builder = new URIBuilder();
            } else {
                builder = new URIBuilder(url);
            }
            if (params != null) {
                params.forEach((key, values) -> values.forEach(value -> builder.addParameter(key, value)));
            }
            if (paths != null) {
                List<String> segments = new ArrayList<>();
                for (String item : builder.getPathSegments()) {
                    if (!item.isEmpty()) {
                        segments.add(item);
                    }
                }
                Iterator<String> pathIterator = paths.iterator();
                while (pathIterator.hasNext()) {
                    String item = pathIterator.next();
                    if (!pathIterator.hasNext() && "/".equals(item)) { // preserve trailing slash
                        segments.add("");
                    } else {
                        segments.addAll(StringUtils.split(item, '/', true));
                    }
                }
                builder.setPathSegments(segments);
            }
            URI uri = builder.build();
            return uri.toASCIIString();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    private void buildInternal() {
        if (url == null) {
            throw new RuntimeException("incomplete http request, 'url' not set");
        }
        if (method == null) {
            if (multiPart != null && multiPart.isMultipart()) {
                method = "POST";
            } else {
                method = "GET";
            }
        }
        method = method.toUpperCase();
        if ("GET".equals(method) && multiPart != null) {
            Map<String, Object> parts = multiPart.getFormFields();
            if (parts != null) {
                parts.forEach((k, v) -> param(k, (String) v));
            }
            multiPart = null;
        }
        if (multiPart != null) {
            if (body == null) { // this is not-null only for a re-try, don't rebuild multi-part
                body = multiPart.build();
                String userContentType = getHeader(Http.Header.CONTENT_TYPE.key);
                if (userContentType != null) {
                    String boundary = multiPart.getBoundary();
                    if (boundary != null) {
                        contentType(userContentType + "; boundary=" + boundary);
                    }
                } else {
                    contentType(multiPart.getContentTypeHeader());
                }
            }
        }
        if (cookies != null && !cookies.isEmpty()) {
            List<String> cookieValues = new ArrayList<>(cookies.size());
            for (Cookie c : cookies) {
                String cookieValue = ClientCookieEncoder.LAX.encode(c);
                cookieValues.add(cookieValue);
            }
            header(Http.Header.COOKIE.key, StringUtils.join(cookieValues, "; "));
        }
        if (body != null) {
            if (multiPart == null) {
                String contentType = getContentType();
                if (contentType == null) {
                    ResourceType rt = ResourceType.fromObject(body);
                    if (rt != null) {
                        contentType = rt.contentType;
                    }
                }
                Charset cs = contentType == null ? null : Http.parseContentTypeCharset(contentType);
                if (cs == null) {
                    if (charset != null) {
                        // edge case, support setting content type to an empty string
                        contentType = StringUtils.trimToNull(contentType);
                        if (contentType != null) {
                            contentType = contentType + "; charset=" + charset;
                        }
                    }
                }
                contentType(contentType);
            }
        }
    }

    public Map<String, Object> toHttpRequestData() {
        buildInternal();
        Map<String, Object> map = new HashMap<>();
        map.put("url", getUri());
        map.put("method", method);
        if (headers != null) {
            List<Map<String, Object>> list = new ArrayList<>(headers.size());
            map.put("headers", list);
            headers.forEach((k, v) -> {
                if (v != null) {
                    v.forEach(value -> {
                        if (value != null) {
                            Map<String, Object> header = new HashMap<>();
                            header.put("name", k);
                            header.put("value", value);
                            list.add(header);
                        }
                    });
                }
            });
        }
        if (params != null) {
            List<Map<String, Object>> list = new ArrayList<>(params.size());
            map.put("params", list);
            params.forEach((k, v) -> {
                if (v != null) {
                    v.forEach(value -> {
                        if (value != null) {
                            Map<String, Object> header = new HashMap<>();
                            header.put("name", k);
                            header.put("value", value);
                            list.add(header);
                        }
                    });
                }
            });
        }
        if (body != null) {
            map.put("body", body);
        }
        return map;
    }

    private static final List<String> CURL_IGNORED_HEADERS = Arrays.asList(
            "accept-encoding",
            "connection",
            "host",
            "user-agent",
            "content-length"
    );

    public String toCurlCommand() {
        buildInternal();
        StringBuilder sb = new StringBuilder();
        sb.append("curl ");
        sb.append("-X ").append(method).append(' ');
        String url = getUri();
        if (!StringUtils.isBlank(url)) {
            sb.append(getUri()).append(' ');
        }
        if (headers != null && !headers.isEmpty()) {
            headers.forEach((name, values) -> {
                if (!CURL_IGNORED_HEADERS.contains(name.toLowerCase())) {
                    if (values != null && !values.isEmpty()) {
                        values.forEach(value -> {
                            if (value != null) {
                                sb.append(" \\\n");
                                sb.append("-H \"").append(name).append(": ").append(value).append("\"");
                            }
                        });
                    }
                }
            });
        }
        if (multiPart != null) {
            sb.append(" \\\n");
            sb.append(multiPart.toCurlCommand());
        } else if (body != null) {
            sb.append(" \\\n");
            String raw = Json.toJson(body);
            sb.append("-d '").append(raw).append("'");
        } else if (params != null && !params.isEmpty() && !method.equals("GET")) {
            // For non-GET requests with parameters but no body or multipart, add parameters as form fields
            sb.append(" \\\n");
            Iterator<Map.Entry<String, List<String>>> paramsIterator = params.entrySet().iterator();
            while (paramsIterator.hasNext()) {
                Map.Entry<String, List<String>> entry = paramsIterator.next();
                String name = entry.getKey();
                List<String> values = entry.getValue();
                if (values != null && !values.isEmpty()) {
                    Iterator<String> valuesIterator = values.iterator();
                    while (valuesIterator.hasNext()) {
                        String value = valuesIterator.next();
                        if (value != null) {
                            sb.append("-d ").append(name).append("=").append(value);
                            if (valuesIterator.hasNext() || paramsIterator.hasNext()) {
                                sb.append(" \\\n");
                            }
                        }
                    }
                }
            }
        }
        return sb.toString();
    }

    private Invokable method() {
        return args -> {
            if (args.length > 1) {
                body = args[1];
            }
            if (args.length > 0) {
                method = args[0] + "";
            }
            return invoke();
        };
    }

    private Invokable header() {
        return args -> {
            if (args.length < 2) {
                throw new RuntimeException("header() needs two arguments");
            }
            header(args[0] + "", args[1] + "");
            return this;
        };
    }

    private Invokable param() {
        return args -> {
            if (args.length < 2) {
                throw new RuntimeException("param() needs two arguments");
            }
            param(args[0] + "", args[1] + "");
            return this;
        };
    }

    private Invokable path() {
        return args -> {
            for (Object arg : args) {
                if (arg != null) {
                    path(arg + "");
                }
            }
            return this;
        };
    }

    private Invokable body() {
        return args -> {
            if (args.length == 0) {
                throw new RuntimeException("body() needs at least one argument");
            }
            body(args[0]);
            return this;
        };
    }

    @Override
    public Object get(String key) {
        switch (key) {
            case "get":
            case "post":
            case "put":
            case "delete":
            case "head":
            case "options":
            case "trace":
            case "connect":
            case "patch":
                return (Invokable) args -> args.length > 0 ? invoke(key, args[0]) : invoke(key);
            case "method":
                return method();
            case "header":
                return header();
            case "param":
                return param();
            case "path":
                return path();
            case "body":
                return body();
        }
        logger.warn("unexpected key: {}", key);
        return null;
    }

}
