/*
 * The MIT License
 *
 * Copyright 2024 Karate Labs Inc.
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

import java.util.*;

/**
 * JavaScript Array implementation using composition (has-a JsObject) instead of inheritance.
 * This allows JsArray to implement List<Object> while JsObject implements Map<String, Object>,
 * avoiding the method signature conflict between Map.remove(Object) and List.remove(Object).
 *
 * Implements List<Object> for Java interop with auto-unwrapping - Java code gets clean values.
 * JS internal code uses getElement/setElement for raw access (returns UNDEFINED for out of bounds).
 */
class JsArray implements List<Object>, ObjectLike, JsCallable {

    final List<Object> list;

    // Composition: JsObject for object properties (arrays can have named properties like arr.foo = "bar")
    private final JsObject delegate;

    // Reference to self for prototype methods (used by fromThis)
    final JsArray _this;

    private Prototype prototype;

    public JsArray(List<Object> list) {
        this.list = list;
        this.delegate = new JsObject();
        this._this = this;
    }

    public JsArray() {
        this(new ArrayList<>());
    }

    // =================================================================================================
    // Prototype chain
    // =================================================================================================

    Prototype getPrototype() {
        if (prototype == null) {
            prototype = initPrototype();
        }
        return prototype;
    }

    Prototype initPrototype() {
        Prototype objectPrototype = delegate.getPrototype();
        return new Prototype(objectPrototype) {
            @SuppressWarnings("unchecked")
            @Override
            public Object getProperty(String propName) {
                return switch (propName) {
                    case "length" -> list.size();
                    case "map" -> (JsCallable) (context, args) -> {
                        List<Object> results = new ArrayList<>();
                        JsCallable callable = toCallable(args);
                        for (KeyValue kv : Terms.toIterable(context.getThisObject())) {
                            Object result = callable.call(context, kv.value, kv.index);
                            results.add(result);
                        }
                        return results;
                    };
                    case "filter" -> (JsCallable) (context, args) -> {
                        List<Object> results = new ArrayList<>();
                        JsCallable callable = toCallable(args);
                        for (KeyValue kv : _this.jsEntries()) {
                            Object result = callable.call(context, kv.value, kv.index);
                            if (Terms.isTruthy(result)) {
                                results.add(kv.value);
                            }
                        }
                        return results;
                    };
                    case "join" -> (JsCallable) (context, args) -> {
                        StringBuilder sb = new StringBuilder();
                        String delimiter;
                        if (args.length > 0 && args[0] != null) {
                            delimiter = args[0].toString();
                        } else {
                            delimiter = ",";
                        }
                        for (KeyValue kv : _this.jsEntries()) {
                            if (!sb.isEmpty()) {
                                sb.append(delimiter);
                            }
                            sb.append(kv.value);
                        }
                        return sb.toString();
                    };
                    case "find" -> (JsCallable) (context, args) -> {
                        JsCallable callable = toCallable(args);
                        for (KeyValue kv : _this.jsEntries()) {
                            Object result = callable.call(context, kv.value, kv.index);
                            if (Terms.isTruthy(result)) {
                                return kv.value;
                            }
                        }
                        return Terms.UNDEFINED;
                    };
                    case "findIndex" -> (JsCallable) (context, args) -> {
                        JsCallable callable = toCallable(args);
                        for (KeyValue kv : _this.jsEntries()) {
                            Object result = callable.call(context, kv.value, kv.index);
                            if (Terms.isTruthy(result)) {
                                return kv.index;
                            }
                        }
                        return -1;
                    };
                    case "push" -> (JsCallable) (context, args) -> {
                        List<Object> thisArray = rawList(context);
                        thisArray.addAll(Arrays.asList(args));
                        return thisArray.size();
                    };
                    case "reverse" -> (JsCallable) (context, args) -> {
                        List<Object> thisArray = rawList(context);
                        int size = thisArray.size();
                        List<Object> result = new ArrayList<>();
                        for (int i = size; i > 0; i--) {
                            result.add(thisArray.get(i - 1));
                        }
                        return result;
                    };
                    case "includes" -> (JsCallable) (context, args) -> {
                        for (KeyValue kv : _this.jsEntries()) {
                            if (Terms.eq(kv.value, args[0], false)) {
                                return true;
                            }
                        }
                        return false;
                    };
                    case "indexOf" -> (JsCallable) (context, args) -> {
                        List<Object> thisArray = rawList(context);
                        int size = thisArray.size();
                        if (size == 0) {
                            return -1;
                        }
                        if (args.length == 0) {
                            return -1;
                        }
                        Object searchElement = args[0];
                        int fromIndex = 0;
                        if (args.length > 1 && args[1] != null) {
                            fromIndex = Terms.objectToNumber(args[1]).intValue();
                            if (fromIndex < 0) {
                                fromIndex = Math.max(size + fromIndex, 0);
                            }
                        }
                        if (fromIndex >= size) {
                            return -1;
                        }
                        for (int i = fromIndex; i < size; i++) {
                            if (Terms.eq(thisArray.get(i), searchElement, false)) {
                                return i;
                            }
                        }
                        return -1;
                    };
                    case "slice" -> (JsCallable) (context, args) -> {
                        List<Object> thisArray = rawList(context);
                        int size = thisArray.size();
                        int start = 0;
                        int end = size;
                        if (args.length > 0 && args[0] != null) {
                            start = Terms.objectToNumber(args[0]).intValue();
                            if (start < 0) {
                                start = Math.max(size + start, 0);
                            }
                        }
                        if (args.length > 1 && args[1] != null) {
                            end = Terms.objectToNumber(args[1]).intValue();
                            if (end < 0) {
                                end = Math.max(size + end, 0);
                            }
                        }
                        start = Math.min(start, size);
                        end = Math.min(end, size);
                        List<Object> result = new ArrayList<>();
                        for (int i = start; i < end; i++) {
                            result.add(thisArray.get(i));
                        }
                        return result;
                    };
                    case "forEach" -> (JsCallable) (context, args) -> {
                        JsCallable callable = toCallable(args);
                        for (KeyValue kv : _this.jsEntries()) {
                            if (context instanceof CoreContext cc) {
                                cc.iteration = kv.index;
                            }
                            callable.call(context, kv.value, kv.index, context.getThisObject());
                        }
                        return Terms.UNDEFINED;
                    };
                    case "concat" -> (JsCallable) (context, args) -> {
                        List<Object> thisArray = rawList(context);
                        List<Object> result = new ArrayList<>(thisArray);
                        for (Object arg : args) {
                            if (arg instanceof List) {
                                result.addAll((List<Object>) arg);
                            } else if (arg instanceof JsArray) {
                                result.addAll(((JsArray) arg).toList());
                            } else {
                                result.add(arg);
                            }
                        }
                        return result;
                    };
                    case "every" -> (JsCallable) (context, args) -> {
                        List<Object> thisArray = rawList(context);
                        if (thisArray.isEmpty()) {
                            return true;
                        }
                        JsCallable callable = toCallable(args);
                        for (KeyValue kv : _this.jsEntries()) {
                            Object result = callable.call(context, kv.value, kv.index, thisArray);
                            if (!Terms.isTruthy(result)) {
                                return false;
                            }
                        }
                        return true;
                    };
                    case "some" -> (JsCallable) (context, args) -> {
                        List<Object> thisArray = rawList(context);
                        if (thisArray.isEmpty()) {
                            return false;
                        }
                        JsCallable callable = toCallable(args);
                        for (KeyValue kv : _this.jsEntries()) {
                            Object result = callable.call(context, kv.value, kv.index, thisArray);
                            if (Terms.isTruthy(result)) {
                                return true;
                            }
                        }
                        return false;
                    };
                    case "reduce" -> (JsCallable) (context, args) -> {
                        List<Object> thisArray = rawList(context);
                        JsCallable callable = toCallable(args);
                        if (thisArray.isEmpty() && args.length < 2) {
                            throw new RuntimeException("reduce() called on empty array with no initial value");
                        }
                        int startIndex = 0;
                        Object accumulator;
                        if (args.length >= 2) {
                            accumulator = args[1];
                        } else {
                            accumulator = thisArray.getFirst();
                            startIndex = 1;
                        }
                        for (int i = startIndex; i < thisArray.size(); i++) {
                            Object currentValue = thisArray.get(i);
                            accumulator = callable.call(context, accumulator, currentValue, i, thisArray);
                        }
                        return accumulator;
                    };
                    case "reduceRight" -> (JsCallable) (context, args) -> {
                        List<Object> thisArray = rawList(context);
                        JsCallable callable = toCallable(args);
                        if (thisArray.isEmpty() && args.length < 2) {
                            throw new RuntimeException("reduceRight() called on empty array with no initial value");
                        }
                        int startIndex = thisArray.size() - 1;
                        Object accumulator;
                        if (args.length >= 2) {
                            accumulator = args[1];
                        } else {
                            accumulator = thisArray.get(startIndex);
                            startIndex--;
                        }
                        for (int i = startIndex; i >= 0; i--) {
                            Object currentValue = thisArray.get(i);
                            accumulator = callable.call(context, accumulator, currentValue, i, thisArray);
                        }
                        return accumulator;
                    };
                    case "flat" -> (JsCallable) (context, args) -> {
                        List<Object> thisArray = rawList(context);
                        int depth = 1;
                        if (args.length > 0 && args[0] != null) {
                            Number depthNum = Terms.objectToNumber(args[0]);
                            if (!Double.isNaN(depthNum.doubleValue()) && !Double.isInfinite(depthNum.doubleValue())) {
                                depth = depthNum.intValue();
                            }
                        }
                        List<Object> result = new ArrayList<>();
                        flatten(thisArray, result, depth);
                        return result;
                    };
                    case "flatMap" -> (JsCallable) (context, args) -> {
                        List<Object> thisArray = rawList(context);
                        JsCallable callable = toCallable(args);
                        List<Object> mappedResult = new ArrayList<>();
                        int index = 0;
                        for (Object item : thisArray) {
                            Object mapped = callable.call(context, item, index, thisArray);
                            if (mapped instanceof List || mapped instanceof JsArray) {
                                List<Object> nestedList;
                                if (mapped instanceof JsArray) {
                                    nestedList = ((JsArray) mapped).toList();
                                } else {
                                    nestedList = (List<Object>) mapped;
                                }
                                mappedResult.addAll(nestedList);
                            } else {
                                mappedResult.add(mapped);
                            }
                            index++;
                        }
                        return mappedResult;
                    };
                    case "sort" -> (JsCallable) (context, args) -> {
                        List<Object> thisArray = rawList(context);
                        List<Object> list = new ArrayList<>(thisArray);
                        if (list.isEmpty()) {
                            return list;
                        }
                        if (args.length > 0 && args[0] instanceof JsCallable) {
                            JsCallable callable = toCallable(args);
                            list.sort((a, b) -> {
                                Object result = callable.call(context, a, b);
                                if (result instanceof Number) {
                                    return ((Number) result).intValue();
                                }
                                return 0;
                            });
                        } else {
                            list.sort((a, b) -> {
                                String strA = a != null ? a.toString() : "";
                                String strB = b != null ? b.toString() : "";
                                return strA.compareTo(strB);
                            });
                        }
                        // in js, sort modifies the original array and returns it
                        // since we're creating a new list, we need to update the original array
                        for (int i = 0; i < list.size(); i++) {
                            if (i < thisArray.size()) {
                                thisArray.set(i, list.get(i));
                            } else {
                                thisArray.add(list.get(i));
                            }
                        }
                        return thisArray;
                    };
                    case "fill" -> (JsCallable) (context, args) -> {
                        if (args.length == 0) {
                            return context.getThisObject();
                        }
                        List<Object> thisArray = rawList(context);
                        int size = thisArray.size();
                        if (size == 0) {
                            return thisArray;
                        }
                        Object value = args[0];
                        int start = 0;
                        int end = size;
                        if (args.length > 1 && args[1] != null) {
                            start = Terms.objectToNumber(args[1]).intValue();
                            if (start < 0) {
                                start = Math.max(size + start, 0);
                            }
                        }
                        if (args.length > 2 && args[2] != null) {
                            end = Terms.objectToNumber(args[2]).intValue();
                            if (end < 0) {
                                end = Math.max(size + end, 0);
                            }
                        }
                        start = Math.min(start, size);
                        end = Math.min(end, size);
                        for (int i = start; i < end; i++) {
                            thisArray.set(i, value);
                        }
                        return thisArray;
                    };
                    case "splice" -> (JsCallable) (context, args) -> {
                        if (args.length == 0) {
                            return new ArrayList<>();
                        }
                        List<Object> thisArray = rawList(context);
                        int size = thisArray.size();
                        if (size == 0) {
                            return new ArrayList<>();
                        }
                        int start = 0;
                        if (args[0] != null) {
                            start = Terms.objectToNumber(args[0]).intValue();
                            if (start < 0) {
                                start = Math.max(size + start, 0);
                            }
                        }
                        start = Math.min(start, size);
                        int deleteCount = size - start;
                        if (args.length > 1 && args[1] != null) {
                            deleteCount = Terms.objectToNumber(args[1]).intValue();
                            deleteCount = Math.min(Math.max(deleteCount, 0), size - start);
                        }
                        List<Object> elementsToAdd = new ArrayList<>();
                        if (args.length > 2) {
                            for (int i = 2; i < args.length; i++) {
                                elementsToAdd.add(args[i]);
                            }
                        }
                        List<Object> removedElements = new ArrayList<>();
                        for (int i = start; i < start + deleteCount; i++) {
                            removedElements.add(thisArray.get(i));
                        }
                        int newSize = size - deleteCount + elementsToAdd.size();
                        List<Object> newList = new ArrayList<>(newSize);
                        for (int i = 0; i < start; i++) {
                            newList.add(thisArray.get(i));
                        }
                        newList.addAll(elementsToAdd);
                        for (int i = start + deleteCount; i < size; i++) {
                            newList.add(thisArray.get(i));
                        }
                        // update original array
                        thisArray.clear();
                        thisArray.addAll(newList);
                        return removedElements;
                    };
                    case "shift" -> (JsCallable) (context, args) -> {
                        List<Object> thisArray = rawList(context);
                        int size = thisArray.size();
                        if (size == 0) {
                            return Terms.UNDEFINED;
                        }
                        Object firstElement = thisArray.getFirst();
                        List<Object> newList = new ArrayList<>(size - 1);
                        for (int i = 1; i < size; i++) {
                            newList.add(thisArray.get(i));
                        }
                        // update original array
                        thisArray.clear();
                        thisArray.addAll(newList);
                        return firstElement;
                    };
                    case "unshift" -> (JsCallable) (context, args) -> {
                        List<Object> thisArray = rawList(context);
                        if (args.length == 0) {
                            return thisArray.size();
                        }
                        List<Object> newList = new ArrayList<>(thisArray.size() + args.length);
                        newList.addAll(Arrays.asList(args));
                        newList.addAll(thisArray);
                        // update original array
                        thisArray.clear();
                        thisArray.addAll(newList);
                        return thisArray.size();
                    };
                    case "lastIndexOf" -> (JsCallable) (context, args) -> {
                        List<Object> thisArray = rawList(context);
                        int size = thisArray.size();
                        if (size == 0) {
                            return -1;
                        }
                        if (args.length == 0) {
                            return -1;
                        }
                        Object searchElement = args[0];
                        int fromIndex = size - 1;
                        if (args.length > 1 && args[1] != null) {
                            Number n = Terms.objectToNumber(args[1]);
                            if (Double.isNaN(n.doubleValue())) {
                                fromIndex = size - 1;
                            } else {
                                fromIndex = n.intValue();
                                if (fromIndex < 0) {
                                    fromIndex = size + fromIndex;
                                } else if (fromIndex >= size) {
                                    fromIndex = size - 1;
                                }
                            }
                        }
                        if (fromIndex < 0) {
                            return -1;
                        }
                        for (int i = fromIndex; i >= 0; i--) {
                            if (Terms.eq(thisArray.get(i), searchElement, false)) {
                                return i;
                            }
                        }
                        return -1;
                    };
                    case "pop" -> (JsCallable) (context, args) -> {
                        List<Object> thisArray = rawList(context);
                        int size = thisArray.size();
                        if (size == 0) {
                            return Terms.UNDEFINED;
                        }
                        Object lastElement = thisArray.get(size - 1);
                        List<Object> newList = new ArrayList<>(size - 1);
                        for (int i = 0; i < size - 1; i++) {
                            newList.add(thisArray.get(i));
                        }
                        // update original array
                        thisArray.clear();
                        thisArray.addAll(newList);
                        return lastElement;
                    };
                    case "at" -> (JsCallable) (context, args) -> {
                        List<Object> thisArray = rawList(context);
                        int size = thisArray.size();
                        if (size == 0 || args.length == 0 || args[0] == null) {
                            return Terms.UNDEFINED;
                        }
                        int index = Terms.objectToNumber(args[0]).intValue();
                        if (index < 0) {
                            index = size + index;
                        }
                        if (index < 0 || index >= size) {
                            return Terms.UNDEFINED;
                        }
                        return thisArray.get(index);
                    };
                    case "copyWithin" -> (JsCallable) (context, args) -> {
                        List<Object> thisArray = rawList(context);
                        int size = thisArray.size();
                        if (size == 0 || args.length == 0) {
                            return thisArray;
                        }
                        int target = Terms.objectToNumber(args[0]).intValue();
                        if (target < 0) {
                            target = Math.max(size + target, 0);
                        }
                        int start = 0;
                        if (args.length > 1 && args[1] != null) {
                            start = Terms.objectToNumber(args[1]).intValue();
                            if (start < 0) {
                                start = Math.max(size + start, 0);
                            }
                        }
                        int end = size;
                        if (args.length > 2 && args[2] != null) {
                            end = Terms.objectToNumber(args[2]).intValue();
                            if (end < 0) {
                                end = Math.max(size + end, 0);
                            }
                        }
                        start = Math.min(start, size);
                        end = Math.min(end, size);
                        target = Math.min(target, size);
                        List<Object> toCopy = new ArrayList<>();
                        for (int i = start; i < end; i++) {
                            toCopy.add(thisArray.get(i));
                        }
                        if (toCopy.isEmpty()) {
                            return thisArray;
                        }
                        // avoid concurrent modification issues
                        List<Object> list = new ArrayList<>(thisArray);
                        // copy elements over
                        int copyCount = 0;
                        for (int i = target; i < size && copyCount < toCopy.size(); i++) {
                            list.set(i, toCopy.get(copyCount++));
                        }
                        // update original array
                        thisArray.clear();
                        thisArray.addAll(list);
                        return thisArray;
                    };
                    case "keys" -> (JsCallable) (context, args) -> {
                        List<Object> thisArray = rawList(context);
                        List<Object> result = new ArrayList<>();
                        int size = thisArray.size();
                        for (int i = 0; i < size; i++) {
                            result.add(i);
                        }
                        return result;
                    };
                    case "values" -> (JsCallable) (context, args) -> rawList(context);
                    case "entries" -> (JsCallable) (context, args) -> {
                        List<Object> thisArray = rawList(context);
                        List<Object> result = new ArrayList<>();
                        int size = thisArray.size();
                        for (int i = 0; i < size; i++) {
                            List<Object> entry = new ArrayList<>();
                            entry.add(i);
                            entry.add(thisArray.get(i));
                            result.add(entry);
                        }
                        return result;
                    };
                    case "findLast" -> (JsCallable) (context, args) -> {
                        List<Object> thisArray = rawList(context);
                        int size = thisArray.size();
                        if (size == 0 || args.length == 0) {
                            return Terms.UNDEFINED;
                        }
                        JsCallable callable = toCallable(args);
                        for (int i = size - 1; i >= 0; i--) {
                            Object value = thisArray.get(i);
                            Object result = callable.call(context, value, i, thisArray);
                            if (Terms.isTruthy(result)) {
                                return value;
                            }
                        }
                        return Terms.UNDEFINED;
                    };
                    case "findLastIndex" -> (JsCallable) (context, args) -> {
                        List<Object> thisArray = rawList(context);
                        int size = thisArray.size();
                        if (size == 0 || args.length == 0) {
                            return -1;
                        }
                        JsCallable callable = toCallable(args);
                        for (int i = size - 1; i >= 0; i--) {
                            Object value = thisArray.get(i);
                            Object result = callable.call(context, value, i, thisArray);
                            if (Terms.isTruthy(result)) {
                                return i;
                            }
                        }
                        return -1;
                    };
                    case "with" -> (JsCallable) (context, args) -> {
                        List<Object> thisArray = rawList(context);
                        int size = thisArray.size();
                        if (size == 0 || args.length < 2) {
                            return thisArray;
                        }
                        int index = Terms.objectToNumber(args[0]).intValue();
                        if (index < 0) {
                            index = size + index;
                        }
                        if (index < 0 || index >= size) {
                            return thisArray; // If index is out of bounds, return a copy of the array
                        }
                        Object value = args[1];
                        // Create a copy of the original array
                        List<Object> result = new ArrayList<>(thisArray);
                        // Replace the value at the specified index
                        result.set(index, value);
                        return result;
                    };
                    case "group" -> (JsCallable) (context, args) -> {
                        List<Object> thisArray = rawList(context);
                        if (args.length == 0) {
                            return new JsObject();
                        }
                        JsCallable callable = toCallable(args);
                        Map<String, List<Object>> groups = new HashMap<>();
                        for (KeyValue kv : _this.jsEntries()) {
                            Object key = callable.call(context, kv.value, kv.index, thisArray);
                            String keyStr = key == null ? "null" : key.toString();
                            if (!groups.containsKey(keyStr)) {
                                groups.put(keyStr, new ArrayList<>());
                            }
                            groups.get(keyStr).add(kv.value);
                        }
                        JsObject result = new JsObject();
                        for (Map.Entry<String, List<Object>> entry : groups.entrySet()) {
                            result.putMember(entry.getKey(), entry.getValue());
                        }
                        return result;
                    };
                    // static ==========================================================================================
                    case "from" -> (JsCallable) (context, args) -> {
                        List<Object> results = new ArrayList<>();
                        JsCallable callable = null;
                        if (args.length > 1 && args[1] instanceof JsCallable) {
                            callable = (JsCallable) args[1];
                        }
                        JsArray array;
                        if (args[0] instanceof Map) {
                            array = toArray((Map<String, Object>) args[0]);
                        } else if (args[0] instanceof List) {
                            array = new JsArray((List<Object>) args[0]);
                        } else {
                            array = JsArray.this;
                        }
                        for (KeyValue kv : array.jsEntries()) {
                            Object result = callable == null ? kv.value : callable.call(context, kv.value, kv.index);
                            results.add(result);
                        }
                        return results;
                    };
                    case "isArray" -> (JsCallable) (context, args) -> args[0] instanceof List;
                    case "of" -> (JsCallable) (context, args) -> Arrays.asList(args);
                    default -> null;
                };
            }
        };
    }

    // =================================================================================================
    // ObjectLike implementation - delegates to internal JsObject for named properties
    // =================================================================================================

    @Override
    public Object getMember(String name) {
        // Check instance properties first (stored in delegate)
        Map<String, Object> delegateMap = delegate.toMap();
        if (delegateMap.containsKey(name)) {
            return delegateMap.get(name);
        }
        // Special case: "prototype" returns the prototype object
        if ("prototype".equals(name)) {
            return getPrototype();
        }
        // Then check prototype chain
        return getPrototype().getMember(name);
    }

    @Override
    public void putMember(String name, Object value) {
        delegate.putMember(name, value);
    }

    @Override
    public void removeMember(String name) {
        delegate.removeMember(name);
    }

    @Override
    public Map<String, Object> toMap() {
        // Arrays don't typically convert to maps, return delegate's map
        return delegate.toMap();
    }

    // =================================================================================================
    // List<Object> implementation - returns raw values (no auto-unwrap)
    // =================================================================================================

    @Override
    public int size() {
        return list.size();
    }

    @Override
    public boolean isEmpty() {
        return list.isEmpty();
    }

    @Override
    public boolean contains(Object o) {
        return list.contains(o);
    }

    @Override
    public Iterator<Object> iterator() {
        return list.iterator();
    }

    @Override
    public Object[] toArray() {
        return list.toArray();
    }

    @Override
    public <T> T[] toArray(T[] a) {
        return list.toArray(a);
    }

    @Override
    public boolean add(Object o) {
        return list.add(o);
    }

    @Override
    public boolean remove(Object o) {
        return list.remove(o);
    }

    @Override
    public boolean containsAll(Collection<?> c) {
        return list.containsAll(c);
    }

    @Override
    public boolean addAll(Collection<? extends Object> c) {
        return list.addAll(c);
    }

    @Override
    public boolean addAll(int index, Collection<? extends Object> c) {
        return list.addAll(index, c);
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        return list.removeAll(c);
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        return list.retainAll(c);
    }

    @Override
    public void clear() {
        list.clear();
    }

    @Override
    public Object get(int index) {
        return list.get(index);
    }

    @Override
    public Object set(int index, Object element) {
        return list.set(index, element);
    }

    @Override
    public void add(int index, Object element) {
        list.add(index, element);
    }

    @Override
    public Object remove(int index) {
        return list.remove(index);
    }

    @Override
    public int indexOf(Object o) {
        return list.indexOf(o);
    }

    @Override
    public int lastIndexOf(Object o) {
        return list.lastIndexOf(o);
    }

    @Override
    public ListIterator<Object> listIterator() {
        return list.listIterator();
    }

    @Override
    public ListIterator<Object> listIterator(int index) {
        return list.listIterator(index);
    }

    @Override
    public List<Object> subList(int fromIndex, int toIndex) {
        return new JsArray(new ArrayList<>(list.subList(fromIndex, toIndex)));
    }

    // =================================================================================================
    // JS internal access - returns raw values
    // =================================================================================================

    /**
     * JS internal access - returns raw value, UNDEFINED for out of bounds
     */
    public Object getElement(int index) {
        if (index < 0 || index >= list.size()) {
            return Terms.UNDEFINED;  // JS semantics
        }
        return list.get(index);  // Raw value
    }

    public void setElement(int index, Object value) {
        list.set(index, value);
    }

    public void addElement(Object value) {
        list.add(value);
    }

    public void removeElement(int index) {
        list.remove(index);
    }

    /**
     * Returns the raw internal list (for JS internal use).
     */
    public List<Object> toList() {
        return list;
    }

    // =================================================================================================
    // JS iteration support
    // =================================================================================================

    /**
     * Returns an iterable for JS for-in/for-of iteration with KeyValue pairs.
     */
    public Iterable<KeyValue> jsEntries() {
        return () -> new Iterator<>() {
            int index = 0;

            @Override
            public boolean hasNext() {
                return index < list.size();
            }

            @Override
            public KeyValue next() {
                int i = index++;
                return new KeyValue(_this, i, i + "", list.get(i));
            }
        };
    }

    // =================================================================================================
    // Helper methods
    // =================================================================================================

    @SuppressWarnings("unchecked")
    JsArray fromThis(Context context) {
        Object thisObject = context.getThisObject();
        if (thisObject instanceof JsArray arr) {
            return arr;
        }
        if (thisObject instanceof List<?> list) {
            return new JsArray((List<Object>) list);
        }
        return this;
    }

    List<Object> rawList(Context context) {
        return fromThis(context).list;
    }

    static JsCallable toCallable(Object[] args) {
        if (args.length > 0 && args[0] instanceof JsCallable callable) {
            return callable;
        }
        return (c, a) -> Terms.UNDEFINED;
    }

    static JsArray toArray(Map<String, Object> map) {
        List<Object> list = new ArrayList<>();
        if (map.containsKey("length")) {
            Object length = map.get("length");
            if (length instanceof Number) {
                int size = ((Number) length).intValue();
                for (int i = 0; i < size; i++) {
                    list.add(Terms.UNDEFINED);
                }
            }
        }
        Set<Integer> indexes = new HashSet<>();
        for (String key : map.keySet()) {
            try {
                int index = Integer.parseInt(key);
                indexes.add(index);
            } catch (Exception e) {
                // ignore
            }
        }
        for (int index : indexes) {
            list.add(index, map.get(index + ""));
        }
        return new JsArray(list);
    }

    @SuppressWarnings("unchecked")
    static void flatten(List<Object> source, List<Object> result, int depth) {
        for (Object item : source) {
            if (depth > 0 && (item instanceof List || item instanceof JsArray)) {
                List<Object> nestedList;
                if (item instanceof JsArray) {
                    nestedList = ((JsArray) item).toList();
                } else {
                    nestedList = (List<Object>) item;
                }
                flatten(nestedList, result, depth - 1);
            } else {
                result.add(item);
            }
        }
    }

    @Override
    public Object call(Context context, Object... args) {
        if (args.length == 1 && args[0] instanceof Number n) {
            int count = n.intValue();
            List<Object> list = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                list.add(null);
            }
            return list;
        }
        return Arrays.asList(args);
    }

}
