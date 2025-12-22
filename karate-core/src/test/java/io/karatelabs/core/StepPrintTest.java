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
package io.karatelabs.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static io.karatelabs.core.TestUtils.*;
import static org.junit.jupiter.api.Assertions.*;

class StepPrintTest {

    @Test
    void testPrintString() {
        ScenarioRuntime sr = run("""
            * print 'hello world'
            """);
        assertPassed(sr);
        // Check that print output was captured in step logs
        List<StepResult> steps = sr.getResult().getStepResults();
        assertFalse(steps.isEmpty());
        String log = steps.getFirst().getLog();
        assertNotNull(log);
        assertTrue(log.contains("hello world"));
    }

    @Test
    void testPrintVariable() {
        ScenarioRuntime sr = run("""
            * def name = 'test'
            * print name
            """);
        assertPassed(sr);
        List<StepResult> steps = sr.getResult().getStepResults();
        // The print step is the second one
        assertTrue(steps.size() >= 2);
        String log = steps.get(1).getLog();
        assertTrue(log.contains("test"));
    }

    @Test
    void testPrintJson() {
        ScenarioRuntime sr = run("""
            * def data = { name: 'john', age: 30 }
            * print data
            """);
        assertPassed(sr);
        List<StepResult> steps = sr.getResult().getStepResults();
        assertTrue(steps.size() >= 2);
        String log = steps.get(1).getLog();
        // JSON should be printed
        assertTrue(log.contains("john") || log.contains("name"));
    }

    @Test
    void testPrintExpression() {
        ScenarioRuntime sr = run("""
            * def x = 10
            * print 'x squared is:', x * x
            """);
        assertPassed(sr);
    }

}
