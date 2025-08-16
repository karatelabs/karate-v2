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
package io.karatelabs.common;

import de.siegmar.fastcsv.reader.CsvReader;
import de.siegmar.fastcsv.reader.CsvRecord;
import de.siegmar.fastcsv.writer.CsvWriter;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.*;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;

public class FileUtils {

    private FileUtils() {
        // only static methods
    }

    public static final File WORKING_DIR = new File("").getAbsoluteFile();

    public static String toString(File file) {
        try {
            return toString(new FileInputStream(file));
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    public static String toString(URL url) {
        return toString(toBytes(url));
    }

    public static byte[] toBytes(URL url) {
        try (InputStream is = url.openStream()) {
            ByteArrayOutputStream os = FileUtils.toByteStream(is);
            return os.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static String toString(InputStream is) {
        try {
            return toByteStream(is).toString(UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static byte[] toBytes(File file) {
        try {
            return toBytes(new FileInputStream(file));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static byte[] toBytes(InputStream is) {
        return toByteStream(is).toByteArray();
    }

    public static ByteArrayOutputStream toByteStream(InputStream is) {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int length;
        try {
            while ((length = is.read(buffer)) != -1) {
                result.write(buffer, 0, length);
            }
            return result;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static String toString(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        return new String(bytes, UTF_8);
    }

    public static byte[] toBytes(String string) {
        if (string == null) {
            return null;
        }
        return string.getBytes(UTF_8);
    }

    public static void copy(File src, File dest) {
        try {
            writeToFile(dest, toBytes(new FileInputStream(src)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void writeToFile(File file, byte[] data) {
        try {
            File parent = file.getAbsoluteFile().getParentFile();
            if (!parent.exists()) {
                parent.mkdirs();
            }
            // try with resources, so will be closed automatically
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(data);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static Object fromYaml(String raw) {
        LoaderOptions options = new LoaderOptions();
        options.setCodePointLimit(8 * 1024 * 1024); // 8MB
        Yaml yaml = new Yaml(new SafeConstructor(options));
        return yaml.load(raw);
    }

    public static List<Map<String, Object>> fromCsv(String raw) {
        CsvReader<CsvRecord> reader = CsvReader.builder().ofCsvRecord(raw);
        List<String> header = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        try {
            boolean first = true;
            for (CsvRecord row : reader) {
                if (first) {
                    for (String field : row.getFields()) {
                        header.add(field.replace("\ufeff", "")); // remove byte order mark
                    }
                    first = false;
                } else {
                    int count = header.size();
                    Map<String, Object> map = new LinkedHashMap<>(count);
                    for (int i = 0; i < count; i++) {
                        map.put(header.get(i), row.getField(i));
                    }
                    rows.add(map);
                }
            }
            return rows;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static String toCsv(List<Map<String, Object>> list) {
        StringWriter sw = new StringWriter();
        CsvWriter writer = CsvWriter.builder().build(sw);
        // header row
        if (!list.isEmpty()) {
            writer.writeRecord(list.get(0).keySet());
        }
        for (Map<String, Object> map : list) {
            List<String> row = new ArrayList<>(map.size());
            for (Object value : map.values()) {
                row.add(value == null ? null : value.toString());
            }
            writer.writeRecord(row);
        }
        return sw.toString();
    }

    public static void writeToFile(File file, String data) {
        writeToFile(file, data.getBytes(UTF_8));
    }

    public static InputStream toInputStream(String text) {
        return new ByteArrayInputStream(text.getBytes(UTF_8));
    }


}
