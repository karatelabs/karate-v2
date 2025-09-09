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

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;

class JsDate extends JsObject implements JavaMirror {

    private static final SimpleDateFormat ISO_FORMATTER = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    static {
        ISO_FORMATTER.setTimeZone(UTC);
    }

    private final Date value;

    JsDate(Date date) {
        this.value = date;
    }

    JsDate() {
        this(new Date());
    }

    JsDate(long timestamp) {
        this.value = new Date(timestamp);
    }

    JsDate(int year, int month, int date) {
        Calendar cal = Calendar.getInstance();
        cal.set(year, month, date, 0, 0, 0);
        cal.set(Calendar.MILLISECOND, 0);
        this.value = cal.getTime();
    }

    JsDate(int year, int month, int date, int hours, int minutes, int seconds) {
        Calendar cal = Calendar.getInstance();
        cal.set(year, month, date, hours, minutes, seconds);
        cal.set(Calendar.MILLISECOND, 0);
        this.value = cal.getTime();
    }

    JsDate(int year, int month, int date, int hours, int minutes, int seconds, int ms) {
        Calendar cal = Calendar.getInstance();
        cal.set(year, month, date, hours, minutes, seconds);
        cal.set(Calendar.MILLISECOND, ms);
        this.value = cal.getTime();
    }

    long getTime() {
        return value.getTime();
    }

    private static final String[] FORMATS = {
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd"
    };

    static Date parse(String dateStr) {
        for (String format : FORMATS) {
            try {
                SimpleDateFormat sdf = new SimpleDateFormat(format);
                if (format.endsWith("'Z'")) {
                    sdf.setTimeZone(UTC);
                }
                return sdf.parse(dateStr);
            } catch (ParseException e) {
                // try next format
            }
        }
        // if parsing fails
        return new Date();
    }

    JsDate(String text) {
        this.value = parse(text);
    }

    @Override
    public String toString() {
        return value.toString();
    }

    @Override
    public Object toJava() {
        return value;
    }

    Date cast(Context context) {
        if (context.thisObject instanceof JsDate date) {
            return date.value;
        }
        return value;
    }

    @Override
    Prototype initPrototype() {
        Prototype wrapped = super.initPrototype();
        return new Prototype(wrapped) {
            @Override
            public Object getProperty(String propName) {
                return switch (propName) {
                    case "now" -> (Invokable) args -> System.currentTimeMillis();
                    case "parse" -> (Invokable) args -> {
                        if (args.length == 0 || args[0] == null) {
                            return Double.NaN;
                        }
                        try {
                            String dateStr = args[0].toString();
                            return new JsDate(dateStr).getTime();
                        } catch (Exception e) {
                            return Double.NaN;
                        }
                    };
                    case "getTime", "valueOf" -> (JsCallable) (context, args) -> cast(context).getTime();
                    case "toString" -> (JsCallable) (context, args) -> cast(context).toString();
                    case "toISOString" -> (JsCallable) (context, args) -> {
                        synchronized (ISO_FORMATTER) {
                            return ISO_FORMATTER.format(cast(context));
                        }
                    };
                    case "toUTCString" -> (JsCallable) (context, args) -> {
                        SimpleDateFormat formatter = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'");
                        formatter.setTimeZone(UTC);
                        return formatter.format(cast(context));
                    };
                    case "getFullYear" -> (JsCallable) (context, args) -> {
                        Calendar cal = Calendar.getInstance();
                        cal.setTime(cast(context));
                        return cal.get(Calendar.YEAR);
                    };
                    case "getMonth" -> (JsCallable) (context, args) -> {
                        Calendar cal = Calendar.getInstance();
                        cal.setTime(cast(context));
                        return cal.get(Calendar.MONTH); // already 0-indexed in Java!
                    };
                    case "getDate" -> (JsCallable) (context, args) -> {
                        Calendar cal = Calendar.getInstance();
                        cal.setTime(cast(context));
                        return cal.get(Calendar.DAY_OF_MONTH);
                    };
                    case "getDay" -> (JsCallable) (context, args) -> {
                        Calendar cal = Calendar.getInstance();
                        cal.setTime(cast(context));
                        // convert from Java's 1=Sunday to JS's 0=Sunday
                        return cal.get(Calendar.DAY_OF_WEEK) - 1;
                    };
                    case "getHours" -> (JsCallable) (context, args) -> {
                        Calendar cal = Calendar.getInstance();
                        cal.setTime(cast(context));
                        return cal.get(Calendar.HOUR_OF_DAY);
                    };
                    case "getMinutes" -> (JsCallable) (context, args) -> {
                        Calendar cal = Calendar.getInstance();
                        cal.setTime(cast(context));
                        return cal.get(Calendar.MINUTE);
                    };
                    case "getSeconds" -> (JsCallable) (context, args) -> {
                        Calendar cal = Calendar.getInstance();
                        cal.setTime(cast(context));
                        return cal.get(Calendar.SECOND);
                    };
                    case "getMilliseconds" -> (JsCallable) (context, args) -> {
                        Calendar cal = Calendar.getInstance();
                        cal.setTime(cast(context));
                        return cal.get(Calendar.MILLISECOND);
                    };
                    case "setDate" -> (JsCallable) (context, args) -> {
                        if (args.length == 0 || !(args[0] instanceof Number)) {
                            return Double.NaN;
                        }
                        int day = ((Number) args[0]).intValue();
                        if (context.thisObject instanceof JsDate jsDate) {
                            Calendar cal = Calendar.getInstance();
                            cal.setTime(jsDate.value);
                            // handles overflow automatically!
                            cal.set(Calendar.DAY_OF_MONTH, day);
                            jsDate.value.setTime(cal.getTimeInMillis());
                            return jsDate.value.getTime();
                        }
                        return Double.NaN;
                    };
                    case "setMonth" -> (JsCallable) (context, args) -> {
                        if (args.length == 0 || !(args[0] instanceof Number)) {
                            return Double.NaN;
                        }
                        int month = ((Number) args[0]).intValue();
                        if (context.thisObject instanceof JsDate jsDate) {
                            Calendar cal = Calendar.getInstance();
                            cal.setTime(jsDate.value);
                            // handles overflow - month 12 becomes jan of next year
                            cal.set(Calendar.MONTH, month);
                            if (args.length > 1 && args[1] instanceof Number) {
                                cal.set(Calendar.DAY_OF_MONTH, ((Number) args[1]).intValue());
                            }
                            jsDate.value.setTime(cal.getTimeInMillis()); // Mutate!
                            return jsDate.value.getTime();
                        }
                        return Double.NaN;
                    };
                    case "setFullYear" -> (JsCallable) (context, args) -> {
                        if (args.length == 0 || !(args[0] instanceof Number)) {
                            return Double.NaN;
                        }
                        int year = ((Number) args[0]).intValue();
                        if (context.thisObject instanceof JsDate jsDate) {
                            Calendar cal = Calendar.getInstance();
                            cal.setTime(jsDate.value);
                            cal.set(Calendar.YEAR, year);
                            if (args.length > 1 && args[1] instanceof Number) {
                                cal.set(Calendar.MONTH, ((Number) args[1]).intValue());
                            }
                            if (args.length > 2 && args[2] instanceof Number) {
                                cal.set(Calendar.DAY_OF_MONTH, ((Number) args[2]).intValue());
                            }
                            jsDate.value.setTime(cal.getTimeInMillis()); // Mutate!
                            return jsDate.value.getTime();
                        }
                        return Double.NaN;
                    };
                    case "setHours" -> (JsCallable) (context, args) -> {
                        if (args.length == 0 || !(args[0] instanceof Number)) {
                            return Double.NaN;
                        }
                        int hours = ((Number) args[0]).intValue();
                        if (context.thisObject instanceof JsDate jsDate) {
                            Calendar cal = Calendar.getInstance();
                            cal.setTime(jsDate.value);
                            cal.set(Calendar.HOUR_OF_DAY, hours);
                            if (args.length > 1 && args[1] instanceof Number) {
                                cal.set(Calendar.MINUTE, ((Number) args[1]).intValue());
                            }
                            if (args.length > 2 && args[2] instanceof Number) {
                                cal.set(Calendar.SECOND, ((Number) args[2]).intValue());
                            }
                            if (args.length > 3 && args[3] instanceof Number) {
                                cal.set(Calendar.MILLISECOND, ((Number) args[3]).intValue());
                            }
                            jsDate.value.setTime(cal.getTimeInMillis()); // Mutate!
                            return jsDate.value.getTime();
                        }
                        return Double.NaN;
                    };
                    case "setMinutes" -> (JsCallable) (context, args) -> {
                        if (args.length == 0 || !(args[0] instanceof Number)) {
                            return Double.NaN;
                        }
                        int minutes = ((Number) args[0]).intValue();
                        if (context.thisObject instanceof JsDate jsDate) {
                            Calendar cal = Calendar.getInstance();
                            cal.setTime(jsDate.value);
                            cal.set(Calendar.MINUTE, minutes);
                            if (args.length > 1 && args[1] instanceof Number) {
                                cal.set(Calendar.SECOND, ((Number) args[1]).intValue());
                            }
                            if (args.length > 2 && args[2] instanceof Number) {
                                cal.set(Calendar.MILLISECOND, ((Number) args[2]).intValue());
                            }

                            jsDate.value.setTime(cal.getTimeInMillis()); // Mutate!
                            return jsDate.value.getTime();
                        }
                        return Double.NaN;
                    };
                    case "setSeconds" -> (JsCallable) (context, args) -> {
                        if (args.length == 0 || !(args[0] instanceof Number)) {
                            return Double.NaN;
                        }
                        int seconds = ((Number) args[0]).intValue();

                        if (context.thisObject instanceof JsDate jsDate) {
                            Calendar cal = Calendar.getInstance();
                            cal.setTime(jsDate.value);
                            cal.set(Calendar.SECOND, seconds);
                            if (args.length > 1 && args[1] instanceof Number) {
                                cal.set(Calendar.MILLISECOND, ((Number) args[1]).intValue());
                            }
                            jsDate.value.setTime(cal.getTimeInMillis()); // Mutate!
                            return jsDate.value.getTime();
                        }
                        return Double.NaN;
                    };
                    case "setMilliseconds" -> (JsCallable) (context, args) -> {
                        if (args.length == 0 || !(args[0] instanceof Number)) {
                            return Double.NaN;
                        }
                        int ms = ((Number) args[0]).intValue();
                        if (context.thisObject instanceof JsDate jsDate) {
                            Calendar cal = Calendar.getInstance();
                            cal.setTime(jsDate.value);
                            cal.set(Calendar.MILLISECOND, ms);

                            jsDate.value.setTime(cal.getTimeInMillis()); // Mutate!
                            return jsDate.value.getTime();
                        }
                        return Double.NaN;
                    };
                    case "setTime" -> (JsCallable) (context, args) -> {
                        if (args.length == 0 || !(args[0] instanceof Number)) {
                            return Double.NaN;
                        }
                        long timestamp = ((Number) args[0]).longValue();
                        if (context.thisObject instanceof JsDate jsDate) {
                            jsDate.value.setTime(timestamp); // Direct mutation!
                            return timestamp;
                        }
                        return Double.NaN;
                    };
                    default -> null;
                };
            }
        };
    }

    @Override
    public Date call(Context context, Object... args) {
        if (args.length == 0) {
            return new JsDate().value;
        } else if (args.length == 1) {
            Object arg = args[0];
            if (arg instanceof Number n) {
                return new JsDate(n.longValue()).value;
            } else if (arg instanceof String s) {
                return new JsDate(s).value;
            } else if (arg instanceof JsDate date) {
                return new Date(date.value.getTime()); // clone
            }
        } else if (args.length >= 3) {
            int year = args[0] instanceof Number ? ((Number) args[0]).intValue() : 0;
            int month = args[1] instanceof Number ? ((Number) args[1]).intValue() : 0;
            int day = args[2] instanceof Number ? ((Number) args[2]).intValue() : 1;
            if (args.length >= 6) {
                int hours = args[3] instanceof Number ? ((Number) args[3]).intValue() : 0;
                int minutes = args[4] instanceof Number ? ((Number) args[4]).intValue() : 0;
                int seconds = args[5] instanceof Number ? ((Number) args[5]).intValue() : 0;
                if (args.length >= 7) {
                    int ms = args[6] instanceof Number ? ((Number) args[6]).intValue() : 0;
                    return new JsDate(year, month, day, hours, minutes, seconds, ms).value;
                }
                return new JsDate(year, month, day, hours, minutes, seconds).value;
            }
            return new JsDate(year, month, day).value;
        }
        return new JsDate().value;
    }
}
