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
 * Mouse actions for CDP-based browser automation.
 * Uses CDP Input.dispatchMouseEvent for low-level mouse control.
 * Coordinates are viewport-relative.
 */
public class Mouse {

    private final CdpClient cdp;
    private double x;
    private double y;

    Mouse(CdpClient cdp) {
        this.cdp = cdp;
        this.x = 0;
        this.y = 0;
    }

    Mouse(CdpClient cdp, double x, double y) {
        this.cdp = cdp;
        this.x = x;
        this.y = y;
    }

    /**
     * Move mouse to specified coordinates.
     *
     * @param x the x coordinate (viewport-relative)
     * @param y the y coordinate (viewport-relative)
     * @return this for chaining
     */
    public Mouse move(double x, double y) {
        this.x = x;
        this.y = y;
        dispatchEvent("mouseMoved");
        return this;
    }

    /**
     * Move mouse by offset from current position.
     *
     * @param dx the x offset
     * @param dy the y offset
     * @return this for chaining
     */
    public Mouse offset(double dx, double dy) {
        return move(this.x + dx, this.y + dy);
    }

    /**
     * Click at the current position.
     *
     * @return this for chaining
     */
    public Mouse click() {
        down();
        up();
        return this;
    }

    /**
     * Double-click at the current position.
     *
     * @return this for chaining
     */
    public Mouse doubleClick() {
        click();
        dispatchEvent("mousePressed", Map.of("clickCount", 2));
        dispatchEvent("mouseReleased", Map.of("clickCount", 2));
        return this;
    }

    /**
     * Right-click at the current position.
     *
     * @return this for chaining
     */
    public Mouse rightClick() {
        dispatchEvent("mousePressed", Map.of("button", "right"));
        dispatchEvent("mouseReleased", Map.of("button", "right"));
        return this;
    }

    /**
     * Press the mouse button down at the current position.
     *
     * @return this for chaining
     */
    public Mouse down() {
        dispatchEvent("mousePressed");
        return this;
    }

    /**
     * Release the mouse button at the current position.
     *
     * @return this for chaining
     */
    public Mouse up() {
        dispatchEvent("mouseReleased");
        return this;
    }

    /**
     * Perform a mouse wheel scroll.
     *
     * @param deltaX horizontal scroll amount (positive = right)
     * @param deltaY vertical scroll amount (positive = down)
     * @return this for chaining
     */
    public Mouse wheel(double deltaX, double deltaY) {
        cdp.method("Input.dispatchMouseEvent")
                .param("type", "mouseWheel")
                .param("x", x)
                .param("y", y)
                .param("deltaX", deltaX)
                .param("deltaY", deltaY)
                .send();
        return this;
    }

    /**
     * Scroll down at the current position.
     *
     * @param amount the scroll amount in pixels
     * @return this for chaining
     */
    public Mouse scrollDown(double amount) {
        return wheel(0, amount);
    }

    /**
     * Scroll up at the current position.
     *
     * @param amount the scroll amount in pixels
     * @return this for chaining
     */
    public Mouse scrollUp(double amount) {
        return wheel(0, -amount);
    }

    /**
     * Scroll right at the current position.
     *
     * @param amount the scroll amount in pixels
     * @return this for chaining
     */
    public Mouse scrollRight(double amount) {
        return wheel(amount, 0);
    }

    /**
     * Scroll left at the current position.
     *
     * @param amount the scroll amount in pixels
     * @return this for chaining
     */
    public Mouse scrollLeft(double amount) {
        return wheel(-amount, 0);
    }

    /**
     * Drag from current position to target position.
     *
     * @param targetX the target x coordinate
     * @param targetY the target y coordinate
     * @return this for chaining
     */
    public Mouse dragTo(double targetX, double targetY) {
        down();
        move(targetX, targetY);
        up();
        return this;
    }

    /**
     * Get current x coordinate.
     */
    public double getX() {
        return x;
    }

    /**
     * Get current y coordinate.
     */
    public double getY() {
        return y;
    }

    private void dispatchEvent(String type) {
        dispatchEvent(type, Map.of());
    }

    private void dispatchEvent(String type, Map<String, Object> extra) {
        CdpMessage message = cdp.method("Input.dispatchMouseEvent")
                .param("type", type)
                .param("x", x)
                .param("y", y);

        // Default button is left
        if (!extra.containsKey("button")) {
            message.param("button", "left");
        }

        // Add click count for press/release
        if ((type.equals("mousePressed") || type.equals("mouseReleased")) && !extra.containsKey("clickCount")) {
            message.param("clickCount", 1);
        }

        // Add any extra parameters
        extra.forEach(message::param);

        message.send();
    }

}
