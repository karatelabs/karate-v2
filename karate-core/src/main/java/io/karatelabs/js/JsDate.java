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

class JsDate extends JsObject {

    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
    private static final ZoneId SYS = ZoneId.systemDefault();
    private static final ZoneId UTC = ZoneId.of("UTC");

    private ZonedDateTime value;

    JsDate(ZonedDateTime dateTime) {
        this.value = dateTime;
    }

    JsDate() {
        this(ZonedDateTime.now(SYS));
    }

    JsDate(long timestamp) {
        this.value = ZonedDateTime.ofInstant(Instant.ofEpochMilli(timestamp), SYS);
    }

    JsDate(int year, int month, int date) {
        // JavaScript months are 0-indexed, Java months are 1-indexed
        this.value = ZonedDateTime.of(year, month + 1, date, 0, 0, 0, 0, SYS);
    }

    JsDate(int year, int month, int date, int hours, int minutes, int seconds) {
        this.value = ZonedDateTime.of(year, month + 1, date, hours, minutes, seconds, 0, SYS);
    }

    JsDate(int year, int month, int date, int hours, int minutes, int seconds, int ms) {
        this.value = ZonedDateTime.of(year, month + 1, date, hours, minutes, seconds, ms * 1000000, SYS);
    }

    long getTime() {
        return value.toInstant().toEpochMilli();
    }

    static ZonedDateTime parse(String date) {
        try {
            LocalDateTime localDateTime = LocalDateTime.parse(date, DateTimeFormatter.ISO_DATE_TIME);
            return ZonedDateTime.of(localDateTime, SYS);
        } catch (DateTimeParseException e) {
            try {
                LocalDate localDate = LocalDate.parse(date, DateTimeFormatter.ISO_DATE);
                return localDate.atStartOfDay(SYS);
            } catch (DateTimeParseException ee) {
                try {
                    OffsetDateTime offsetDateTime = OffsetDateTime.parse(date);
                    return offsetDateTime.toZonedDateTime();
                } catch (DateTimeParseException eee) {
                    return ZonedDateTime.now(SYS);
                }
            }
        }
    }

    JsDate(String text) {
        this.value = parse(text);
    }

    @Override
    public String toString() {
        return value.toString();
    }

    ZonedDateTime cast(Context context) {
        if (context.thisObject instanceof JsDate date) {
            return date.value;
        } else {
            return value;
        }
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
                    case "getTime", "valueOf" ->
                            (JsCallable) (context, args) -> cast(context).toInstant().toEpochMilli();
                    case "toString" -> (JsCallable) (context, args) -> cast(context).toString();
                    case "toISOString" ->
                            (JsCallable) (context, args) -> cast(context).withZoneSameInstant(UTC).format(ISO_FORMATTER);
                    case "toUTCString" -> (JsCallable) (context, args) -> {
                        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'");
                        return cast(context).withZoneSameInstant(UTC).format(formatter);
                    };
                    case "getFullYear" -> (JsCallable) (context, args) -> cast(context).getYear();
                    case "getMonth" -> (JsCallable) (context, args) -> cast(context).getMonthValue() - 1;
                    case "getDate" -> (JsCallable) (context, args) -> cast(context).getDayOfMonth();
                    case "getDay" -> (JsCallable) (context, args) -> cast(context).getDayOfWeek().getValue() % 7;
                    case "getHours" -> (JsCallable) (context, args) -> cast(context).getHour();
                    case "getMinutes" -> (JsCallable) (context, args) -> cast(context).getMinute();
                    case "getSeconds" -> (JsCallable) (context, args) -> cast(context).getSecond();
                    case "getMilliseconds" ->
                            (JsCallable) (context, args) -> cast(context).get(ChronoField.MILLI_OF_SECOND);
                    // Setters
                    case "setDate" -> (JsCallable) (context, args) -> {
                        if (args.length == 0 || !(args[0] instanceof Number)) {
                            return Double.NaN;
                        }
                        int day = ((Number) args[0]).intValue();
                        // JavaScript allows overflow/underflow - e.g., setDate(32) in January rolls to February
                        // Use plusDays to handle this correctly
                        ZonedDateTime zdt = cast(context);
                        int currentDay = zdt.getDayOfMonth();
                        int dayDifference = day - currentDay;
                        ZonedDateTime newDt = zdt.plusDays(dayDifference);
                        if (context.thisObject instanceof JsDate date) {
                            date.value = newDt;
                        }
                        return newDt.toInstant().toEpochMilli();
                    };
                    case "setMonth" -> (JsCallable) (context, args) -> {
                        if (args.length == 0 || !(args[0] instanceof Number)) {
                            return Double.NaN;
                        }
                        int monthValue = ((Number) args[0]).intValue();
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
                        ZonedDateTime newDt = cast(context).plusYears(yearAdjustment).withMonth(finalMonth + 1);
                        // Handle optional day parameter
                        if (args.length > 1 && args[1] instanceof Number) {
                            int day = ((Number) args[1]).intValue();
                            int currentDay = newDt.getDayOfMonth();
                            newDt = newDt.plusDays(day - currentDay);
                        }
                        if (context.thisObject instanceof JsDate date) {
                            date.value = newDt;
                        }
                        return newDt.toInstant().toEpochMilli();
                    };
                    case "setFullYear" -> (JsCallable) (context, args) -> {
                        if (args.length == 0 || !(args[0] instanceof Number)) {
                            return Double.NaN;
                        }
                        int year = ((Number) args[0]).intValue();
                        ZonedDateTime newDt = cast(context).withYear(year);
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
                        if (context.thisObject instanceof JsDate date) {
                            date.value = newDt;
                        }
                        return newDt.toInstant().toEpochMilli();
                    };
                    case "setHours" -> (JsCallable) (context, args) -> {
                        if (args.length == 0 || !(args[0] instanceof Number)) {
                            return Double.NaN;
                        }
                        int hours = ((Number) args[0]).intValue();
                        // JavaScript allows hour overflow/underflow
                        ZonedDateTime zdt = cast(context);
                        int currentHour = zdt.getHour();
                        ZonedDateTime newDt = zdt.plusHours(hours - currentHour);
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
                            newDt = newDt.plusNanos((ms - currentMs) * 1000000L);
                        }
                        if (context.thisObject instanceof JsDate date) {
                            date.value = newDt;
                        }
                        return newDt.toInstant().toEpochMilli();
                    };
                    case "setMinutes" -> (JsCallable) (context, args) -> {
                        if (args.length == 0 || !(args[0] instanceof Number)) {
                            return Double.NaN;
                        }
                        int minutes = ((Number) args[0]).intValue();
                        ZonedDateTime zdt = cast(context);
                        // JavaScript allows minute overflow/underflow
                        int currentMinute = zdt.getMinute();
                        ZonedDateTime newDt = zdt.plusMinutes(minutes - currentMinute);
                        // Handle optional second and millisecond parameters
                        if (args.length > 1 && args[1] instanceof Number) {
                            int seconds = ((Number) args[1]).intValue();
                            int currentSecond = newDt.getSecond();
                            newDt = newDt.plusSeconds(seconds - currentSecond);
                        }
                        if (args.length > 2 && args[2] instanceof Number) {
                            int ms = ((Number) args[2]).intValue();
                            int currentMs = newDt.get(ChronoField.MILLI_OF_SECOND);
                            newDt = newDt.plusNanos((ms - currentMs) * 1000000L);
                        }
                        if (context.thisObject instanceof JsDate date) {
                            date.value = newDt;
                        }
                        return newDt.toInstant().toEpochMilli();
                    };
                    case "setSeconds" -> (JsCallable) (context, args) -> {
                        if (args.length == 0 || !(args[0] instanceof Number)) {
                            return Double.NaN;
                        }
                        int seconds = ((Number) args[0]).intValue();
                        ZonedDateTime zdt = cast(context);
                        // JavaScript allows second overflow/underflow
                        int currentSecond = zdt.getSecond();
                        ZonedDateTime newDt = zdt.plusSeconds(seconds - currentSecond);
                        // Handle optional millisecond parameter
                        if (args.length > 1 && args[1] instanceof Number) {
                            int ms = ((Number) args[1]).intValue();
                            int currentMs = newDt.get(ChronoField.MILLI_OF_SECOND);
                            newDt = newDt.plusNanos((ms - currentMs) * 1000000L);
                        }
                        if (context.thisObject instanceof JsDate date) {
                            date.value = newDt;
                        }
                        return newDt.toInstant().toEpochMilli();
                    };
                    case "setMilliseconds" -> (JsCallable) (context, args) -> {
                        if (args.length == 0 || !(args[0] instanceof Number)) {
                            return Double.NaN;
                        }
                        int ms = ((Number) args[0]).intValue();
                        ZonedDateTime zdt = cast(context);
                        // JavaScript allows millisecond overflow/underflow
                        int currentMs = zdt.get(ChronoField.MILLI_OF_SECOND);
                        ZonedDateTime newDt = zdt.plusNanos((ms - currentMs) * 1000000L);
                        if (context.thisObject instanceof JsDate date) {
                            date.value = newDt;
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
                        if (context.thisObject instanceof JsDate date) {
                            date.value = newDt;
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
            if (arg instanceof Number n) {
                return new JsDate(n.longValue());
            } else if (arg instanceof String s) {
                return new JsDate(s);
            } else if (arg instanceof JsDate date) {
                return new JsDate(date.value);
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
