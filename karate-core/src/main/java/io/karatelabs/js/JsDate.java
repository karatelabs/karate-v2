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

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;

class JsDate extends JsObject implements JavaMirror {

    ZonedDateTime value;
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
    private static final ZoneId UTC = ZoneId.of("UTC");

    JsDate() {
        this(ZonedDateTime.now());
    }

    JsDate(ZonedDateTime dateTime) {
        this.value = dateTime;
    }

    JsDate(long timestamp) {
        this.value = ZonedDateTime.ofInstant(
                Instant.ofEpochMilli(timestamp),
                ZoneId.systemDefault());
    }

    JsDate(int year, int month, int date) {
        // JavaScript months are 0-indexed, Java months are 1-indexed
        this.value = ZonedDateTime.of(year, month + 1, date, 0, 0, 0, 0, ZoneId.systemDefault());
    }

    JsDate(int year, int month, int date, int hours, int minutes, int seconds) {
        this.value = ZonedDateTime.of(year, month + 1, date, hours, minutes, seconds, 0, ZoneId.systemDefault());
    }

    JsDate(int year, int month, int date, int hours, int minutes, int seconds, int ms) {
        this.value = ZonedDateTime.of(year, month + 1, date, hours, minutes, seconds, ms * 1000000, ZoneId.systemDefault());
    }

    JsDate(String dateStr) {
        ZonedDateTime parsedDateTime;
        try {
            // Try parsing as ISO format
            LocalDateTime localDateTime = LocalDateTime.parse(dateStr, DateTimeFormatter.ISO_DATE_TIME);
            parsedDateTime = ZonedDateTime.of(localDateTime, UTC);
        } catch (DateTimeParseException e) {
            try {
                // Try parsing as ISO date only
                LocalDate localDate = LocalDate.parse(dateStr, DateTimeFormatter.ISO_DATE);
                parsedDateTime = localDate.atStartOfDay(UTC);
            } catch (DateTimeParseException e2) {
                try {
                    // Try parsing with offset
                    OffsetDateTime offsetDateTime = OffsetDateTime.parse(dateStr);
                    parsedDateTime = offsetDateTime.toZonedDateTime();
                } catch (DateTimeParseException e3) {
                    // If all parsing fails, use current time
                    parsedDateTime = ZonedDateTime.now();
                }
            }
        }
        this.value = parsedDateTime;
    }

    long getTime() {
        return value.toInstant().toEpochMilli();
    }

    void setValue(ZonedDateTime newDateTime) {
        this.value = newDateTime;
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
                    case "getTime", "valueOf" -> (JsCallable) (context, args) -> {
                        if (context.thisObject instanceof JsDate) {
                            return ((JsDate) context.thisObject).getTime();
                        }
                        return getTime();
                    };
                    case "toString" -> (JsCallable) (context, args) -> {
                        if (context.thisObject instanceof JsDate) {
                            return (context.thisObject).toString();
                        }
                        return toString();
                    };
                    case "toISOString" -> (JsCallable) (context, args) -> {
                        ZonedDateTime dt = context.thisObject instanceof JsDate ? ((JsDate) context.thisObject).value : value;
                        return dt.withZoneSameInstant(UTC).format(ISO_FORMATTER);
                    };
                    case "toUTCString" -> (JsCallable) (context, args) -> {
                        ZonedDateTime dt = context.thisObject instanceof JsDate ? ((JsDate) context.thisObject).value : value;
                        // Format: "Fri, 01 Jan 2021 00:00:00 GMT"
                        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'");
                        return dt.withZoneSameInstant(UTC).format(formatter);
                    };
                    case "getFullYear" -> (JsCallable) (context, args) -> {
                        ZonedDateTime dt = context.thisObject instanceof JsDate ? ((JsDate) context.thisObject).value : value;
                        return dt.getYear();
                    };
                    case "getMonth" -> (JsCallable) (context, args) -> {
                        ZonedDateTime dt = context.thisObject instanceof JsDate ? ((JsDate) context.thisObject).value : value;
                        // JavaScript months are 0-indexed
                        return dt.getMonthValue() - 1;
                    };
                    case "getDate" -> (JsCallable) (context, args) -> {
                        ZonedDateTime dt = context.thisObject instanceof JsDate ? ((JsDate) context.thisObject).value : value;
                        return dt.getDayOfMonth();
                    };
                    case "getDay" -> (JsCallable) (context, args) -> {
                        ZonedDateTime dt = context.thisObject instanceof JsDate ? ((JsDate) context.thisObject).value : value;
                        // Convert Java's 1-7 (Mon-Sun) to JavaScript's 0-6 (Sun-Sat)
                        return dt.getDayOfWeek().getValue() % 7;
                    };
                    case "getHours" -> (JsCallable) (context, args) -> {
                        ZonedDateTime dt = context.thisObject instanceof JsDate ? ((JsDate) context.thisObject).value : value;
                        return dt.getHour();
                    };
                    case "getMinutes" -> (JsCallable) (context, args) -> {
                        ZonedDateTime dt = context.thisObject instanceof JsDate ? ((JsDate) context.thisObject).value : value;
                        return dt.getMinute();
                    };
                    case "getSeconds" -> (JsCallable) (context, args) -> {
                        ZonedDateTime dt = context.thisObject instanceof JsDate ? ((JsDate) context.thisObject).value : value;
                        return dt.getSecond();
                    };
                    case "getMilliseconds" -> (JsCallable) (context, args) -> {
                        ZonedDateTime dt = context.thisObject instanceof JsDate ? ((JsDate) context.thisObject).value : value;
                        return dt.get(ChronoField.MILLI_OF_SECOND);
                    };
                    // Setters
                    case "setDate" -> (JsCallable) (context, args) -> {
                        if (args.length == 0 || !(args[0] instanceof Number)) {
                            return Double.NaN;
                        }
                        int day = ((Number) args[0]).intValue();
                        ZonedDateTime dt = context.thisObject instanceof JsDate ? ((JsDate) context.thisObject).value : value;
                        // JavaScript allows overflow/underflow - e.g., setDate(32) in January rolls to February
                        // Use plusDays to handle this correctly
                        int currentDay = dt.getDayOfMonth();
                        int dayDifference = day - currentDay;
                        ZonedDateTime newDt = dt.plusDays(dayDifference);
                        if (context.thisObject instanceof JsDate) {
                            ((JsDate) context.thisObject).setValue(newDt);
                        }
                        return newDt.toInstant().toEpochMilli();
                    };
                    case "setMonth" -> (JsCallable) (context, args) -> {
                        if (args.length == 0 || !(args[0] instanceof Number)) {
                            return Double.NaN;
                        }
                        int monthValue = ((Number) args[0]).intValue();
                        ZonedDateTime dt = context.thisObject instanceof JsDate ? ((JsDate) context.thisObject).value : value;
                        
                        // JavaScript allows month overflow/underflow
                        // Calculate year and month adjustments
                        int yearAdjustment = 0;
                        int finalMonth = monthValue;
                        
                        if (monthValue < 0) {
                            // Handle negative months
                            yearAdjustment = (monthValue / 12) - 1;
                            finalMonth = 12 + (monthValue % 12);
                        } else if (monthValue > 11) {
                            // Handle months > 11
                            yearAdjustment = monthValue / 12;
                            finalMonth = monthValue % 12;
                        }
                        
                        // JavaScript months are 0-indexed, Java months are 1-indexed
                        ZonedDateTime newDt = dt.plusYears(yearAdjustment).withMonth(finalMonth + 1);
                        
                        // Handle optional day parameter
                        if (args.length > 1 && args[1] instanceof Number) {
                            int day = ((Number) args[1]).intValue();
                            int currentDay = newDt.getDayOfMonth();
                            newDt = newDt.plusDays(day - currentDay);
                        }
                        
                        if (context.thisObject instanceof JsDate) {
                            ((JsDate) context.thisObject).setValue(newDt);
                        }
                        return newDt.toInstant().toEpochMilli();
                    };
                    case "setFullYear" -> (JsCallable) (context, args) -> {
                        if (args.length == 0 || !(args[0] instanceof Number)) {
                            return Double.NaN;
                        }
                        int year = ((Number) args[0]).intValue();
                        ZonedDateTime dt = context.thisObject instanceof JsDate ? ((JsDate) context.thisObject).value : value;
                        ZonedDateTime newDt = dt.withYear(year);
                        
                        // Handle optional month parameter
                        if (args.length > 1 && args[1] instanceof Number) {
                            int monthValue = ((Number) args[1]).intValue();
                            // Handle month overflow/underflow
                            int yearAdjustment = 0;
                            int finalMonth = monthValue;
                            
                            if (monthValue < 0) {
                                yearAdjustment = (monthValue / 12) - 1;
                                finalMonth = 12 + (monthValue % 12);
                            } else if (monthValue > 11) {
                                yearAdjustment = monthValue / 12;
                                finalMonth = monthValue % 12;
                            }
                            
                            newDt = newDt.plusYears(yearAdjustment).withMonth(finalMonth + 1);
                        }
                        
                        // Handle optional day parameter
                        if (args.length > 2 && args[2] instanceof Number) {
                            int day = ((Number) args[2]).intValue();
                            int currentDay = newDt.getDayOfMonth();
                            newDt = newDt.plusDays(day - currentDay);
                        }
                        
                        if (context.thisObject instanceof JsDate) {
                            ((JsDate) context.thisObject).setValue(newDt);
                        }
                        return newDt.toInstant().toEpochMilli();
                    };
                    case "setHours" -> (JsCallable) (context, args) -> {
                        if (args.length == 0 || !(args[0] instanceof Number)) {
                            return Double.NaN;
                        }
                        int hours = ((Number) args[0]).intValue();
                        ZonedDateTime dt = context.thisObject instanceof JsDate ? ((JsDate) context.thisObject).value : value;
                        // JavaScript allows hour overflow/underflow
                        int currentHour = dt.getHour();
                        ZonedDateTime newDt = dt.plusHours(hours - currentHour);
                        // Handle optional minute, second, and millisecond parameters
                        if (args.length > 1 && args[1] instanceof Number) {
                            int minutes = ((Number) args[1]).intValue();
                            int currentMinute = newDt.getMinute();
                            newDt = newDt.plusMinutes(minutes - currentMinute);
                        }
                        if (args.length > 2 && args[2] instanceof Number) {
                            int seconds = ((Number) args[2]).intValue();
                            int currentSecond = newDt.getSecond();
                            newDt = newDt.plusSeconds(seconds - currentSecond);
                        }
                        if (args.length > 3 && args[3] instanceof Number) {
                            int ms = ((Number) args[3]).intValue();
                            int currentMs = newDt.get(ChronoField.MILLI_OF_SECOND);
                            newDt = newDt.plusNanos((ms - currentMs) * 1000000);
                        }
                        if (context.thisObject instanceof JsDate) {
                            ((JsDate) context.thisObject).setValue(newDt);
                        }
                        return newDt.toInstant().toEpochMilli();
                    };
                    case "setMinutes" -> (JsCallable) (context, args) -> {
                        if (args.length == 0 || !(args[0] instanceof Number)) {
                            return Double.NaN;
                        }
                        int minutes = ((Number) args[0]).intValue();
                        ZonedDateTime dt = context.thisObject instanceof JsDate ? ((JsDate) context.thisObject).value : value;
                        // JavaScript allows minute overflow/underflow
                        int currentMinute = dt.getMinute();
                        ZonedDateTime newDt = dt.plusMinutes(minutes - currentMinute);
                        // Handle optional second and millisecond parameters
                        if (args.length > 1 && args[1] instanceof Number) {
                            int seconds = ((Number) args[1]).intValue();
                            int currentSecond = newDt.getSecond();
                            newDt = newDt.plusSeconds(seconds - currentSecond);
                        }
                        if (args.length > 2 && args[2] instanceof Number) {
                            int ms = ((Number) args[2]).intValue();
                            int currentMs = newDt.get(ChronoField.MILLI_OF_SECOND);
                            newDt = newDt.plusNanos((ms - currentMs) * 1000000);
                        }
                        if (context.thisObject instanceof JsDate) {
                            ((JsDate) context.thisObject).setValue(newDt);
                        }
                        return newDt.toInstant().toEpochMilli();
                    };
                    case "setSeconds" -> (JsCallable) (context, args) -> {
                        if (args.length == 0 || !(args[0] instanceof Number)) {
                            return Double.NaN;
                        }
                        int seconds = ((Number) args[0]).intValue();
                        ZonedDateTime dt = context.thisObject instanceof JsDate ? ((JsDate) context.thisObject).value : value;
                        // JavaScript allows second overflow/underflow
                        int currentSecond = dt.getSecond();
                        ZonedDateTime newDt = dt.plusSeconds(seconds - currentSecond);
                        // Handle optional millisecond parameter
                        if (args.length > 1 && args[1] instanceof Number) {
                            int ms = ((Number) args[1]).intValue();
                            int currentMs = newDt.get(ChronoField.MILLI_OF_SECOND);
                            newDt = newDt.plusNanos((ms - currentMs) * 1000000);
                        }
                        if (context.thisObject instanceof JsDate) {
                            ((JsDate) context.thisObject).setValue(newDt);
                        }
                        return newDt.toInstant().toEpochMilli();
                    };
                    case "setMilliseconds" -> (JsCallable) (context, args) -> {
                        if (args.length == 0 || !(args[0] instanceof Number)) {
                            return Double.NaN;
                        }
                        int ms = ((Number) args[0]).intValue();
                        ZonedDateTime dt = context.thisObject instanceof JsDate ? ((JsDate) context.thisObject).value : value;
                        // JavaScript allows millisecond overflow/underflow
                        int currentMs = dt.get(ChronoField.MILLI_OF_SECOND);
                        ZonedDateTime newDt = dt.plusNanos((ms - currentMs) * 1000000);
                        if (context.thisObject instanceof JsDate) {
                            ((JsDate) context.thisObject).setValue(newDt);
                        }
                        return newDt.toInstant().toEpochMilli();
                    };
                    case "setTime" -> (JsCallable) (context, args) -> {
                        if (args.length == 0 || !(args[0] instanceof Number)) {
                            return Double.NaN;
                        }
                        long timestamp = ((Number) args[0]).longValue();
                        ZonedDateTime newDt = ZonedDateTime.ofInstant(
                                Instant.ofEpochMilli(timestamp),
                                ZoneId.systemDefault());
                        if (context.thisObject instanceof JsDate) {
                            ((JsDate) context.thisObject).setValue(newDt);
                        }
                        return timestamp;
                    };
                    default -> null;
                };
            }
        };
    }

    @Override
    public Object call(Context context, Object... args) {
        if (args.length == 0) {
            return new JsDate();
        } else if (args.length == 1) {
            Object arg = args[0];
            if (arg instanceof Number) {
                // Date(timestamp)
                return new JsDate(((Number) arg).longValue());
            } else if (arg instanceof String) {
                // Date(dateString)
                return new JsDate((String) arg);
            } else if (arg instanceof JsDate) {
                // Date(dateObject)
                return new JsDate(((JsDate) arg).value);
            }
        } else if (args.length >= 3) {
            // Date(year, month, day, [hours, minutes, seconds, ms])
            int year = args[0] instanceof Number ? ((Number) args[0]).intValue() : 0;
            int month = args[1] instanceof Number ? ((Number) args[1]).intValue() : 0;
            int day = args[2] instanceof Number ? ((Number) args[2]).intValue() : 1;
            if (args.length >= 6) {
                int hours = args[3] instanceof Number ? ((Number) args[3]).intValue() : 0;
                int minutes = args[4] instanceof Number ? ((Number) args[4]).intValue() : 0;
                int seconds = args[5] instanceof Number ? ((Number) args[5]).intValue() : 0;
                if (args.length >= 7) {
                    int ms = args[6] instanceof Number ? ((Number) args[6]).intValue() : 0;
                    return new JsDate(year, month, day, hours, minutes, seconds, ms);
                }
                return new JsDate(year, month, day, hours, minutes, seconds);
            }
            return new JsDate(year, month, day);
        }
        return new JsDate();
    }

}
