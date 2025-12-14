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

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Date;

class JsDate extends JsObject implements JavaMirror {

    private static final DateTimeFormatter ISO_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private static final DateTimeFormatter UTC_STRING_FORMATTER =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'").withZone(ZoneOffset.UTC);

    private long millis;

    JsDate() {
        this.millis = System.currentTimeMillis();
    }

    JsDate(long timestamp) {
        this.millis = timestamp;
    }

    JsDate(Date date) {
        this.millis = date.getTime();
    }

    JsDate(int year, int month, int date) {
        ZonedDateTime zdt = ZonedDateTime.of(year, month + 1, date, 0, 0, 0, 0, ZoneId.systemDefault());
        this.millis = zdt.toInstant().toEpochMilli();
    }

    JsDate(int year, int month, int date, int hours, int minutes, int seconds) {
        ZonedDateTime zdt = ZonedDateTime.of(year, month + 1, date, hours, minutes, seconds, 0, ZoneId.systemDefault());
        this.millis = zdt.toInstant().toEpochMilli();
    }

    JsDate(int year, int month, int date, int hours, int minutes, int seconds, int ms) {
        ZonedDateTime zdt = ZonedDateTime.of(year, month + 1, date, hours, minutes, seconds, ms * 1_000_000, ZoneId.systemDefault());
        this.millis = zdt.toInstant().toEpochMilli();
    }

    JsDate(Instant instant) {
        this.millis = instant.toEpochMilli();
    }

    JsDate(LocalDateTime ldt) {
        this.millis = ldt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    JsDate(LocalDate ld) {
        this.millis = ld.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    JsDate(ZonedDateTime zdt) {
        this.millis = zdt.toInstant().toEpochMilli();
    }

    JsDate(String text) {
        this.millis = parseToMillis(text);
    }

    private ZonedDateTime toZonedDateTime() {
        return ZonedDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault());
    }

    long getTime() {
        return millis;
    }

    private static long parseToMillis(String dateStr) {
        // Try ISO format with milliseconds: yyyy-MM-dd'T'HH:mm:ss.SSS'Z'
        try {
            return Instant.parse(dateStr.endsWith("Z") ? dateStr : dateStr + "Z").toEpochMilli();
        } catch (Exception e) {
            // continue
        }
        // Try ISO format: yyyy-MM-dd'T'HH:mm:ss
        try {
            if (dateStr.contains("T")) {
                LocalDateTime ldt = LocalDateTime.parse(dateStr.replace("Z", ""));
                return ldt.atZone(dateStr.endsWith("Z") ? ZoneOffset.UTC : ZoneId.systemDefault())
                        .toInstant().toEpochMilli();
            }
        } catch (Exception e) {
            // continue
        }
        // Try date only: yyyy-MM-dd
        try {
            LocalDate ld = LocalDate.parse(dateStr);
            return ld.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (DateTimeParseException e) {
            // continue
        }
        // if parsing fails, return current time
        return System.currentTimeMillis();
    }

    // Public parse method returns Date for backward compatibility
    static Date parse(String dateStr) {
        return new Date(parseToMillis(dateStr));
    }

    @Override
    public String toString() {
        return toZonedDateTime().toString();
    }

    @Override
    public Object getJavaValue() {
        return new Date(millis);
    }

    @Override
    public Object getInternalValue() {
        return millis;
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
                            return parseToMillis(dateStr);
                        } catch (Exception e) {
                            return Double.NaN;
                        }
                    };
                    case "getTime", "valueOf" -> (JsCallable) (context, args) -> fromThis(context).millis;
                    case "toString" -> (JsCallable) (context, args) -> fromThis(context).toString();
                    case "toISOString" -> (JsCallable) (context, args) ->
                            ISO_FORMATTER.format(Instant.ofEpochMilli(fromThis(context).millis));
                    case "toUTCString" -> (JsCallable) (context, args) ->
                            UTC_STRING_FORMATTER.format(Instant.ofEpochMilli(fromThis(context).millis));
                    case "getFullYear" -> (JsCallable) (context, args) ->
                            fromThis(context).toZonedDateTime().getYear();
                    case "getMonth" -> (JsCallable) (context, args) ->
                            fromThis(context).toZonedDateTime().getMonthValue() - 1; // 0-indexed
                    case "getDate" -> (JsCallable) (context, args) ->
                            fromThis(context).toZonedDateTime().getDayOfMonth();
                    case "getDay" -> (JsCallable) (context, args) ->
                            fromThis(context).toZonedDateTime().getDayOfWeek().getValue() % 7; // Sun=0
                    case "getHours" -> (JsCallable) (context, args) ->
                            fromThis(context).toZonedDateTime().getHour();
                    case "getMinutes" -> (JsCallable) (context, args) ->
                            fromThis(context).toZonedDateTime().getMinute();
                    case "getSeconds" -> (JsCallable) (context, args) ->
                            fromThis(context).toZonedDateTime().getSecond();
                    case "getMilliseconds" -> (JsCallable) (context, args) ->
                            fromThis(context).toZonedDateTime().getNano() / 1_000_000;
                    case "setDate" -> (JsCallable) (context, args) -> {
                        if (args.length == 0 || !(args[0] instanceof Number)) {
                            return Double.NaN;
                        }
                        int day = ((Number) args[0]).intValue();
                        JsDate jsDate = fromThis(context);
                        ZonedDateTime zdt = jsDate.toZonedDateTime();
                        // Set to 1st of month, then add (day-1) to handle overflow
                        zdt = zdt.withDayOfMonth(1).plusDays(day - 1);
                        jsDate.millis = zdt.toInstant().toEpochMilli();
                        return jsDate.millis;
                    };
                    case "setMonth" -> (JsCallable) (context, args) -> {
                        if (args.length == 0 || !(args[0] instanceof Number)) {
                            return Double.NaN;
                        }
                        int month = ((Number) args[0]).intValue();
                        JsDate jsDate = fromThis(context);
                        ZonedDateTime zdt = jsDate.toZonedDateTime();
                        int originalDay = zdt.getDayOfMonth();
                        // Set to Jan 1st of current year, add months, then restore day
                        zdt = zdt.withMonth(1).withDayOfMonth(1).plusMonths(month);
                        int maxDay = zdt.toLocalDate().lengthOfMonth();
                        zdt = zdt.withDayOfMonth(Math.min(originalDay, maxDay));
                        if (args.length > 1 && args[1] instanceof Number) {
                            int day = ((Number) args[1]).intValue();
                            zdt = zdt.withDayOfMonth(1).plusDays(day - 1);
                        }
                        jsDate.millis = zdt.toInstant().toEpochMilli();
                        return jsDate.millis;
                    };
                    case "setFullYear" -> (JsCallable) (context, args) -> {
                        if (args.length == 0 || !(args[0] instanceof Number)) {
                            return Double.NaN;
                        }
                        int year = ((Number) args[0]).intValue();
                        JsDate jsDate = fromThis(context);
                        ZonedDateTime zdt = jsDate.toZonedDateTime();
                        int originalDay = zdt.getDayOfMonth();
                        zdt = zdt.withYear(year);
                        // Handle Feb 29 -> Feb 28 for non-leap years
                        int maxDay = zdt.toLocalDate().lengthOfMonth();
                        if (originalDay > maxDay) {
                            zdt = zdt.withDayOfMonth(maxDay);
                        }
                        if (args.length > 1 && args[1] instanceof Number) {
                            int month = ((Number) args[1]).intValue();
                            zdt = zdt.withMonth(1).withDayOfMonth(1).plusMonths(month);
                            maxDay = zdt.toLocalDate().lengthOfMonth();
                            zdt = zdt.withDayOfMonth(Math.min(originalDay, maxDay));
                        }
                        if (args.length > 2 && args[2] instanceof Number) {
                            int day = ((Number) args[2]).intValue();
                            zdt = zdt.withDayOfMonth(1).plusDays(day - 1);
                        }
                        jsDate.millis = zdt.toInstant().toEpochMilli();
                        return jsDate.millis;
                    };
                    case "setHours" -> (JsCallable) (context, args) -> {
                        if (args.length == 0 || !(args[0] instanceof Number)) {
                            return Double.NaN;
                        }
                        int hours = ((Number) args[0]).intValue();
                        JsDate jsDate = fromThis(context);
                        ZonedDateTime zdt = jsDate.toZonedDateTime()
                                .withHour(0).plusHours(hours);
                        if (args.length > 1 && args[1] instanceof Number) {
                            int minutes = ((Number) args[1]).intValue();
                            zdt = zdt.withMinute(0).plusMinutes(minutes);
                        }
                        if (args.length > 2 && args[2] instanceof Number) {
                            int seconds = ((Number) args[2]).intValue();
                            zdt = zdt.withSecond(0).plusSeconds(seconds);
                        }
                        if (args.length > 3 && args[3] instanceof Number) {
                            int ms = ((Number) args[3]).intValue();
                            zdt = zdt.withNano(0).plusNanos(ms * 1_000_000L);
                        }
                        jsDate.millis = zdt.toInstant().toEpochMilli();
                        return jsDate.millis;
                    };
                    case "setMinutes" -> (JsCallable) (context, args) -> {
                        if (args.length == 0 || !(args[0] instanceof Number)) {
                            return Double.NaN;
                        }
                        int minutes = ((Number) args[0]).intValue();
                        JsDate jsDate = fromThis(context);
                        ZonedDateTime zdt = jsDate.toZonedDateTime()
                                .withMinute(0).plusMinutes(minutes);
                        if (args.length > 1 && args[1] instanceof Number) {
                            int seconds = ((Number) args[1]).intValue();
                            zdt = zdt.withSecond(0).plusSeconds(seconds);
                        }
                        if (args.length > 2 && args[2] instanceof Number) {
                            int ms = ((Number) args[2]).intValue();
                            zdt = zdt.withNano(0).plusNanos(ms * 1_000_000L);
                        }
                        jsDate.millis = zdt.toInstant().toEpochMilli();
                        return jsDate.millis;
                    };
                    case "setSeconds" -> (JsCallable) (context, args) -> {
                        if (args.length == 0 || !(args[0] instanceof Number)) {
                            return Double.NaN;
                        }
                        int seconds = ((Number) args[0]).intValue();
                        JsDate jsDate = fromThis(context);
                        ZonedDateTime zdt = jsDate.toZonedDateTime()
                                .withSecond(0).plusSeconds(seconds);
                        if (args.length > 1 && args[1] instanceof Number) {
                            int ms = ((Number) args[1]).intValue();
                            zdt = zdt.withNano(0).plusNanos(ms * 1_000_000L);
                        }
                        jsDate.millis = zdt.toInstant().toEpochMilli();
                        return jsDate.millis;
                    };
                    case "setMilliseconds" -> (JsCallable) (context, args) -> {
                        if (args.length == 0 || !(args[0] instanceof Number)) {
                            return Double.NaN;
                        }
                        int ms = ((Number) args[0]).intValue();
                        JsDate jsDate = fromThis(context);
                        ZonedDateTime zdt = jsDate.toZonedDateTime()
                                .withNano(0).plusNanos(ms * 1_000_000L);
                        jsDate.millis = zdt.toInstant().toEpochMilli();
                        return jsDate.millis;
                    };
                    case "setTime" -> (JsCallable) (context, args) -> {
                        if (args.length == 0 || !(args[0] instanceof Number)) {
                            return Double.NaN;
                        }
                        long timestamp = ((Number) args[0]).longValue();
                        JsDate jsDate = fromThis(context);
                        jsDate.millis = timestamp;
                        return timestamp;
                    };
                    default -> null;
                };
            }
        };
    }

    @Override
    JsDate fromThis(Context context) {
        if (context.getThisObject() instanceof JsDate date) {
            return date;
        }
        return this;
    }

    @Override
    public Object call(Context context, Object... args) {
        // Check if called with 'new' keyword
        CallInfo callInfo = context.getCallInfo();
        boolean isNew = callInfo != null && callInfo.constructor;

        JsDate result;
        if (args.length == 0) {
            result = new JsDate();
        } else if (args.length == 1) {
            Object arg = args[0];
            if (arg instanceof Number n) {
                result = new JsDate(n.longValue());
            } else if (arg instanceof String s) {
                result = new JsDate(s);
            } else if (arg instanceof JsDate date) {
                result = new JsDate(date.millis);
            } else if (arg instanceof Date date) {
                result = new JsDate(date);
            } else {
                result = new JsDate();
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
                    result = new JsDate(year, month, day, hours, minutes, seconds, ms);
                } else {
                    result = new JsDate(year, month, day, hours, minutes, seconds);
                }
            } else {
                result = new JsDate(year, month, day);
            }
        } else {
            result = new JsDate();
        }

        // Return JsDate for 'new' calls (JavaMirror), Date for function calls
        return isNew ? result : new Date(result.millis);
    }
}
