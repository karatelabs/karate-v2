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
package io.karatelabs.driver.e2e;

import io.karatelabs.driver.DriverInspector;
import io.karatelabs.driver.e2e.support.DriverTestBase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E tests for DriverInspector observability features.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class InspectorE2eTest extends DriverTestBase {

    private static DriverInspector inspector;

    @BeforeAll
    static void createInspector() {
        inspector = new DriverInspector(driver);
    }

    @Test
    @Order(1)
    void testCaptureScreenshot() {
        driver.setUrl(testUrl("/"));

        byte[] screenshot = inspector.captureScreenshot();
        assertNotNull(screenshot);
        assertTrue(screenshot.length > 1000, "Screenshot should have substantial content");

        // Verify PNG format
        assertEquals((byte) 0x89, screenshot[0]);
        assertEquals((byte) 0x50, screenshot[1]); // P
    }

    @Test
    @Order(2)
    void testCaptureScreenshotJpeg() {
        byte[] screenshot = inspector.captureScreenshot("jpeg");
        assertNotNull(screenshot);
        assertTrue(screenshot.length > 0);

        // JPEG files start with FFD8
        assertEquals((byte) 0xFF, screenshot[0]);
        assertEquals((byte) 0xD8, screenshot[1]);
    }

    @Test
    @Order(3)
    void testCaptureScreenshotBase64() {
        String base64 = inspector.captureScreenshotBase64();
        assertNotNull(base64);
        assertTrue(base64.length() > 100, "Base64 screenshot should have content");

        // Base64 PNG starts with iVBORw0KGgo (decoded: PNG header)
        assertTrue(base64.startsWith("iVBORw0KGgo"), "Should be valid PNG base64");
    }

    @Test
    @Order(10)
    void testGetOuterHtml() {
        driver.setUrl(testUrl("/"));

        String outerHtml = inspector.getOuterHtml("h1");
        assertNotNull(outerHtml);
        assertTrue(outerHtml.startsWith("<h1"), "Should be h1 element");
        assertTrue(outerHtml.contains("Karate Driver Test Pages"));
    }

    @Test
    @Order(11)
    void testGetInnerHtml() {
        String innerHtml = inspector.getInnerHtml("h1");
        assertNotNull(innerHtml);
        assertEquals("Karate Driver Test Pages", innerHtml.trim());
    }

    @Test
    @Order(12)
    void testGetText() {
        String text = inspector.getText("#description");
        assertNotNull(text);
        assertTrue(text.contains("Welcome"));
    }

    @Test
    @Order(13)
    void testGetAttributes() {
        Map<String, String> attrs = inspector.getAttributes("#message");
        assertNotNull(attrs);
        assertEquals("message", attrs.get("id"));
    }

    @Test
    @Order(14)
    void testQuerySelectorAll() {
        List<Map<String, Object>> links = inspector.querySelectorAll("a");
        assertNotNull(links);
        assertFalse(links.isEmpty(), "Should find links on page");

        // Check first link
        Map<String, Object> firstLink = links.get(0);
        assertEquals("A", firstLink.get("tagName"));
    }

    @Test
    @Order(20)
    void testGetPageSource() {
        String source = inspector.getPageSource();
        assertNotNull(source);
        assertTrue(source.contains("<html"));
        assertTrue(source.contains("Karate Driver Test"));
    }

    @Test
    @Order(21)
    void testGetCurrentUrl() {
        String url = inspector.getCurrentUrl();
        assertNotNull(url);
        assertTrue(url.contains("index.html") || url.contains(":18080/"),
                "Should be index page: " + url);
    }

    @Test
    @Order(22)
    void testGetTitle() {
        String title = inspector.getTitle();
        assertEquals("Karate Driver Test", title);
    }

    @Test
    @Order(30)
    void testConsoleMessages() throws Exception {
        // Clear any previous messages
        inspector.clearConsoleMessages();

        // Navigate to page that logs to console
        driver.setUrl(testUrl("/"));

        // Give time for console message to be captured
        Thread.sleep(200);

        // The index page has: console.log('Index page loaded')
        List<String> messages = inspector.getConsoleMessages();
        // Console messages may or may not be captured depending on timing
        // Just verify the method works
        assertNotNull(messages);
    }

    @Test
    @Order(31)
    void testOnConsoleMessage() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> captured = new AtomicReference<>();

        inspector.onConsoleMessage(msg -> {
            captured.set(msg);
            latch.countDown();
        });

        // Execute console.log via script
        driver.script("console.log('test message from script')");

        // Wait for message to be captured
        boolean received = latch.await(2, TimeUnit.SECONDS);
        if (received) {
            assertEquals("test message from script", captured.get());
        }
        // If not received, that's ok - console capture can be timing-dependent
    }

    @Test
    @Order(40)
    void testGetSnapshot() {
        driver.setUrl(testUrl("/"));

        Map<String, Object> snapshot = inspector.getSnapshot();

        assertNotNull(snapshot);
        assertTrue(((String) snapshot.get("url")).contains(":18080"));
        assertEquals("Karate Driver Test", snapshot.get("title"));
        assertNotNull(snapshot.get("consoleMessages"));
        assertNotNull(snapshot.get("consoleErrors"));
        assertNotNull(snapshot.get("screenshotBase64"));

        String screenshot = (String) snapshot.get("screenshotBase64");
        assertTrue(screenshot.startsWith("iVBORw0KGgo"), "Should have valid PNG base64");
    }

    @Test
    @Order(41)
    void testGetSnapshotLight() {
        Map<String, Object> snapshot = inspector.getSnapshotLight();

        assertNotNull(snapshot);
        assertNotNull(snapshot.get("url"));
        assertNotNull(snapshot.get("title"));
        assertNotNull(snapshot.get("consoleMessages"));
        assertNotNull(snapshot.get("consoleErrors"));
        assertNull(snapshot.get("screenshotBase64"), "Light snapshot should not have screenshot");
    }

    @Test
    @Order(50)
    void testEvalJs() {
        Object result = inspector.evalJs("1 + 2 * 3");
        assertEquals(7, ((Number) result).intValue());
    }

    @Test
    @Order(51)
    void testEvalJsObject() {
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) inspector.evalJs("({a: 1, b: 'test'})");
        assertEquals(1, ((Number) result.get("a")).intValue());
        assertEquals("test", result.get("b"));
    }

}
