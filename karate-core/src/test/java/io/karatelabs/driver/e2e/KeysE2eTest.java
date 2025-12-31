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

import io.karatelabs.driver.Keys;
import io.karatelabs.driver.e2e.support.DriverTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E tests for keyboard operations.
 */
class KeysE2eTest extends DriverTestBase {

    @BeforeEach
    void setup() {
        driver.setUrl(testUrl("/input"));
    }

    @Test
    void testTypeText() {
        driver.focus("#username");
        driver.keys().type("hello");

        String value = driver.value("#username");
        assertEquals("hello", value);
    }

    @Test
    void testTypeWithNumbers() {
        driver.focus("#username");
        driver.keys().type("user123");

        String value = driver.value("#username");
        assertEquals("user123", value);
    }

    @Test
    void testTypeWithSpecialChars() {
        driver.focus("#email");
        driver.keys().type("test@example.com");

        String value = driver.value("#email");
        assertEquals("test@example.com", value);
    }

    @Test
    void testTabKey() {
        driver.focus("#username");
        driver.keys().type("user1");

        // Tab to next field
        driver.keys().press(Keys.TAB);

        // Type in email field (should now be focused)
        driver.keys().type("test@test.com");

        // Verify email field has the value
        String emailValue = driver.value("#email");
        assertEquals("test@test.com", emailValue);
    }

    @Test
    void testEnterKey() {
        driver.focus("#username");

        // Press Enter key (verifies key dispatch works without throwing)
        driver.keys().press(Keys.ENTER);

        // Key was sent successfully
    }

    @Test
    void testBackspace() {
        driver.focus("#username");
        driver.keys().type("hello world");

        // Delete last 5 characters
        for (int i = 0; i < 5; i++) {
            driver.keys().press(Keys.BACKSPACE);
        }

        String value = driver.value("#username");
        assertEquals("hello ", value);
    }

    @Test
    void testArrowKeys() {
        driver.focus("#username");
        driver.keys().type("abc");

        // Move cursor left and insert character
        driver.keys().press(Keys.LEFT);
        driver.keys().press(Keys.LEFT);
        driver.keys().type("X");

        String value = driver.value("#username");
        assertEquals("aXbc", value);
    }

    @Test
    void testCtrlA_SelectAll() {
        driver.input("#username", "select me");
        driver.focus("#username");

        // Select all with Ctrl+A
        driver.keys().ctrl("a");

        // Type to replace selection
        driver.keys().type("replaced");

        String value = driver.value("#username");
        assertEquals("replaced", value);
    }

}
