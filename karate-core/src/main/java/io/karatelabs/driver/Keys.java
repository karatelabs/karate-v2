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
package io.karatelabs.driver;

import java.util.Map;

/**
 * Keyboard actions for CDP-based browser automation.
 * Uses CDP Input.dispatchKeyEvent for low-level keyboard control.
 */
public class Keys {

    // Special key constants with key codes
    public static final String NULL = "\uE000";
    public static final String CANCEL = "\uE001";
    public static final String HELP = "\uE002";
    public static final String BACKSPACE = "\uE003";
    public static final String TAB = "\uE004";
    public static final String CLEAR = "\uE005";
    public static final String RETURN = "\uE006";
    public static final String ENTER = "\uE007";
    public static final String SHIFT = "\uE008";
    public static final String CONTROL = "\uE009";
    public static final String ALT = "\uE00A";
    public static final String PAUSE = "\uE00B";
    public static final String ESCAPE = "\uE00C";
    public static final String SPACE = "\uE00D";
    public static final String PAGE_UP = "\uE00E";
    public static final String PAGE_DOWN = "\uE00F";
    public static final String END = "\uE010";
    public static final String HOME = "\uE011";
    public static final String LEFT = "\uE012";
    public static final String UP = "\uE013";
    public static final String RIGHT = "\uE014";
    public static final String DOWN = "\uE015";
    public static final String INSERT = "\uE016";
    public static final String DELETE = "\uE017";
    public static final String SEMICOLON = "\uE018";
    public static final String EQUALS = "\uE019";

    // Numpad keys
    public static final String NUMPAD0 = "\uE01A";
    public static final String NUMPAD1 = "\uE01B";
    public static final String NUMPAD2 = "\uE01C";
    public static final String NUMPAD3 = "\uE01D";
    public static final String NUMPAD4 = "\uE01E";
    public static final String NUMPAD5 = "\uE01F";
    public static final String NUMPAD6 = "\uE020";
    public static final String NUMPAD7 = "\uE021";
    public static final String NUMPAD8 = "\uE022";
    public static final String NUMPAD9 = "\uE023";
    public static final String MULTIPLY = "\uE024";
    public static final String ADD = "\uE025";
    public static final String SEPARATOR = "\uE026";
    public static final String SUBTRACT = "\uE027";
    public static final String DECIMAL = "\uE028";
    public static final String DIVIDE = "\uE029";

    // Function keys
    public static final String F1 = "\uE031";
    public static final String F2 = "\uE032";
    public static final String F3 = "\uE033";
    public static final String F4 = "\uE034";
    public static final String F5 = "\uE035";
    public static final String F6 = "\uE036";
    public static final String F7 = "\uE037";
    public static final String F8 = "\uE038";
    public static final String F9 = "\uE039";
    public static final String F10 = "\uE03A";
    public static final String F11 = "\uE03B";
    public static final String F12 = "\uE03C";

    // Meta/Command key
    public static final String META = "\uE03D";
    public static final String COMMAND = META;

    private final CdpClient cdp;
    private int modifiers = 0;

    // Modifier flags
    private static final int ALT_FLAG = 1;
    private static final int CTRL_FLAG = 2;
    private static final int META_FLAG = 4;
    private static final int SHIFT_FLAG = 8;

    Keys(CdpClient cdp) {
        this.cdp = cdp;
    }

    /**
     * Type a sequence of characters.
     *
     * @param text the text to type
     * @return this for chaining
     */
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

    /**
     * Press and release a key.
     *
     * @param key the key to press (can be a special key constant or single character)
     * @return this for chaining
     */
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

    /**
     * Hold down a modifier key.
     *
     * @param key the modifier key (SHIFT, CONTROL, ALT, META)
     * @return this for chaining
     */
    public Keys down(String key) {
        int flag = getModifierFlag(key);
        if (flag > 0) {
            modifiers |= flag;
            dispatchKeyDown(getKeyInfo(key));
        }
        return this;
    }

    /**
     * Release a modifier key.
     *
     * @param key the modifier key (SHIFT, CONTROL, ALT, META)
     * @return this for chaining
     */
    public Keys up(String key) {
        int flag = getModifierFlag(key);
        if (flag > 0) {
            modifiers &= ~flag;
            dispatchKeyUp(getKeyInfo(key));
        }
        return this;
    }

    /**
     * Press a key combination (e.g., Ctrl+A, Shift+Tab).
     *
     * @param modifierKeys the modifier keys to hold
     * @param key the key to press
     * @return this for chaining
     */
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

    /**
     * Press Ctrl+key combination.
     */
    public Keys ctrl(String key) {
        return combo(new String[]{CONTROL}, key);
    }

    /**
     * Press Alt+key combination.
     */
    public Keys alt(String key) {
        return combo(new String[]{ALT}, key);
    }

    /**
     * Press Shift+key combination.
     */
    public Keys shift(String key) {
        return combo(new String[]{SHIFT}, key);
    }

    /**
     * Press Meta/Command+key combination.
     */
    public Keys meta(String key) {
        return combo(new String[]{META}, key);
    }

    private void typeChar(char c) {
        KeyInfo info = getKeyInfoForChar(c);
        dispatchKeyDown(info);
        dispatchKeyPress(info);
        dispatchKeyUp(info);
    }

    private void pressSpecialKey(String key) {
        KeyInfo info = getKeyInfo(key);
        dispatchKeyDown(info);
        dispatchKeyUp(info);
    }

    private void dispatchKeyDown(KeyInfo info) {
        CdpMessage message = cdp.method("Input.dispatchKeyEvent")
                .param("type", "keyDown")
                .param("modifiers", modifiers);

        if (info.key != null) message.param("key", info.key);
        if (info.code != null) message.param("code", info.code);
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

    private void dispatchKeyPress(KeyInfo info) {
        if (info.text == null || info.text.isEmpty()) {
            return;
        }
        cdp.method("Input.dispatchKeyEvent")
                .param("type", "char")
                .param("modifiers", modifiers)
                .param("text", info.text)
                .send();
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
            // For other characters, just use the character
            info.windowsVirtualKeyCode = c;
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
            case '\uE006', '\uE007' -> { info.key = "Enter"; info.code = "Enter"; info.windowsVirtualKeyCode = 13; }
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
