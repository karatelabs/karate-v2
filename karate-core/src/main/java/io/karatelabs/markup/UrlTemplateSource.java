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

import io.karatelabs.common.FileUtils;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.net.URL;

public class UrlTemplateSource implements TemplateSource {

    private byte[] bytes;
    private final URL url;
    private final boolean classpath;

    public UrlTemplateSource(File file, boolean classpath) {
        try {
            url = file.toURI().toURL();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        this.classpath = classpath;
    }

    public UrlTemplateSource(URL url, boolean classpath) {
        this.url = url;
        this.classpath = classpath;
    }

    @Override
    public boolean isFile() {
        return "file".equals(url.getProtocol());
    }

    @Override
    public File getFile() {
        return isFile() ? new File(url.getFile()) : null;
    }

    @Override
    public URL getUrl() {
        return url;
    }

    @Override
    public String getPath() {
        return url.getPath();
    }

    @Override
    public TemplateSource resolve(String path) {
        String relativePath = url.getPath();
        int pos = relativePath.lastIndexOf('/');
        String parentPath = pos == -1 ? "" : relativePath.substring(0, pos);
        if (classpath) {
            return TemplateSource.get("classpath:" + parentPath + "/" + path);
        } else {
            return TemplateSource.get(parentPath + "/" + path);
        }
    }

    @Override
    public InputStream getStream() {
        if (bytes == null) {
            bytes = FileUtils.toBytes(url);
        }
        return new ByteArrayInputStream(bytes);
    }

}
