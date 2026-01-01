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
package io.karatelabs.driver.cdp;

import io.karatelabs.driver.Keys;

/**
 * CDP-based implementation of keyboard actions.
 * Uses CDP Input.dispatchKeyEvent for low-level keyboard control.
 */
public class CdpKeys implements Keys {

    private final CdpClient cdp;
    private int modifiers = 0;

    // Modifier flags
    private static final int ALT_FLAG = 1;
    private static final int CTRL_FLAG = 2;
    private static final int META_FLAG = 4;
    private static final int SHIFT_FLAG = 8;

    public CdpKeys(CdpClient cdp) {
        this.cdp = cdp;
    }

    @Override
    public Keys type(String text) {
        for (char c : text.toCharArray()) {
            String s = String.valueOf(c);
            if (isSpecialKey(s)) {
                pressSpecialKey(s);
            } else {
                typeChar(c);
            }
        }
        return this;
    }

    @Override
    public Keys press(String key) {
        if (isSpecialKey(key)) {
            pressSpecialKey(key);
        } else {
            for (char c : key.toCharArray()) {
                typeChar(c);
            }
        }
        return this;
    }

    @Override
    public Keys down(String key) {
        int flag = getModifierFlag(key);
        if (flag > 0) {
            modifiers |= flag;
            dispatchRawKeyDown(getKeyInfo(key));
        }
        return this;
    }

    @Override
    public Keys up(String key) {
        int flag = getModifierFlag(key);
        if (flag > 0) {
            modifiers &= ~flag;
            dispatchKeyUp(getKeyInfo(key));
        }
        return this;
    }

    @Override
    public Keys combo(String[] modifierKeys, String key) {
        // Press modifiers
        for (String mod : modifierKeys) {
            down(mod);
        }

        // Press the key
        press(key);

        // Release modifiers in reverse order
        for (int i = modifierKeys.length - 1; i >= 0; i--) {
            up(modifierKeys[i]);
        }

        return this;
    }

    @Override
    public Keys ctrl(String key) {
        return combo(new String[]{CONTROL}, key);
    }

    @Override
    public Keys alt(String key) {
        return combo(new String[]{ALT}, key);
    }

    @Override
    public Keys shift(String key) {
        return combo(new String[]{SHIFT}, key);
    }

    @Override
    public Keys meta(String key) {
        return combo(new String[]{META}, key);
    }

    private void typeChar(char c) {
        KeyInfo info = getKeyInfoForChar(c);
        dispatchRawKeyDown(info);
        dispatchChar(info);
        dispatchKeyUp(info);
    }

    private void pressSpecialKey(String key) {
        KeyInfo info = getKeyInfo(key);
        // Special keys need rawKeyDown + char (with proper text) + keyUp
        dispatchRawKeyDown(info);
        // Enter key needs char event with \r to trigger form submit
        if (info.text != null && !info.text.isEmpty()) {
            dispatchChar(info);
        }
        dispatchKeyUp(info);
    }

    private void dispatchRawKeyDown(KeyInfo info) {
        CdpMessage message = cdp.method("Input.dispatchKeyEvent")
                .param("type", "rawKeyDown")  // rawKeyDown works better than keyDown
                .param("modifiers", modifiers);

        if (info.key != null) message.param("key", info.key);
        if (info.code != null) message.param("code", info.code);
        if (info.text != null) message.param("text", info.text);
        if (info.windowsVirtualKeyCode > 0) message.param("windowsVirtualKeyCode", info.windowsVirtualKeyCode);
        if (info.nativeVirtualKeyCode > 0) message.param("nativeVirtualKeyCode", info.nativeVirtualKeyCode);

        message.send();
    }

    private void dispatchKeyUp(KeyInfo info) {
        CdpMessage message = cdp.method("Input.dispatchKeyEvent")
                .param("type", "keyUp")
                .param("modifiers", modifiers);

        if (info.key != null) message.param("key", info.key);
        if (info.code != null) message.param("code", info.code);
        if (info.windowsVirtualKeyCode > 0) message.param("windowsVirtualKeyCode", info.windowsVirtualKeyCode);
        if (info.nativeVirtualKeyCode > 0) message.param("nativeVirtualKeyCode", info.nativeVirtualKeyCode);

        message.send();
    }

    private void dispatchChar(KeyInfo info) {
        if (info.text == null || info.text.isEmpty()) {
            return;
        }
        CdpMessage message = cdp.method("Input.dispatchKeyEvent")
                .param("type", "char")
                .param("modifiers", modifiers)
                .param("text", info.text);
        if (info.windowsVirtualKeyCode > 0) message.param("windowsVirtualKeyCode", info.windowsVirtualKeyCode);
        message.send();
    }

    private boolean isSpecialKey(String key) {
        if (key == null || key.isEmpty()) return false;
        char c = key.charAt(0);
        return c >= '\uE000' && c <= '\uE03D';
    }

    private int getModifierFlag(String key) {
        if (key == null || key.isEmpty()) return 0;
        return switch (key) {
            case SHIFT -> SHIFT_FLAG;
            case CONTROL -> CTRL_FLAG;
            case ALT -> ALT_FLAG;
            case META -> META_FLAG;  // COMMAND is alias for META
            default -> 0;
        };
    }

    private KeyInfo getKeyInfoForChar(char c) {
        KeyInfo info = new KeyInfo();
        info.text = String.valueOf(c);
        info.key = info.text;

        // Determine key code
        if (c >= 'a' && c <= 'z') {
            info.code = "Key" + Character.toUpperCase(c);
            info.windowsVirtualKeyCode = Character.toUpperCase(c);
        } else if (c >= 'A' && c <= 'Z') {
            info.code = "Key" + c;
            info.windowsVirtualKeyCode = c;
        } else if (c >= '0' && c <= '9') {
            info.code = "Digit" + c;
            info.windowsVirtualKeyCode = c;
        } else if (c == ' ') {
            info.key = " ";
            info.code = "Space";
            info.windowsVirtualKeyCode = 32;
        } else {
            // Punctuation and special characters - use proper key codes
            switch (c) {
                case '.' -> { info.code = "Period"; info.windowsVirtualKeyCode = 190; }
                case ',' -> { info.code = "Comma"; info.windowsVirtualKeyCode = 188; }
                case ';' -> { info.code = "Semicolon"; info.windowsVirtualKeyCode = 186; }
                case '\'' -> { info.code = "Quote"; info.windowsVirtualKeyCode = 222; }
                case '[' -> { info.code = "BracketLeft"; info.windowsVirtualKeyCode = 219; }
                case ']' -> { info.code = "BracketRight"; info.windowsVirtualKeyCode = 221; }
                case '\\' -> { info.code = "Backslash"; info.windowsVirtualKeyCode = 220; }
                case '/' -> { info.code = "Slash"; info.windowsVirtualKeyCode = 191; }
                case '`' -> { info.code = "Backquote"; info.windowsVirtualKeyCode = 192; }
                case '-' -> { info.code = "Minus"; info.windowsVirtualKeyCode = 189; }
                case '=' -> { info.code = "Equal"; info.windowsVirtualKeyCode = 187; }
                default -> info.windowsVirtualKeyCode = 0; // Let text handle it
            }
        }

        return info;
    }

    private KeyInfo getKeyInfo(String specialKey) {
        KeyInfo info = new KeyInfo();
        info.text = "";

        if (specialKey == null || specialKey.isEmpty()) {
            return info;
        }

        char c = specialKey.charAt(0);
        switch (c) {
            case '\uE003' -> { info.key = "Backspace"; info.code = "Backspace"; info.windowsVirtualKeyCode = 8; }
            case '\uE004' -> { info.key = "Tab"; info.code = "Tab"; info.windowsVirtualKeyCode = 9; }
            case '\uE006', '\uE007' -> { info.key = "Enter"; info.code = "Enter"; info.windowsVirtualKeyCode = 13; info.text = "\r"; }
            case '\uE008' -> { info.key = "Shift"; info.code = "ShiftLeft"; info.windowsVirtualKeyCode = 16; }
            case '\uE009' -> { info.key = "Control"; info.code = "ControlLeft"; info.windowsVirtualKeyCode = 17; }
            case '\uE00A' -> { info.key = "Alt"; info.code = "AltLeft"; info.windowsVirtualKeyCode = 18; }
            case '\uE00C' -> { info.key = "Escape"; info.code = "Escape"; info.windowsVirtualKeyCode = 27; }
            case '\uE00D' -> { info.key = " "; info.code = "Space"; info.windowsVirtualKeyCode = 32; info.text = " "; }
            case '\uE00E' -> { info.key = "PageUp"; info.code = "PageUp"; info.windowsVirtualKeyCode = 33; }
            case '\uE00F' -> { info.key = "PageDown"; info.code = "PageDown"; info.windowsVirtualKeyCode = 34; }
            case '\uE010' -> { info.key = "End"; info.code = "End"; info.windowsVirtualKeyCode = 35; }
            case '\uE011' -> { info.key = "Home"; info.code = "Home"; info.windowsVirtualKeyCode = 36; }
            case '\uE012' -> { info.key = "ArrowLeft"; info.code = "ArrowLeft"; info.windowsVirtualKeyCode = 37; }
            case '\uE013' -> { info.key = "ArrowUp"; info.code = "ArrowUp"; info.windowsVirtualKeyCode = 38; }
            case '\uE014' -> { info.key = "ArrowRight"; info.code = "ArrowRight"; info.windowsVirtualKeyCode = 39; }
            case '\uE015' -> { info.key = "ArrowDown"; info.code = "ArrowDown"; info.windowsVirtualKeyCode = 40; }
            case '\uE016' -> { info.key = "Insert"; info.code = "Insert"; info.windowsVirtualKeyCode = 45; }
            case '\uE017' -> { info.key = "Delete"; info.code = "Delete"; info.windowsVirtualKeyCode = 46; }
            case '\uE031' -> { info.key = "F1"; info.code = "F1"; info.windowsVirtualKeyCode = 112; }
            case '\uE032' -> { info.key = "F2"; info.code = "F2"; info.windowsVirtualKeyCode = 113; }
            case '\uE033' -> { info.key = "F3"; info.code = "F3"; info.windowsVirtualKeyCode = 114; }
            case '\uE034' -> { info.key = "F4"; info.code = "F4"; info.windowsVirtualKeyCode = 115; }
            case '\uE035' -> { info.key = "F5"; info.code = "F5"; info.windowsVirtualKeyCode = 116; }
            case '\uE036' -> { info.key = "F6"; info.code = "F6"; info.windowsVirtualKeyCode = 117; }
            case '\uE037' -> { info.key = "F7"; info.code = "F7"; info.windowsVirtualKeyCode = 118; }
            case '\uE038' -> { info.key = "F8"; info.code = "F8"; info.windowsVirtualKeyCode = 119; }
            case '\uE039' -> { info.key = "F9"; info.code = "F9"; info.windowsVirtualKeyCode = 120; }
            case '\uE03A' -> { info.key = "F10"; info.code = "F10"; info.windowsVirtualKeyCode = 121; }
            case '\uE03B' -> { info.key = "F11"; info.code = "F11"; info.windowsVirtualKeyCode = 122; }
            case '\uE03C' -> { info.key = "F12"; info.code = "F12"; info.windowsVirtualKeyCode = 123; }
            case '\uE03D' -> { info.key = "Meta"; info.code = "MetaLeft"; info.windowsVirtualKeyCode = 91; }
            default -> { info.key = ""; info.code = ""; }
        }

        return info;
    }

    private static class KeyInfo {
        String key;
        String code;
        String text;
        int windowsVirtualKeyCode;
        int nativeVirtualKeyCode;
    }

}
