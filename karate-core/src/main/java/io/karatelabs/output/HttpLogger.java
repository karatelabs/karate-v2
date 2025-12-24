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
package io.karatelabs.output;

import io.karatelabs.common.FileUtils;
import io.karatelabs.common.ResourceType;
import io.karatelabs.io.http.HttpRequest;
import io.karatelabs.io.http.HttpResponse;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class HttpLogger {

    private int requestCount;
    private Consumer<String> logger = s -> LogContext.get().log(s);

    public void setLogger(Consumer<String> logger) {
        this.logger = logger;
    }

    public static void logHeaders(StringBuilder sb, int num, String prefix, Map<String, List<String>> headers) {
        if (headers == null || headers.isEmpty()) {
            return;
        }
        headers.forEach((k, v) -> {
            for (String value : v) {
                sb.append(num).append(prefix).append(k).append(": ");
                sb.append(value);
                sb.append('\n');
            }
        });
    }

    public void logBody(StringBuilder sb, byte[] body, ResourceType rt) {
        if (body == null) {
            return;
        }
        sb.append(FileUtils.toString(body));
        sb.append('\n');
    }

    public static String getStatusFailureMessage(int expected, HttpRequest request, HttpResponse response) {
        String url = request.getUrlAndPath();
        String rawResponse = response.getBodyString();
        long responseTime = response.getResponseTime();
        return "status code was: " + response.getStatus() + ", expected: " + expected
                + ", response time in milliseconds: " + responseTime + ", url: " + url
                + ", response: \n" + rawResponse;
    }

    public void logRequest(HttpRequest request) {
        requestCount++;
        StringBuilder sb = new StringBuilder();
        String uri = request.getUrlAndPath();
        sb.append("request:\n").append(requestCount).append(" > ")
                .append(request.getMethod()).append(' ').append(uri).append("\n");
        logHeaders(sb, requestCount, " > ", request.getHeaders());
        ResourceType rt = ResourceType.fromContentType(request.getContentType());
        if (rt == null || rt.isBinary()) {
            // don't log body
        } else {
            byte[] body;
            if (rt == ResourceType.MULTIPART) {
                body = request.getBodyDisplay() == null ? null : request.getBodyDisplay().getBytes();
            } else {
                body = request.getBody();
            }
            logBody(sb, body, rt);
        }
        logger.accept(sb.toString());
    }

    public void logResponse(HttpResponse response) {
        HttpRequest request = response.getRequest();
        StringBuilder sb = new StringBuilder();
        sb.append("response time in milliseconds: ").append(response.getResponseTime()).append('\n');
        sb.append(requestCount).append(" < ").append(response.getStatus()).append(' ')
                .append(request.getMethod()).append(' ').append(request.getPath())
                .append('\n');
        logHeaders(sb, requestCount, " < ", response.getHeaders());
        ResourceType rt = response.getResourceType();
        if (rt == null || rt.isBinary()) {
            // don't log body
        } else {
            logBody(sb, response.getBodyBytes(), rt);
        }
        logger.accept(sb.toString());
    }

}
