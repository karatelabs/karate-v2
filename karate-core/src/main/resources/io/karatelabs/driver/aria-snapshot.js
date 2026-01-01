/**
 * aria-snapshot.js - Browser-side element discovery for LLM agents
 *
 * Provides window.__karate object with:
 * - getInteractables(options) - Get array of interactable elements
 * - getActions() - Get actions map for current refs
 * - resolveRef(ref) - Get DOM element for ref
 * - log(type, message) - Log to sidebar (if enabled)
 */
(function() {
    // Sidebar UI (injected directly, no extension needed)
    function initSidebar() {
        if (document.getElementById('karate-sidebar')) return;

        var style = document.createElement('style');
        style.textContent = '\
            #karate-sidebar { position:fixed; top:10px; right:10px; width:280px; height:300px; \
                background:rgba(26,26,46,0.95); color:#eee; font-family:monospace; font-size:11px; \
                z-index:2147483647; display:flex; flex-direction:column; \
                box-shadow:0 4px 20px rgba(0,0,0,0.4); border-radius:8px; \
                resize:both; overflow:hidden; min-width:200px; min-height:100px; } \
            #karate-sidebar.collapsed { height:auto !important; min-height:auto; resize:none; } \
            #karate-sidebar.collapsed #karate-log { display:none; } \
            #karate-header { padding:8px 10px; background:#16213e; font-weight:bold; \
                display:flex; justify-content:space-between; align-items:center; \
                border-bottom:1px solid #0f3460; cursor:move; border-radius:8px 8px 0 0; \
                user-select:none; } \
            #karate-sidebar.collapsed #karate-header { border-radius:8px; } \
            #karate-title { color:#e94560; } \
            #karate-controls { display:flex; gap:4px; } \
            #karate-controls button { background:#0f3460; color:#eee; border:none; \
                width:20px; height:20px; border-radius:4px; cursor:pointer; font-size:10px; } \
            #karate-controls button:hover { background:#e94560; } \
            #karate-log { flex:1; overflow-y:auto; padding:8px; } \
            .karate-entry { margin-bottom:6px; padding:6px; background:#16213e; \
                border-radius:4px; border-left:3px solid #0f3460; } \
            .karate-entry.cmd { border-left-color:#e94560; } \
            .karate-entry.result { border-left-color:#4ecca3; } \
            .karate-entry.error { border-left-color:#ff6b6b; background:#2a1a1a; } \
            .karate-time { color:#666; font-size:10px; } \
            .karate-entry code { background:#0f3460; padding:1px 4px; border-radius:2px; color:#4ecca3; } \
            .karate-highlight { outline:3px solid #e94560 !important; outline-offset:2px; }';
        document.head.appendChild(style);

        var sidebar = document.createElement('div');
        sidebar.id = 'karate-sidebar';
        sidebar.innerHTML = '<div id="karate-header">' +
            '<span id="karate-title">Karate</span>' +
            '<div id="karate-controls">' +
            '<button id="karate-collapse" title="Collapse">_</button>' +
            '<button id="karate-clear" title="Clear">C</button>' +
            '</div></div><div id="karate-log"></div>';
        document.body.appendChild(sidebar);

        // Clear button
        document.getElementById('karate-clear').onclick = function(e) {
            e.stopPropagation();
            document.getElementById('karate-log').innerHTML = '';
        };

        // Collapse/expand button
        document.getElementById('karate-collapse').onclick = function(e) {
            e.stopPropagation();
            sidebar.classList.toggle('collapsed');
            this.textContent = sidebar.classList.contains('collapsed') ? '+' : '_';
        };

        // Draggable header
        var header = document.getElementById('karate-header');
        var isDragging = false, startX, startY, startLeft, startTop;

        header.onmousedown = function(e) {
            if (e.target.tagName === 'BUTTON') return;
            isDragging = true;
            startX = e.clientX;
            startY = e.clientY;
            var rect = sidebar.getBoundingClientRect();
            startLeft = rect.left;
            startTop = rect.top;
            e.preventDefault();
        };

        document.onmousemove = function(e) {
            if (!isDragging) return;
            var dx = e.clientX - startX;
            var dy = e.clientY - startY;
            sidebar.style.left = (startLeft + dx) + 'px';
            sidebar.style.top = (startTop + dy) + 'px';
            sidebar.style.right = 'auto';
        };

        document.onmouseup = function() {
            isDragging = false;
        };
    }

    window.__karate = {
        refs: {},
        seq: 1,
        sidebarEnabled: false,  // Set to true via --sidebar flag

        /**
         * Get all interactable elements.
         * @param {Object} options - Optional filtering options
         * @param {string} options.scope - CSS selector to scope search
         * @param {boolean} options.viewport - If false, include off-screen elements (default: true)
         * @returns {string[]} Array of "role:name[ref]" strings
         */
        getInteractables: function(options) {
            options = options || {};
            this.refs = {};
            this.seq = 1;

            var results = [];
            var selector = [
                'button',
                'a[href]',
                'input:not([type="hidden"])',
                'select',
                'textarea',
                '[role="button"]',
                '[role="link"]',
                '[role="checkbox"]',
                '[role="radio"]',
                '[role="combobox"]',
                '[role="textbox"]',
                '[role="menuitem"]',
                '[role="tab"]',
                '[tabindex]:not([tabindex="-1"])'
            ].join(', ');

            var container = document;
            if (options.scope) {
                container = document.querySelector(options.scope);
                if (!container) return results;
            }

            var elements = container.querySelectorAll(selector);

            for (var i = 0; i < elements.length; i++) {
                var el = elements[i];

                // Skip elements inside the karate sidebar
                if (el.closest('#karate-sidebar')) continue;

                // Skip hidden elements
                if (!this.isVisible(el)) continue;

                // Skip disabled elements (they can't be interacted with)
                if (el.disabled) continue;

                // Viewport check (default: true)
                if (options.viewport !== false) {
                    if (!this.isInViewport(el)) continue;
                }

                var ref = 'e' + this.seq++;
                this.refs[ref] = el;

                var role = this.getRole(el);
                var name = this.getName(el);

                // Escape special characters in name
                name = name.replace(/[\[\]]/g, '').replace(/:/g, '-');

                results.push(role + ':' + name + '[' + ref + ']');
            }

            return results;
        },

        /**
         * Get available actions for each current ref.
         * @returns {Object} Map of ref -> array of action names
         */
        getActions: function() {
            var actions = {};
            for (var ref in this.refs) {
                var el = this.refs[ref];
                var role = this.getRole(el);
                actions[ref] = this.getActionsForRole(role, el);
            }
            return actions;
        },

        /**
         * Resolve a ref to its DOM element.
         * @param {string} ref - Element reference (e.g., "e1")
         * @returns {Element|null} DOM element or null if stale
         */
        resolveRef: function(ref) {
            var el = this.refs[ref];
            if (!el) return null;

            // Check if element is still in DOM
            if (!document.contains(el)) {
                delete this.refs[ref];
                return null;
            }

            return el;
        },

        /**
         * Check if element is visible.
         */
        isVisible: function(el) {
            // Check aria-hidden
            if (el.getAttribute('aria-hidden') === 'true') return false;

            // Check display/visibility
            var style = window.getComputedStyle(el);
            if (style.display === 'none') return false;
            if (style.visibility === 'hidden') return false;

            // Check dimensions
            var rect = el.getBoundingClientRect();
            if (rect.width === 0 && rect.height === 0) return false;

            return true;
        },

        /**
         * Check if element is in viewport.
         */
        isInViewport: function(el) {
            var rect = el.getBoundingClientRect();
            return (
                rect.bottom >= 0 &&
                rect.top <= window.innerHeight &&
                rect.right >= 0 &&
                rect.left <= window.innerWidth
            );
        },

        /**
         * Get ARIA role for element.
         */
        getRole: function(el) {
            // Explicit role takes precedence
            var role = el.getAttribute('role');
            if (role) return role;

            // Implicit role based on tag
            var tag = el.tagName.toLowerCase();
            var type = el.type ? el.type.toLowerCase() : '';

            if (tag === 'button') return 'button';
            if (tag === 'a' && el.hasAttribute('href')) return 'link';
            if (tag === 'select') return 'combobox';
            if (tag === 'textarea') return 'textbox';
            if (tag === 'input') {
                if (type === 'checkbox') return 'checkbox';
                if (type === 'radio') return 'radio';
                if (type === 'submit' || type === 'button') return 'button';
                if (type === 'email' || type === 'password' || type === 'text' ||
                    type === 'search' || type === 'tel' || type === 'url' || type === 'number') {
                    return 'textbox';
                }
                return 'textbox'; // Default for inputs
            }

            return 'generic';
        },

        /**
         * Get accessible name for element.
         */
        getName: function(el) {
            // aria-label
            var label = el.getAttribute('aria-label');
            if (label) return label.trim();

            // aria-labelledby
            var labelledBy = el.getAttribute('aria-labelledby');
            if (labelledBy) {
                var labels = labelledBy.split(/\s+/).map(function(id) {
                    var labelEl = document.getElementById(id);
                    return labelEl ? labelEl.textContent : '';
                });
                var combined = labels.join(' ').trim();
                if (combined) return combined;
            }

            // Associated label element
            if (el.id) {
                var labelFor = document.querySelector('label[for="' + el.id + '"]');
                if (labelFor) return labelFor.textContent.trim();
            }

            // Wrapped in label
            var parentLabel = el.closest('label');
            if (parentLabel) {
                // Get label text excluding the input itself
                var clone = parentLabel.cloneNode(true);
                var inputs = clone.querySelectorAll('input, select, textarea');
                inputs.forEach(function(input) { input.remove(); });
                var labelText = clone.textContent.trim();
                if (labelText) return labelText;
            }

            // Placeholder
            if (el.placeholder) return el.placeholder;

            // alt for images/image buttons
            if (el.alt) return el.alt;

            // title
            if (el.title) return el.title;

            // value for buttons
            if (el.value && (el.type === 'submit' || el.type === 'button')) {
                return el.value;
            }

            // Text content (for buttons, links)
            var text = el.textContent || '';
            text = text.trim().replace(/\s+/g, ' ');
            if (text.length > 50) text = text.substring(0, 47) + '...';
            if (text) return text;

            return '(unnamed)';
        },

        /**
         * Get available actions for a role.
         */
        getActionsForRole: function(role, el) {
            switch (role) {
                case 'button':
                case 'link':
                case 'menuitem':
                case 'tab':
                    return ['click'];
                case 'textbox':
                    return ['input', 'clear', 'focus'];
                case 'checkbox':
                    return ['click', 'check', 'uncheck'];
                case 'radio':
                    return ['click'];
                case 'combobox':
                    return ['select', 'input', 'click'];
                case 'listbox':
                    return ['select'];
                default:
                    return ['click'];
            }
        },

        /**
         * Log a message to the sidebar.
         * @param {string} type - 'cmd', 'result', or 'error'
         * @param {string} message - The message (can include <code> tags)
         * @param {string} ref - Optional ref to highlight
         */
        log: function(type, message, ref) {
            if (!this.sidebarEnabled) return;
            initSidebar();

            var logEl = document.getElementById('karate-log');
            if (!logEl) return;

            var entry = document.createElement('div');
            entry.className = 'karate-entry ' + type;

            var time = new Date().toLocaleTimeString();
            entry.innerHTML = '<div class="karate-time">' + time + '</div>' +
                '<div>' + message + '</div>';

            logEl.appendChild(entry);
            logEl.scrollTop = logEl.scrollHeight;

            // Highlight element if ref provided
            if (ref && this.refs[ref]) {
                var el = this.refs[ref];
                el.classList.add('karate-highlight');
                setTimeout(function() { el.classList.remove('karate-highlight'); }, 1000);
            }
        },

        /**
         * Request user intervention.
         * Shows alert in sidebar with message, textbox for response, and Resume button.
         * Sets __karate.handoffPending = true, cleared when user clicks Resume.
         * @param {string} message - Message to show user
         */
        handoff: function(message) {
            this.handoffPending = true;
            this.handoffResponse = '';
            initSidebar();

            var sidebar = document.getElementById('karate-sidebar');
            if (!sidebar) return;

            // Expand sidebar if collapsed
            sidebar.classList.remove('collapsed');
            var collapseBtn = document.getElementById('karate-collapse');
            if (collapseBtn) collapseBtn.textContent = '_';

            // Create handoff alert
            var alert = document.createElement('div');
            alert.id = 'karate-handoff';
            alert.style.cssText = 'background:#e94560; color:white; padding:12px; margin:8px; ' +
                'border-radius:6px; text-align:center;';
            alert.innerHTML = '<div style="font-weight:bold; margin-bottom:8px;">🖐 INTERVENTION NEEDED</div>' +
                '<div style="margin-bottom:12px; font-size:12px;">' + message + '</div>' +
                '<textarea id="karate-response" placeholder="Optional: instructions for agent..." ' +
                'style="width:100%; height:50px; margin-bottom:8px; padding:6px; border:none; ' +
                'border-radius:4px; font-size:11px; font-family:monospace; resize:vertical;"></textarea>' +
                '<button id="karate-resume" style="background:white; color:#e94560; border:none; ' +
                'padding:8px 24px; border-radius:4px; font-weight:bold; cursor:pointer; font-size:13px;">' +
                '▶ Resume Agent</button>';

            // Insert at top of sidebar (after header)
            var header = document.getElementById('karate-header');
            if (header && header.nextSibling) {
                sidebar.insertBefore(alert, header.nextSibling);
            } else {
                sidebar.appendChild(alert);
            }

            // Flash the sidebar border
            sidebar.style.boxShadow = '0 0 20px #e94560';

            // Handle resume click
            var self = this;
            var resumeHandler = function() {
                var textarea = document.getElementById('karate-response');
                self.handoffResponse = textarea ? textarea.value : '';
                self.handoffPending = false;
                alert.remove();
                sidebar.style.boxShadow = '0 4px 20px rgba(0,0,0,0.4)';
                self.log('result', 'Control resumed' + (self.handoffResponse ? ': ' + self.handoffResponse : ''));
            };

            document.getElementById('karate-resume').onclick = resumeHandler;

            // Also submit on Enter key in textarea
            document.getElementById('karate-response').onkeydown = function(e) {
                if (e.key === 'Enter' && !e.shiftKey) {
                    e.preventDefault();
                    resumeHandler();
                }
            };

            this.log('cmd', '<code>handoff</code>: ' + message);
            return {waiting: true, message: message};
        },

        /**
         * Get the response text from the last handoff.
         * @returns {string} User's response text
         */
        getHandoffResponse: function() {
            return this.handoffResponse || '';
        },

        /**
         * Check if handoff is still pending.
         * @returns {boolean} True if waiting for user to click Resume
         */
        isHandoffPending: function() {
            return this.handoffPending === true;
        },

        /**
         * Initialize sidebar on first use.
         */
        initSidebar: function() {
            initSidebar();
        }
    };
})();
