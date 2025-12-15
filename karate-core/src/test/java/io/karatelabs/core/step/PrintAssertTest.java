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
package io.karatelabs.core.step;

import io.karatelabs.core.ScenarioRuntime;
import io.karatelabs.core.StepResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.karatelabs.core.TestUtils.*;
import static org.junit.jupiter.api.Assertions.*;

class PrintAssertTest {

    // ========== Print ==========

    @Test
    void testPrintString() {
        ScenarioRuntime sr = run("print 'hello world'");
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
        ScenarioRuntime sr = run(
                "def name = 'test'",
                "print name"
        );
        assertPassed(sr);
        List<StepResult> steps = sr.getResult().getStepResults();
        // The print step is the second one
        assertTrue(steps.size() >= 2);
        String log = steps.get(1).getLog();
        assertTrue(log.contains("test"));
    }

    @Test
    void testPrintJson() {
        ScenarioRuntime sr = run(
                "def data = { name: 'john', age: 30 }",
                "print data"
        );
        assertPassed(sr);
        List<StepResult> steps = sr.getResult().getStepResults();
        assertTrue(steps.size() >= 2);
        String log = steps.get(1).getLog();
        // JSON should be printed
        assertTrue(log.contains("john") || log.contains("name"));
    }

    @Test
    void testPrintExpression() {
        ScenarioRuntime sr = run(
                "def x = 10",
                "print 'x squared is:', x * x"
        );
        assertPassed(sr);
    }

    // ========== Assert ==========

    @Test
    void testAssertTrue() {
        ScenarioRuntime sr = run("assert 1 + 1 == 2");
        assertPassed(sr);
    }

    @Test
    void testAssertFalse() {
        ScenarioRuntime sr = run("assert 1 + 1 == 3");
        assertFailed(sr);
    }

    @Test
    void testAssertWithVariable() {
        ScenarioRuntime sr = run(
                "def x = 10",
                "assert x > 5"
        );
        assertPassed(sr);
    }

    @Test
    void testAssertWithVariableFailure() {
        ScenarioRuntime sr = run(
                "def x = 3",
                "assert x > 5"
        );
        assertFailed(sr);
    }

    @Test
    void testAssertComplex() {
        ScenarioRuntime sr = run(
                "def arr = [1, 2, 3]",
                "assert arr.length == 3"
        );
        assertPassed(sr);
    }

    @Test
    void testAssertString() {
        ScenarioRuntime sr = run(
                "def s = 'hello'",
                "assert s.startsWith('hel')"
        );
        assertPassed(sr);
    }

    // ========== Eval ==========

    @Test
    void testEval() {
        ScenarioRuntime sr = run(
                "def counter = 0",
                "eval counter = counter + 1",
                "match counter == 1"
        );
        assertPassed(sr);
    }

    @Test
    void testEvalFunction() {
        ScenarioRuntime sr = run(
                "def result = null",
                "def fn = function(x) { return x * 2 }",
                "eval result = fn(5)",
                "match result == 10"
        );
        assertPassed(sr);
    }

    // ========== Configure ==========

    @Test
    void testConfigure() {
        ScenarioRuntime sr = run(
                "configure ssl = true",
                "def x = 1"
        );
        assertPassed(sr);
        // Just verify it doesn't fail - configure is a no-op for most settings in tests
    }

}
