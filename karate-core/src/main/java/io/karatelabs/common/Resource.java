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

import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;

public interface Resource {

    String CLASSPATH_COLON = "classpath:";
    String FILE_COLON = "file:";
    String THIS_COLON = "this:";

    boolean isFile();

    boolean isClassPath();

    File getFile();

    URI getUri();

    Resource resolve(String path);

    InputStream getStream();

    String getRelativePath();

    String getText();

    String getLine(int index);

    default long getLastModified() {
        if (isFile()) {
            return getFile().lastModified();
        }
        try {
            return getUri().toURL().openConnection().getLastModified();
        } catch (Exception e) {
            return 0;
        }
    }

    default String getPackageQualifiedName() {
        String path = getRelativePath();
        if (path.endsWith(".feature")) {
            path = path.substring(0, path.length() - 8);
        }
        if (path.charAt(0) == '/') {
            path = path.substring(1);
        }
        return path.replace('/', '.').replaceAll("\\.[.]+", ".");
    }

    default String getExtension() {
        String path = getUri().getPath();
        int pos = path.lastIndexOf('.');
        if (pos == -1 || pos == path.length() - 1) {
            return "";
        }
        return path.substring(pos + 1);
    }

    default String getFileNameWithoutExtension() {
        String path = getRelativePath();
        int pos = path.lastIndexOf('.');
        if (pos == -1) {
            return path;
        } else {
            return path.substring(0, pos);
        }
    }

    default String getPrefixedPath() {
        return isClassPath() ? CLASSPATH_COLON + getRelativePath() : getRelativePath();
    }

    static String removePrefix(String text) {
        if (text.startsWith(CLASSPATH_COLON) || text.startsWith(FILE_COLON)) {
            return text.substring(text.indexOf(':') + 1);
        } else {
            return text;
        }
    }

    static String getParentPath(String relativePath) {
        int pos = relativePath.lastIndexOf('/');
        return pos == -1 ? "" : relativePath.substring(0, pos + 1);
    }

    static Resource path(String path) {
        if (path == null) {
            path = "";
        }
        if (path.startsWith(CLASSPATH_COLON)) {
            String relativePath = removePrefix(path);
            URL url = Thread.currentThread().getContextClassLoader().getResource(relativePath);
            if (url == null) {
                throw new RuntimeException("cannot find resource: " + path);
            }
            return new UrlResource(url, true);
        } else {
            File file = new File(path);
            return new UrlResource(file, false);
        }
    }

    static Resource file(File file) {
        return new UrlResource(file, false);
    }

    static Resource text(String text) {
        return new MemoryResource(text);
    }

}
