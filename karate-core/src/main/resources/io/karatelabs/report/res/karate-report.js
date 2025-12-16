/* Karate v2 Report - Theme & Utilities */

// Theme management
const KarateTheme = {
    STORAGE_KEY: 'karate-theme',

    // Get current theme: 'light', 'dark', or 'auto'
    get() {
        return localStorage.getItem(this.STORAGE_KEY) || 'auto';
    },

    // Set theme and apply it
    set(theme) {
        if (theme === 'auto') {
            localStorage.removeItem(this.STORAGE_KEY);
            document.documentElement.removeAttribute('data-theme');
        } else {
            localStorage.setItem(this.STORAGE_KEY, theme);
            document.documentElement.setAttribute('data-theme', theme);
        }
        this.updateToggleIcon();
    },

    // Cycle through: auto -> light -> dark -> auto
    toggle() {
        const current = this.get();
        const next = current === 'auto' ? 'light' : current === 'light' ? 'dark' : 'auto';
        this.set(next);
    },

    // Get effective theme (what's actually displayed)
    getEffective() {
        const stored = this.get();
        if (stored !== 'auto') return stored;
        return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
    },

    // Update toggle button icon
    updateToggleIcon() {
        const btn = document.querySelector('.theme-toggle');
        if (!btn) return;

        const theme = this.get();
        const effective = this.getEffective();

        // Icons: sun for light, moon for dark, auto for system
        const icons = {
            light: `<svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 3v1m0 16v1m9-9h-1M4 12H3m15.364 6.364l-.707-.707M6.343 6.343l-.707-.707m12.728 0l-.707.707M6.343 17.657l-.707.707M16 12a4 4 0 11-8 0 4 4 0 018 0z" />
            </svg>`,
            dark: `<svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M20.354 15.354A9 9 0 018.646 3.646 9.003 9.003 0 0012 21a9.003 9.003 0 008.354-5.646z" />
            </svg>`,
            auto: `<svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9.75 17L9 20l-1 1h8l-1-1-.75-3M3 13h18M5 17h14a2 2 0 002-2V5a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z" />
            </svg>`
        };

        btn.innerHTML = icons[theme];
        btn.title = `Theme: ${theme} (click to change)`;
    },

    // Initialize on page load
    init() {
        // Apply stored theme immediately (before paint)
        const stored = localStorage.getItem(this.STORAGE_KEY);
        if (stored) {
            document.documentElement.setAttribute('data-theme', stored);
        }

        // Listen for OS theme changes
        window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => {
            if (this.get() === 'auto') {
                this.updateToggleIcon();
            }
        });

        // Set up toggle button when DOM is ready
        if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', () => this.updateToggleIcon());
        } else {
            this.updateToggleIcon();
        }
    }
};

// Initialize theme immediately
KarateTheme.init();

// Alpine.js data for theme (if using Alpine)
document.addEventListener('alpine:init', () => {
    Alpine.data('theme', () => ({
        current: KarateTheme.get(),
        toggle() {
            KarateTheme.toggle();
            this.current = KarateTheme.get();
        }
    }));
});
