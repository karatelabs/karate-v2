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

import io.karatelabs.driver.e2e.support.DriverTestBase;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E tests for browser navigation.
 */
class NavigationE2ETest extends DriverTestBase {

    @Test
    void testScriptExecutionBasic() {
        // Test basic script execution (works without navigation)
        Object result = driver.script("1 + 1");
        assertEquals(2, ((Number) result).intValue());

        result = driver.script("'hello' + ' world'");
        assertEquals("hello world", result);
    }

    @Test
    void testScriptReturnsObject() {
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) driver.script("({name: 'test', value: 42})");
        assertEquals("test", result.get("name"));
        assertEquals(42, ((Number) result.get("value")).intValue());
    }

    @Test
    void testScreenshot() {
        // Take screenshot of blank page
        byte[] screenshot = driver.screenshot();

        assertNotNull(screenshot);
        assertTrue(screenshot.length > 0, "Screenshot should have content");

        // PNG files start with specific bytes
        assertEquals((byte) 0x89, screenshot[0]);
        assertEquals((byte) 0x50, screenshot[1]); // P
        assertEquals((byte) 0x4E, screenshot[2]); // N
        assertEquals((byte) 0x47, screenshot[3]); // G
    }

}
