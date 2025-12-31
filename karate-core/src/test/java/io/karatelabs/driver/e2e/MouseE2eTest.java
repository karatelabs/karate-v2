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

import io.karatelabs.driver.cdp.*;

import io.karatelabs.driver.Mouse;
import io.karatelabs.driver.e2e.support.DriverTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E tests for mouse operations.
 */
class MouseE2eTest extends DriverTestBase {

    @BeforeEach
    void setup() {
        driver.setUrl(testUrl("/input"));
    }

    @Test
    void testMouseAtOrigin() {
        Mouse mouse = driver.mouse();
        assertEquals(0.0, mouse.getX());
        assertEquals(0.0, mouse.getY());
    }

    @Test
    void testMouseAtCoordinates() {
        Mouse mouse = driver.mouse(100, 200);
        assertEquals(100.0, mouse.getX());
        assertEquals(200.0, mouse.getY());
    }

    @Test
    void testMouseAtElement() {
        Mouse mouse = driver.mouse("#submit-btn");
        // Should be positioned at the center of the button
        Map<String, Object> pos = driver.position("#submit-btn", true);
        double expectedX = ((Number) pos.get("x")).doubleValue() + ((Number) pos.get("width")).doubleValue() / 2;
        double expectedY = ((Number) pos.get("y")).doubleValue() + ((Number) pos.get("height")).doubleValue() / 2;

        assertEquals(expectedX, mouse.getX(), 1.0);
        assertEquals(expectedY, mouse.getY(), 1.0);
    }

    @Test
    void testMouseMove() {
        Mouse mouse = driver.mouse();
        mouse.move(150, 250);
        assertEquals(150.0, mouse.getX());
        assertEquals(250.0, mouse.getY());
    }

    @Test
    void testMouseOffset() {
        Mouse mouse = driver.mouse(100, 100);
        mouse.offset(50, 25);
        assertEquals(150.0, mouse.getX());
        assertEquals(125.0, mouse.getY());
    }

    @Test
    void testMouseClick() {
        // Verify mouse click method works without throwing
        Mouse mouse = driver.mouse("#submit-btn");
        mouse.click();
        // The click is sent to CDP - actual DOM effect depends on browser state
    }

    @Test
    void testMouseDoubleClick() {
        Mouse mouse = driver.mouse("#username");
        mouse.doubleClick();
        // Double-click sent successfully
    }

    @Test
    void testMouseRightClick() {
        Mouse mouse = driver.mouse("#username");
        mouse.rightClick();
        // Right-click sent successfully
    }

    @Test
    void testMouseMethodChaining() {
        Mouse mouse = driver.mouse()
                .move(100, 100)
                .offset(50, 50)
                .click();

        assertEquals(150.0, mouse.getX());
        assertEquals(150.0, mouse.getY());
    }

}
