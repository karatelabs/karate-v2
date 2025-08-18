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
package io.karatelabs.js;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;

class JsNumber extends JsObject implements JavaMirror {

    final Number value;

    JsNumber() {
        this(0);
    }

    JsNumber(Number value) {
        this.value = value;
    }

    @Override
    public String toString() {
        return value.toString();
    }

    @Override
    public Object toJava() {
        return value;
    }

    @Override
    Prototype initPrototype() {
        Prototype wrapped = super.initPrototype();
        return new Prototype(wrapped) {
            @Override
            public Object getProperty(String propName) {
                switch (propName) {
                    case "toFixed":
                        return (Invokable) args -> {
                            int digits = 0;
                            if (args.length > 0) {
                                digits = Terms.toNumber(args[0]).intValue();
                            }
                            double doubleValue = value.doubleValue();
                            BigDecimal bd = BigDecimal.valueOf(doubleValue);
                            bd = bd.setScale(digits, RoundingMode.HALF_UP);
                            StringBuilder pattern = new StringBuilder("0");
                            if (digits > 0) {
                                pattern.append(".");
                                pattern.append("0".repeat(digits));
                            }
                            DecimalFormat df = new DecimalFormat(pattern.toString());
                            return df.format(bd.doubleValue());
                        };
                    // static ==========================================================================================
                    case "valueOf":
                        return (Invokable) args -> value;
                }
                return null;
            }
        };
    }

    @Override
    public Object invoke(Object... args) {
        Number temp = 0;
        if (args.length > 0) {
            temp = Terms.toNumber(args[0]);
        }
        return new JsNumber(temp);
    }

}
