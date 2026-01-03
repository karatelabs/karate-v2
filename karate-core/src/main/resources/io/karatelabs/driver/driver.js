/**
 * Karate JS Runtime - Browser-side utilities for element discovery and interaction
 * Namespace: window.__kjs
 */
(function() {
    // Check if resolve already exists (may be extended by agent-look.js)
    if (window.__kjs && window.__kjs.resolve) return;

    // Initialize or extend __kjs
    var kjs = window.__kjs || (window.__kjs = {});

    // ==================== Logging ====================
    // LLMs can call __kjs.getLogs() to see what happened

    if (!kjs._logs) kjs._logs = [];

    kjs.log = function(msg, data) {
        var entry = {msg: msg, time: Date.now()};
        if (data) entry.data = data;
        this._logs.push(entry);
        if (this._logs.length > 100) this._logs.shift();
    };

    kjs.getLogs = function() {
        return this._logs;
    };

    kjs.clearLogs = function() {
        this._logs = [];
    };

    // ==================== Shared Utilities ====================

    kjs.isVisible = function(el) {
        if (!el) return false;
        if (el.getAttribute('aria-hidden') === 'true') return false;
        var style = window.getComputedStyle(el);
        if (style.display === 'none') return false;
        if (style.visibility === 'hidden') return false;
        var rect = el.getBoundingClientRect();
        if (rect.width === 0 && rect.height === 0) return false;
        return true;
    };

    kjs.getVisibleText = function(el) {
        if (!el) return '';
        var text = '';
        var walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT, null, false);
        var node;
        while ((node = walker.nextNode())) {
            var parent = node.parentElement;
            var hidden = false;
            while (parent && parent !== el) {
                if (parent.getAttribute('aria-hidden') === 'true') { hidden = true; break; }
                var style = window.getComputedStyle(parent);
                if (style.display === 'none' || style.visibility === 'hidden') { hidden = true; break; }
                parent = parent.parentElement;
            }
            if (!hidden) text += node.textContent;
        }
        return text.trim().replace(/\s+/g, ' ');
    };

    kjs.hasMatchingDescendant = function(el, text, contains, selector) {
        var descendants = el.querySelectorAll(selector);
        for (var i = 0; i < descendants.length; i++) {
            var desc = descendants[i];
            if (!this.isVisible(desc)) continue;
            var descText = this.getVisibleText(desc);
            if (!descText) continue;
            var matches = contains
                ? descText.indexOf(text) !== -1
                : descText === text;
            if (matches) return true;
        }
        return false;
    };

    kjs.getSelector = function(tag) {
        if (!tag || tag === '*') return '*';
        var map = {
            'button': 'button, [role="button"], input[type="submit"], input[type="button"]',
            'a': 'a[href], [role="link"]',
            'select': 'select, [role="combobox"], [role="listbox"]',
            'input': 'input:not([type="hidden"]), textarea, [role="textbox"]'
        };
        return map[tag] || tag;
    };

    // ==================== Wildcard Resolver ====================
    // Public API: __kjs.resolve(tag, text, index, contains)

    kjs.resolve = function(tag, text, index, contains) {
        var selector = this.getSelector(tag);
        var candidates = document.querySelectorAll(selector);
        var count = 0;
        for (var i = 0; i < candidates.length; i++) {
            var el = candidates[i];
            if (!this.isVisible(el)) continue;
            var elText = this.getVisibleText(el);
            if (!elText) continue;
            var matches = contains
                ? elText.indexOf(text) !== -1
                : elText === text;
            if (matches) {
                if (this.hasMatchingDescendant(el, text, contains, selector)) continue;
                count++;
                if (count === index) return el;
            }
        }
        this.log('Wildcard not found', {tag: tag, text: text, index: index, contains: contains, found: count});
        return null;
    };
})();
