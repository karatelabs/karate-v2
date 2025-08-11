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

import java.io.File;
import java.io.InputStream;
import java.net.URL;

public interface TemplateSource {

    String CLASSPATH_COLON = "classpath:";
    String FILE_COLON = "file:";
    String THIS_COLON = "this:"; // used only in html templating

    boolean isFile();

    File getFile();

    URL getUrl();

    String getPath();

    TemplateSource resolve(String path);

    InputStream getStream();

    default long getLastModified() {
        if (isFile()) {
            return getFile().lastModified();
        }
        try {
            return getUrl().openConnection().getLastModified();
        } catch (Exception e) {
            return 0;
        }
    }

    static TemplateSource get(String path) {
        if (path.startsWith(CLASSPATH_COLON)) {
            String relativePath = removePrefix(path);
            URL url = Thread.currentThread().getContextClassLoader().getResource(relativePath);
            if (url == null) {
                throw new RuntimeException("cannot find resource: " + path);
            }
            return new UrlTemplateSource(url, true);
        } else {
            File file = new File(path);
            return new UrlTemplateSource(file, false);
        }
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

}
