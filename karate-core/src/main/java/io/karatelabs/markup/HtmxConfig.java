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
package io.karatelabs.markup;

/**
 * Configuration for the HTMX dialect.
 * Controls how ka:get, ka:post, ka:vals and other HTMX-related attributes are processed.
 */
public class HtmxConfig {

    private String contextPath = "";
    private boolean addIndicatorClass = true;

    public HtmxConfig() {
    }

    /**
     * Get the context path to prepend to HTMX URLs.
     * @return the context path (empty string by default)
     */
    public String getContextPath() {
        return contextPath;
    }

    /**
     * Set the context path to prepend to HTMX URLs.
     * For example, if set to "/app", then ka:get="/users" becomes hx-get="/app/users".
     * @param contextPath the context path
     * @return this config for chaining
     */
    public HtmxConfig setContextPath(String contextPath) {
        this.contextPath = contextPath == null ? "" : contextPath;
        return this;
    }

    /**
     * Whether to add the "htmx-indicator" class to elements with HTMX requests.
     * @return true if indicator class should be added
     */
    public boolean isAddIndicatorClass() {
        return addIndicatorClass;
    }

    /**
     * Set whether to add the "htmx-indicator" class to elements with HTMX requests.
     * @param addIndicatorClass true to add the class
     * @return this config for chaining
     */
    public HtmxConfig setAddIndicatorClass(boolean addIndicatorClass) {
        this.addIndicatorClass = addIndicatorClass;
        return this;
    }

}
