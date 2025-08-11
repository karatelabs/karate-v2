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

public class UrlTemplateSourceResolver implements TemplateSourceResolver {

    public final boolean classpath;
    public final String root;

    private static final String EMPTY = "";
    private static final String SLASH = "/";

    public UrlTemplateSourceResolver(String root) {
        if (root == null) {
            root = EMPTY;
        }
        classpath = root.startsWith(TemplateSource.CLASSPATH_COLON);
        root = TemplateSource.removePrefix(root);
        if (!root.isEmpty() && !root.endsWith(SLASH)) {
            root = root + SLASH;
        }
        this.root = root;
    }

    @Override
    public TemplateSource resolve(String path, String caller) {
        if (path.startsWith(TemplateSource.CLASSPATH_COLON)) {
            return get(path);
        }
        String prefix = classpath ? TemplateSource.CLASSPATH_COLON + root : root;
        if (path.startsWith(TemplateSource.THIS_COLON) && caller != null) {
            return get(prefix + TemplateSource.getParentPath(caller) + path.substring(5));
        }
        return get(prefix + (path.charAt(0) == '/' ? path.substring(1) : path));
    }

    private TemplateSource get(String path) {
        return TemplateSource.get(path);
    }

    @Override
    public String toString() {
        return classpath ? TemplateSource.CLASSPATH_COLON + root : root;
    }

}
