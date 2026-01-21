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
package io.karatelabs.parser;

import io.karatelabs.common.Resource;

import java.util.List;

/**
 * Benchmark for JsLexer performance. Run with:
 * mvn test -Dtest=LexerBenchmark -pl karate-js
 */
public class LexerBenchmark {

    // Representative JavaScript code samples
    private static final String JS_IDENTIFIERS_KEYWORDS = """
        function calculateTotal(items, taxRate, discount) {
            let total = 0;
            const multiplier = 1 + taxRate;
            for (let i = 0; i < items.length; i++) {
                const item = items[i];
                if (item.price > 0 && item.quantity > 0) {
                    total += item.price * item.quantity;
                } else if (item.isFree) {
                    continue;
                } else {
                    throw new Error('Invalid item');
                }
            }
            return total * multiplier - discount;
        }
        var result = calculateTotal(data, 0.08, 5.00);
        """;

    private static final String JS_STRINGS_AND_TEMPLATES = """
        const greeting = "Hello, World!";
        const name = 'John Doe';
        const message = `Welcome, ${name}! Your balance is $${balance.toFixed(2)}.`;
        const multiline = `
            This is a
            multiline template
            with ${nested} expressions
        `;
        const escaped = "She said \\"Hello\\" and he replied 'Hi'";
        const path = '/api/users/${userId}/profile';
        """;

    private static final String JS_NUMBERS_AND_OPERATORS = """
        let a = 123 + 456.789;
        let b = 0xFF + 0x1A2B;
        let c = 1e10 + 2.5e-3;
        let d = a * b / c % 100;
        let e = (a << 2) | (b >> 1) & 0xFF;
        let f = a === b ? c : d;
        let g = a !== b && c >= d || e <= f;
        let h = ++a + b-- * --c + d++;
        let i = a **= 2;
        let j = b ??= c || d;
        let k = obj?.prop?.nested ?? 'default';
        """;

    private static final String JS_OBJECTS_AND_ARRAYS = """
        const user = {
            id: 12345,
            name: "Alice",
            email: "alice@example.com",
            roles: ["admin", "user", "guest"],
            profile: {
                age: 30,
                city: "New York",
                settings: {
                    theme: "dark",
                    notifications: true,
                    preferences: {
                        language: "en",
                        timezone: "UTC"
                    }
                }
            },
            tags: [...existingTags, "new", "featured"],
            ...defaults
        };
        const [first, second, ...rest] = items;
        const { name: userName, profile: { age } } = user;
        """;

    private static final String JS_FUNCTIONS_AND_CLASSES = """
        const add = (a, b) => a + b;
        const multiply = (a, b) => {
            return a * b;
        };
        function processData(data, callback = () => {}) {
            try {
                const result = transform(data);
                callback(null, result);
            } catch (error) {
                callback(error, null);
            } finally {
                cleanup();
            }
        }
        const handler = async (event) => {
            const response = await fetch(url);
            return response.json();
        };
        """;

    private static final String JS_COMMENTS_AND_WHITESPACE = """
        // Single line comment
        /* Block comment */
        /**
         * Multi-line JSDoc comment
         * @param {string} name - The name
         * @returns {string} The greeting
         */
        function greet(name) {
            // Another comment
            return "Hello, " + name; /* inline */ // trailing
        }




        """;

    private static final String JS_REGEX = """
        const emailPattern = /^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$/;
        const urlPattern = /https?:\\/\\/[^\\s]+/gi;
        const datePattern = /\\d{4}-\\d{2}-\\d{2}/;
        if (/test/.test(str)) { console.log('match'); }
        const result = str.replace(/foo/g, 'bar');
        """;

    private static final String JS_MIXED_REALISTIC = """
        function UserService(config) {
            const API_URL = config.apiUrl || 'https://api.example.com';
            const cache = new Map();

            this.getUser = async function(userId) {
                if (cache.has(userId)) {
                    return cache.get(userId);
                }

                const response = await fetch(`${API_URL}/users/${userId}`, {
                    method: 'GET',
                    headers: {
                        'Content-Type': 'application/json',
                        'Authorization': `Bearer ${config.token}`
                    }
                });

                if (!response.ok) {
                    throw new Error(`HTTP ${response.status}: ${response.statusText}`);
                }

                const user = await response.json();
                cache.set(userId, user);
                return user;
            };

            this.updateUser = async function(userId, updates) {
                const user = await this.getUser(userId);
                const merged = { ...user, ...updates, updatedAt: Date.now() };

                const response = await fetch(`${API_URL}/users/${userId}`, {
                    method: 'PUT',
                    headers: {
                        'Content-Type': 'application/json',
                        'Authorization': `Bearer ${config.token}`
                    },
                    body: JSON.stringify(merged)
                });

                if (response.ok) {
                    cache.set(userId, merged);
                    return merged;
                }
                throw new Error('Update failed');
            };
        }

        const service = new UserService({ apiUrl: 'https://api.test.com', token: 'abc123' });
        const user = await service.getUser(42);
        console.log(`User: ${user.name}, Email: ${user.email}`);
        """;

    // Combine all samples for a comprehensive test
    private static final String JS_ALL_COMBINED = JS_IDENTIFIERS_KEYWORDS
            + JS_STRINGS_AND_TEMPLATES
            + JS_NUMBERS_AND_OPERATORS
            + JS_OBJECTS_AND_ARRAYS
            + JS_FUNCTIONS_AND_CLASSES
            + JS_COMMENTS_AND_WHITESPACE
            + JS_REGEX
            + JS_MIXED_REALISTIC;

    // Create a large source by repeating the combined sample
    private static final String JS_LARGE;
    static {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            sb.append(JS_ALL_COMBINED).append("\n");
        }
        JS_LARGE = sb.toString();
    }

    public static void main(String[] args) {
        System.out.println("=== JsLexer Benchmark ===\n");
        System.out.println("Source sizes:");
        System.out.println("  Small (combined): " + JS_ALL_COMBINED.length() + " chars");
        System.out.println("  Large (50x):      " + JS_LARGE.length() + " chars");
        System.out.println();

        // Warmup
        System.out.println("Warming up JIT...");
        for (int i = 0; i < 1000; i++) {
            tokenize(JS_ALL_COMBINED);
            tokenize(JS_LARGE);
        }
        System.out.println("Warmup complete.\n");

        // Run benchmarks
        runBenchmark("Small source (mixed JS)", JS_ALL_COMBINED, 10000);
        runBenchmark("Large source (50x mixed)", JS_LARGE, 500);

        // Individual category benchmarks
        System.out.println("\n--- Category Breakdown ---\n");
        runBenchmark("Identifiers & Keywords", JS_IDENTIFIERS_KEYWORDS, 20000);
        runBenchmark("Strings & Templates", JS_STRINGS_AND_TEMPLATES, 20000);
        runBenchmark("Numbers & Operators", JS_NUMBERS_AND_OPERATORS, 20000);
        runBenchmark("Objects & Arrays", JS_OBJECTS_AND_ARRAYS, 20000);
        runBenchmark("Functions", JS_FUNCTIONS_AND_CLASSES, 20000);
        runBenchmark("Comments & Whitespace", JS_COMMENTS_AND_WHITESPACE, 20000);
        runBenchmark("Regex", JS_REGEX, 20000);
        runBenchmark("Realistic Mixed", JS_MIXED_REALISTIC, 10000);
    }

    private static void runBenchmark(String name, String source, int iterations) {
        // Get token count first
        List<Token> tokens = tokenize(source);
        int tokenCount = tokens.size();

        // Timed runs
        long[] times = new long[5];
        for (int run = 0; run < 5; run++) {
            long start = System.nanoTime();
            for (int i = 0; i < iterations; i++) {
                tokenize(source);
            }
            times[run] = System.nanoTime() - start;
        }

        // Calculate stats (use median of 5 runs)
        java.util.Arrays.sort(times);
        long medianNs = times[2];
        double avgNsPerIter = (double) medianNs / iterations;
        double avgUsPerIter = avgNsPerIter / 1000.0;
        double charsPerUs = source.length() / avgUsPerIter;
        double tokensPerUs = tokenCount / avgUsPerIter;

        System.out.printf("%-28s %6d chars, %4d tokens | %8.2f µs/iter | %6.1f chars/µs | %5.2f tokens/µs%n",
                name, source.length(), tokenCount, avgUsPerIter, charsPerUs, tokensPerUs);
    }

    private static List<Token> tokenize(String source) {
        return JsLexer.getTokens(Resource.text(source));
    }
}
