/*
 * The MIT License
 *
 * Copyright 2024 Karate Labs Inc.
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
package io.karatelabs.common;

import java.io.File;

public class Source {

    public final Resource resource;
    private String text;

    String[] lines;

    public static Source of(String text) {
        return new Source(null, text);
    }

    public static Source of(File file) {
        Resource resource = Resource.file(file);
        return new Source(resource, null);
    }

    private Source(Resource resource, String text) {
        this.resource = resource;
        this.text = text;
    }

    public String getText() {
        if (text == null) {
            text = resource.getText();
        }
        return text;
    }

    public String getLine(int index) {
        if (lines == null) {
            lines = getText().split("\\r?\\n");
        }
        return lines[index];
    }

    @Override
    public String toString() {
        return getText();
    }

    public String getPathForLog() {
        if (resource != null) {
            return resource.getUri().toString();
        } else {
            return "(inline)";
        }
    }

}
