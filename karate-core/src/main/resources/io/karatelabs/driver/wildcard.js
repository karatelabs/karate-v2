(function() {
    if (window.__karateWildcard) return;

    window.__karateWildcard = {
        /**
         * Check if element is visible.
         */
        isVisible: function(el) {
            if (el.getAttribute('aria-hidden') === 'true') return false;
            var style = window.getComputedStyle(el);
            if (style.display === 'none') return false;
            if (style.visibility === 'hidden') return false;
            var rect = el.getBoundingClientRect();
            if (rect.width === 0 && rect.height === 0) return false;
            return true;
        },

        /**
         * Get visible text content, excluding hidden elements.
         */
        getVisibleText: function(el) {
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
        },

        /**
         * Check if element has a descendant that also matches the text.
         */
        hasMatchingDescendant: function(el, text, contains, selector) {
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
        },

        /**
         * Resolve a wildcard locator.
         * @param {string} tag - Tag name ('*' for any, 'button' maps to button+[role=button]+input[type=submit])
         * @param {string} text - Text to match
         * @param {number} index - 1-based index
         * @param {boolean} contains - Contains match vs exact match
         * @returns {Element|null}
         */
        resolve: function(tag, text, index, contains) {
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
                    // Skip if a descendant also matches (prefer leaf elements)
                    if (this.hasMatchingDescendant(el, text, contains, selector)) continue;
                    count++;
                    if (count === index) return el;
                }
            }
            return null;
        },

        /**
         * Map tag to CSS selector (expand button to include role=button, etc.)
         */
        getSelector: function(tag) {
            if (!tag || tag === '*') return '*';
            var map = {
                'button': 'button, [role="button"], input[type="submit"], input[type="button"]',
                'a': 'a[href], [role="link"]',
                'select': 'select, [role="combobox"], [role="listbox"]',
                'input': 'input:not([type="hidden"]), textarea, [role="textbox"]'
            };
            return map[tag] || tag;
        }
    };
})();
