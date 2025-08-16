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
package io.karatelabs.common;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;

public class UrlResource implements Resource {

    private byte[] bytes;
    private final URL url;
    private final boolean classpath;
    private final boolean file;
    private final String relativePath;

    private String text;
    private String[] lines;

    public UrlResource(File file, boolean classpath) {
        if (file.isAbsolute()) {
            this.relativePath = FileUtils.WORKING_DIR.toPath().relativize(file.toPath()).toString();
        } else {
            this.relativePath = file.getPath();
        }
        try {
            url = file.toURI().toURL();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        this.classpath = classpath;
        this.file = true;
    }

    public UrlResource(URL url, boolean classpath) {
        this.url = url;
        this.classpath = classpath;
        this.file = "file".equals(url.getProtocol());
        this.relativePath = url.getPath();
    }

    @Override
    public String getText() {
        if (text == null) {
            text = FileUtils.toString(getStream());
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
    public boolean isClassPath() {
        return classpath;
    }

    @Override
    public boolean isFile() {
        return file;
    }

    @Override
    public String getRelativePath() {
        return relativePath;
    }

    @Override
    public File getFile() {
        if (isFile()) {
            try {
                return new File(url.toURI());
            } catch (Exception e) {
                throw new RuntimeException("Failed to convert URL to File: " + url, e);
            }
        }
        return null;
    }

    @Override
    public URI getUri() {
        try {
            return url.toURI();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Resource resolve(String path) {
        String relativePath = url.getPath();
        int pos = relativePath.lastIndexOf('/');
        String parentPath = pos == -1 ? "" : relativePath.substring(0, pos);
        if (classpath) {
            return Resource.path("classpath:" + parentPath + "/" + path);
        } else {
            return Resource.path(parentPath + "/" + path);
        }
    }

    @Override
    public InputStream getStream() {
        if (bytes == null) {
            bytes = FileUtils.toBytes(url);
        }
        return new ByteArrayInputStream(bytes);
    }

    @Override
    public String toString() {
        return relativePath;
    }

}
