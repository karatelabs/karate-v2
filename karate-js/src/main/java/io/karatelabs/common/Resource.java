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

import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;

public interface Resource {

    String CLASSPATH_COLON = "classpath:";
    String FILE_COLON = "file:";
    String THIS_COLON = "this:";

    /**
     * Returns true if this resource is backed by the file system.
     * PathResource returns true, MemoryResource returns false.
     */
    boolean isFile();

    /**
     * Returns true if this resource is in-memory only (not backed by file system).
     * Inverse of isFile() - useful for error messages and debugging.
     */
    default boolean isInMemory() {
        return !isFile();
    }

    /**
     * Returns true if this is a regular file on the local filesystem.
     * This excludes JAR resources and in-memory resources.
     * Use this when you need to know if the resource can be modified on disk.
     *
     * @return true if this is a local file (file:// scheme)
     */
    default boolean isLocalFile() {
        if (!isFile()) {
            return false;
        }
        try {
            URI uri = getUri();
            return uri != null && "file".equals(uri.getScheme());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Returns true if this resource is inside a JAR or ZIP file.
     * JAR resources are read-only and cannot be modified.
     *
     * @return true if this is a JAR resource (jar: scheme)
     */
    default boolean isJarResource() {
        if (!isFile()) {
            return false;
        }
        try {
            URI uri = getUri();
            return uri != null && "jar".equals(uri.getScheme());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Returns true if this resource was loaded from the classpath.
     */
    boolean isClassPath();

    URI getUri();

    /**
     * Returns the Path representation of this resource if it's file-based.
     * Returns null for in-memory resources.
     *
     * @return Path object or null
     */
    Path getPath();

    /**
     * Returns the root path used for relative path computation.
     * For PathResource, this is the configured root (or working directory).
     * For MemoryResource, this is where the resource would be materialized (or system temp).
     *
     * @return root Path, never null (falls back to system temp)
     */
    Path getRoot();

    /**
     * Computes the relative path from a given root to this resource.
     * Only works for file-based resources.
     * Falls back to absolute path if relativization fails (e.g., cross-drive on Windows).
     *
     * @param root the root path to compute relative path from
     * @return relative path string with forward slashes, or absolute path if relativization fails, or null for in-memory resources
     */
    default String getRelativePathFrom(Path root) {
        if (!isFile() || root == null) {
            return null;
        }
        try {
            Path thisPath = getPath();
            if (thisPath == null) {
                return null;
            }

            // Normalize paths
            Path normalizedRoot = root.toAbsolutePath().normalize();
            Path normalizedThis = thisPath.toAbsolutePath().normalize();

            // Check if they share the same root (Windows cross-drive check)
            if (normalizedRoot.getRoot() != null && normalizedThis.getRoot() != null
                    && !normalizedRoot.getRoot().equals(normalizedThis.getRoot())) {
                // Fallback to absolute path for cross-drive
                return normalizedThis.toString().replace('\\', '/');
            }

            String relativePath = normalizedRoot.relativize(normalizedThis).toString();
            return relativePath.replace('\\', '/');
        } catch (Exception e) {
            // Fallback to absolute path if relativization fails
            Path thisPath = getPath();
            return thisPath != null ? thisPath.toAbsolutePath().normalize().toString().replace('\\', '/') : null;
        }
    }

    Resource resolve(String path);

    /**
     * Returns the parent of this resource, or null if no parent exists.
     * For PathResource, preserves root and classpath context.
     * For MemoryResource, returns null.
     *
     * @return parent Resource or null
     */
    default Resource getParent() {
        return null;
    }

    InputStream getStream();

    String getRelativePath();

    String getText();

    String getLine(int index);

    default long getLastModified() {
        if (isFile()) {
            try {
                Path path = getPath();
                if (path != null) {
                    return java.nio.file.Files.getLastModifiedTime(path).toMillis();
                }
            } catch (Exception e) {
                // Fall through
            }
        }
        try {
            URI uri = getUri();
            if (uri != null) {
                return uri.toURL().openConnection().getLastModified();
            }
        } catch (Exception e) {
            // Fall through
        }
        return 0;
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
        URI uri = getUri();
        if (uri == null) {
            return "";
        }
        String path = uri.getPath();
        int pos = path.lastIndexOf('.');
        if (pos == -1 || pos == path.length() - 1) {
            return "";
        }
        return path.substring(pos + 1);
    }

    /**
     * Returns just the file name (last path segment) of this resource.
     * Works for both file-based and URL-based resources.
     *
     * @return the simple file name, or empty string if unavailable
     */
    default String getSimpleName() {
        URI uri = getUri();
        if (uri == null) {
            return "";
        }
        String path = uri.getPath();
        if (path == null || path.isEmpty()) {
            return "";
        }
        // Remove trailing slash if present
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        int pos = path.lastIndexOf('/');
        return pos == -1 ? path : path.substring(pos + 1);
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

    /**
     * Computes the relative path from this resource to another resource.
     * Only works for file-based resources.
     * Falls back to absolute path if relativization fails (e.g., cross-drive on Windows).
     * <p>
     * Path separators are normalized to forward slashes (/) for cross-platform consistency.
     *
     * @param other the target resource
     * @return relative path string with forward slashes, or absolute path if relativization fails, or null for in-memory resources
     */
    default String getRelativePathTo(Resource other) {
        if (!this.isFile() || !other.isFile()) {
            return null; // Only works for file-based resources
        }
        try {
            Path thisPath = this.getPath();
            Path otherPath = other.getPath();

            if (thisPath == null || otherPath == null) {
                return null;
            }

            // Normalize to absolute paths
            thisPath = thisPath.toAbsolutePath().normalize();
            otherPath = otherPath.toAbsolutePath().normalize();

            // Check if they share the same root (critical for Windows cross-drive)
            if (thisPath.getRoot() != null && otherPath.getRoot() != null
                    && !thisPath.getRoot().equals(otherPath.getRoot())) {
                // Fallback to absolute path for cross-drive
                return otherPath.toString().replace('\\', '/');
            }

            String relativePath = thisPath.relativize(otherPath).toString();
            // Normalize to forward slashes for cross-platform consistency
            return relativePath.replace('\\', '/');
        } catch (Exception e) {
            // Fallback to absolute path if relativization fails
            Path otherPath = other.getPath();
            return otherPath != null ? otherPath.toAbsolutePath().normalize().toString().replace('\\', '/') : null;
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

    /**
     * Helper method to convert a URL to a Path, handling JAR URLs specially.
     * Falls back to MemoryResource if JAR file system provider is not available.
     *
     * @param url  the URL to convert
     * @param root optional root path for the resource
     * @return Path object, or null if fallback to MemoryResource is needed
     * @throws Exception if streaming fallback is required (caller should catch and create MemoryResource)
     */
    static Path urlToPath(URL url, Path root) throws Exception {
        URI uri = url.toURI();

        if (!"jar".equals(uri.getScheme())) {
            return Path.of(uri);
        }

        // Special handling for JAR URLs
        try {
            return Path.of(uri);
        } catch (java.nio.file.FileSystemNotFoundException e) {
            // File system doesn't exist yet, create it
            try {
                java.nio.file.FileSystems.newFileSystem(uri, java.util.Collections.emptyMap());
                return Path.of(uri);
            } catch (java.nio.file.FileSystemAlreadyExistsException ex) {
                // Another thread created it concurrently, that's fine
                return Path.of(uri);
            } catch (java.nio.file.ProviderNotFoundException ex) {
                // JAR file system provider not available - signal fallback needed
                throw new java.nio.file.ProviderNotFoundException("JAR provider not available");
            }
        } catch (java.nio.file.ProviderNotFoundException e) {
            // JAR file system provider not available - signal fallback needed
            throw e;
        }
    }

    static Resource path(String path) {
        return path(path, null);
    }

    /**
     * Creates a Resource from a path string, with optional ClassLoader for classpath resources.
     *
     * @param path        the resource path (supports "classpath:" prefix)
     * @param classLoader optional ClassLoader for classpath resources (null = use default)
     * @return Resource instance
     */
    static Resource path(String path, ClassLoader classLoader) {
        if (path == null) {
            path = "";
        }
        if (path.startsWith(CLASSPATH_COLON)) {
            String relativePath = removePrefix(path);
            // Remove leading slash for classloader compatibility
            if (relativePath.startsWith("/")) {
                relativePath = relativePath.substring(1);
            }

            // Try provided classloader, then context classloader, then system classloader
            URL url = null;
            if (classLoader != null) {
                url = classLoader.getResource(relativePath);
            }
            if (url == null) {
                ClassLoader contextCL = Thread.currentThread().getContextClassLoader();
                if (contextCL != null) {
                    url = contextCL.getResource(relativePath);
                }
            }
            if (url == null) {
                url = ClassLoader.getSystemResource(relativePath);
            }
            if (url == null) {
                throw new RuntimeException("cannot find classpath resource: " + path);
            }
            // Convert URL to Path for classpath resources
            try {
                Path resourcePath = urlToPath(url, null);
                return new PathResource(resourcePath, FileUtils.WORKING_DIR.toPath(), true);
            } catch (java.nio.file.ProviderNotFoundException e) {
                // JAR file system provider not available (common in jpackage/JavaFX apps)
                // Fall back to streaming the resource content (root defaults to SYSTEM_TEMP)
                try (java.io.InputStream is = url.openStream()) {
                    String content = FileUtils.toString(is);
                    return new MemoryResource(content);
                } catch (Exception ex) {
                    throw new RuntimeException("Failed to create resource from classpath: " + path, ex);
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to create resource from classpath: " + path, e);
            }
        } else {
            return new PathResource(Path.of(path));
        }
    }

    static Resource text(String text) {
        return new MemoryResource(text);
    }

    /**
     * Creates an in-memory Resource with a custom root.
     * Useful for planning where the resource would be materialized to disk.
     *
     * @param text the text content
     * @param root the root path (where this would live if saved)
     * @return MemoryResource instance
     */
    static Resource text(String text, Path root) {
        return new MemoryResource(text, root);
    }

    /**
     * Creates a modern NIO Path-based Resource.
     * Recommended for new code - better performance and FileSystem support.
     *
     * @param path the path
     * @return PathResource instance
     */
    static Resource from(Path path) {
        return new PathResource(path);
    }

    /**
     * Creates a modern NIO Path-based Resource with custom root.
     *
     * @param path the path
     * @param root the root path for relative path computation
     * @return PathResource instance
     */
    static Resource from(Path path, Path root) {
        return new PathResource(path, root);
    }

    /**
     * Creates a Resource from a URL (supports file:// and jar:// schemes).
     *
     * @param url the URL to convert
     * @return PathResource instance
     */
    static Resource from(URL url) {
        return from(url, null);
    }

    /**
     * Creates a Resource from a URL with custom root.
     *
     * @param url  the URL to convert
     * @param root the root path for relative path computation
     * @return PathResource instance
     */
    static Resource from(URL url, Path root) {
        try {
            Path path = urlToPath(url, root);
            return root != null ? new PathResource(path, root) : new PathResource(path);
        } catch (java.nio.file.ProviderNotFoundException e) {
            // JAR file system provider not available (common in jpackage/JavaFX apps)
            // Fall back to streaming the resource content
            try (java.io.InputStream is = url.openStream()) {
                String content = FileUtils.toString(is);
                return root != null ? new MemoryResource(content, root) : new MemoryResource(content);
            } catch (Exception ex) {
                throw new RuntimeException("Failed to create resource from URL: " + url, ex);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to create resource from URL: " + url, e);
        }
    }

}
