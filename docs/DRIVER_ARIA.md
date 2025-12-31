# ariaTree() Implementation Spec

> Implementation details for the `ariaTree()` method.
> See [DRIVER.md](./DRIVER.md) for API documentation.

---

## YAML Output Format

### Structure

```yaml
- role "accessible name" [state] [ref=ID]:
  - child role "child name" [ref=ID]
  - text: "text content"
```

### Example

**HTML Input:**
```html
<nav>
  <ul>
    <li><a href="/home">Home</a></li>
    <li><a href="/about">About</a></li>
  </ul>
</nav>
<form>
  <input type="text" placeholder="Search..." />
  <button type="submit">Search</button>
</form>
<button disabled>Disabled Button</button>
```

**YAML Output:**
```yaml
- navigation:
  - list:
    - listitem:
      - link "Home" [ref=e1]:
        - /url: /home
    - listitem:
      - link "About" [ref=e2]:
        - /url: /about
- form:
  - textbox [ref=e3]:
    - /placeholder: Search...
  - button "Search" [ref=e4]
- button "Disabled Button" [disabled]
```

---

## ARIA Role Mapping

Map HTML elements to implicit ARIA roles:

| HTML Element | ARIA Role |
|--------------|-----------|
| `<button>` | button |
| `<a href="...">` | link |
| `<h1>`-`<h6>` | heading |
| `<input type="text">` | textbox |
| `<input type="checkbox">` | checkbox |
| `<input type="radio">` | radio |
| `<input type="range">` | slider |
| `<input type="number">` | spinbutton |
| `<select>` | combobox (single) or listbox (multiple) |
| `<textarea>` | textbox |
| `<img>` | img |
| `<nav>` | navigation |
| `<main>` | main |
| `<header>` | banner (when not nested) |
| `<footer>` | contentinfo (when not nested) |
| `<aside>` | complementary |
| `<section>` | region (if named) |
| `<form>` | form (if named) |
| `<ul>`, `<ol>`, `<menu>` | list |
| `<li>` | listitem |
| `<table>` | table |
| `<tr>` | row |
| `<td>` | cell |
| `<th>` | columnheader / rowheader |
| `<dialog>` | dialog |
| `<details>` | group |

**Override:** If element has explicit `role` attribute, use that instead.

---

## Accessible Name Computation

Compute accessible name in priority order:

1. **aria-labelledby** - Concatenate text content of referenced elements
2. **aria-label** - Use attribute value directly
3. **Native labeling**:
   - `<label>` elements for form controls
   - `alt` attribute for images
   - `value` attribute for submit/reset buttons
   - `title` attribute as fallback
4. **Content** - For roles that allow naming from content (button, link, heading, etc.)

### Roles That Allow Naming From Content

- button
- link
- heading
- menuitem
- option
- tab
- treeitem
- cell
- gridcell
- columnheader
- rowheader

---

## Element References (Refs)

### Assignment Rules

Assign refs only to **interactable** elements:
- Buttons, links, inputs, selects, textareas
- Elements with `tabindex >= 0`
- Elements with click handlers (heuristic: cursor style)

### Visibility Requirements

Element must be:
- Not `display: none`
- Not `visibility: hidden`
- Not `aria-hidden="true"`
- Receiving pointer events

### Format

Simple incrementing: `e1`, `e2`, `e3`, ...

### Lifetime

Refs are generated fresh per `ariaTree()` call. Previous refs are invalidated.

---

## State Annotations

| State | When to Include |
|-------|-----------------|
| `[checked]` | Checkbox/radio is checked |
| `[checked=mixed]` | Checkbox is indeterminate |
| `[disabled]` | Element is disabled |
| `[expanded]` | Expandable element is open |
| `[selected]` | Option/tab is selected |
| `[pressed]` | Toggle button is pressed |
| `[level=N]` | Heading level (1-6) |
| `[active]` | Element has focus |

---

## Element Properties

Include as YAML entries prefixed with `/`:

| Property | Applies To | Source |
|----------|------------|--------|
| `/url` | link | `href` attribute |
| `/placeholder` | textbox | `placeholder` attribute |
| `/value` | input (if visible) | `value` property |

---

## Visibility Rules

Skip elements that are hidden:
- `display: none`
- `visibility: hidden`
- `aria-hidden="true"`
- Zero dimensions (`width: 0` or `height: 0`)
- Inside closed `<details>` (except `<summary>`)
- Off-screen with `overflow: hidden` parent

### Visibility Check Implementation

```javascript
function isVisible(element) {
  if (element.getAttribute('aria-hidden') === 'true') return false;

  const style = getComputedStyle(element);
  if (style.display === 'none') return false;
  if (style.visibility === 'hidden') return false;

  const rect = element.getBoundingClientRect();
  if (rect.width === 0 && rect.height === 0) return false;

  // Check if inside closed <details>
  const details = element.closest('details:not([open])');
  if (details && !element.closest('summary')) return false;

  return true;
}
```

---

## Shadow DOM

Traverse into shadow roots by default:

```javascript
function traverse(element) {
  // Process element...

  // Check for shadow root
  if (element.shadowRoot) {
    for (const child of element.shadowRoot.children) {
      traverse(child);
    }
  }

  // Process light DOM children
  for (const child of element.children) {
    traverse(child);
  }
}
```

Handle slotted content by following `<slot>` assignments.

---

## aria-snapshot.js Implementation

### Script Structure

```javascript
(function() {
  if (window.__karate) return;

  const refs = {};
  let refCounter = 0;

  function getAriaTree(options = {}) {
    const interactiveOnly = options.interactive || false;

    // Reset refs for fresh snapshot
    Object.keys(refs).forEach(k => delete refs[k]);
    refCounter = 0;

    const tree = buildTree(document.body, interactiveOnly);
    return renderYaml(tree, 0);
  }

  function buildTree(element, interactiveOnly) {
    if (!isVisible(element)) return null;

    const role = getRole(element);
    const name = getAccessibleName(element);
    const state = getState(element);
    const ref = isInteractable(element) ? assignRef(element) : null;

    const children = [];

    // Shadow DOM
    if (element.shadowRoot) {
      for (const child of element.shadowRoot.children) {
        const node = buildTree(child, interactiveOnly);
        if (node) children.push(node);
      }
    }

    // Light DOM
    for (const child of element.children) {
      const node = buildTree(child, interactiveOnly);
      if (node) children.push(node);
    }

    // Text content (if no element children)
    if (children.length === 0) {
      const text = element.textContent?.trim();
      if (text && !name) {
        children.push({ type: 'text', value: text });
      }
    }

    // Skip if interactiveOnly and not interactable
    if (interactiveOnly && !ref && children.length === 0) {
      return null;
    }

    return { role, name, state, ref, children, properties: getProperties(element) };
  }

  function getRole(element) {
    // Explicit role
    const explicit = element.getAttribute('role');
    if (explicit) return explicit;

    // Implicit role mapping
    const tag = element.tagName.toLowerCase();
    const type = element.getAttribute('type');

    const roleMap = {
      'button': 'button',
      'a': element.hasAttribute('href') ? 'link' : null,
      'h1': 'heading', 'h2': 'heading', 'h3': 'heading',
      'h4': 'heading', 'h5': 'heading', 'h6': 'heading',
      'input': getInputRole(type),
      'select': element.multiple ? 'listbox' : 'combobox',
      'textarea': 'textbox',
      'img': 'img',
      'nav': 'navigation',
      'main': 'main',
      'header': 'banner',
      'footer': 'contentinfo',
      'aside': 'complementary',
      'ul': 'list', 'ol': 'list', 'menu': 'list',
      'li': 'listitem',
      'table': 'table',
      'tr': 'row',
      'td': 'cell',
      'th': 'columnheader',
      'dialog': 'dialog',
      'details': 'group',
      'form': element.hasAttribute('aria-label') ||
              element.hasAttribute('aria-labelledby') ? 'form' : null,
      'section': element.hasAttribute('aria-label') ||
                 element.hasAttribute('aria-labelledby') ? 'region' : null,
    };

    return roleMap[tag] || null;
  }

  function getInputRole(type) {
    const inputRoles = {
      'text': 'textbox',
      'password': 'textbox',
      'email': 'textbox',
      'tel': 'textbox',
      'url': 'textbox',
      'search': 'searchbox',
      'checkbox': 'checkbox',
      'radio': 'radio',
      'range': 'slider',
      'number': 'spinbutton',
      'button': 'button',
      'submit': 'button',
      'reset': 'button',
    };
    return inputRoles[type] || 'textbox';
  }

  function getAccessibleName(element) {
    // 1. aria-labelledby
    const labelledBy = element.getAttribute('aria-labelledby');
    if (labelledBy) {
      const names = labelledBy.split(/\s+/).map(id => {
        const el = document.getElementById(id);
        return el?.textContent?.trim() || '';
      });
      const name = names.join(' ').trim();
      if (name) return name;
    }

    // 2. aria-label
    const ariaLabel = element.getAttribute('aria-label');
    if (ariaLabel) return ariaLabel;

    // 3. Native labeling
    const tag = element.tagName.toLowerCase();

    // Label element
    if (element.id) {
      const label = document.querySelector(`label[for="${element.id}"]`);
      if (label) return label.textContent?.trim();
    }

    // Alt for images
    if (tag === 'img') {
      return element.getAttribute('alt') || '';
    }

    // Value for submit/reset
    if (tag === 'input' && ['submit', 'reset', 'button'].includes(element.type)) {
      return element.value || '';
    }

    // 4. Content (for roles that allow it)
    const role = getRole(element);
    const namingFromContent = ['button', 'link', 'heading', 'menuitem',
                                'option', 'tab', 'treeitem', 'cell'];
    if (namingFromContent.includes(role)) {
      return element.textContent?.trim() || '';
    }

    // 5. Title fallback
    return element.getAttribute('title') || '';
  }

  function getState(element) {
    const states = [];

    // Disabled
    if (element.disabled || element.getAttribute('aria-disabled') === 'true') {
      states.push('disabled');
    }

    // Checked
    if (element.checked) {
      states.push('checked');
    } else if (element.indeterminate) {
      states.push('checked=mixed');
    }

    // Expanded
    const expanded = element.getAttribute('aria-expanded');
    if (expanded === 'true') states.push('expanded');

    // Selected
    if (element.selected || element.getAttribute('aria-selected') === 'true') {
      states.push('selected');
    }

    // Pressed
    const pressed = element.getAttribute('aria-pressed');
    if (pressed === 'true') states.push('pressed');

    // Heading level
    const tag = element.tagName.toLowerCase();
    if (tag.match(/^h[1-6]$/)) {
      states.push(`level=${tag[1]}`);
    }

    // Focus
    if (document.activeElement === element) {
      states.push('active');
    }

    return states;
  }

  function getProperties(element) {
    const props = {};
    const tag = element.tagName.toLowerCase();

    // URL for links
    if (tag === 'a' && element.href) {
      props.url = element.getAttribute('href');
    }

    // Placeholder for inputs
    const placeholder = element.getAttribute('placeholder');
    if (placeholder) {
      props.placeholder = placeholder;
    }

    return Object.keys(props).length > 0 ? props : null;
  }

  function isInteractable(element) {
    const tag = element.tagName.toLowerCase();

    // Naturally interactive elements
    const interactive = ['button', 'a', 'input', 'select', 'textarea'];
    if (interactive.includes(tag) && !element.disabled) {
      return true;
    }

    // Tabindex
    const tabindex = element.getAttribute('tabindex');
    if (tabindex !== null && parseInt(tabindex) >= 0) {
      return true;
    }

    // Role-based (simplified)
    const role = element.getAttribute('role');
    const interactiveRoles = ['button', 'link', 'menuitem', 'option', 'tab',
                              'checkbox', 'radio', 'textbox', 'combobox'];
    if (interactiveRoles.includes(role)) {
      return true;
    }

    return false;
  }

  function assignRef(element) {
    const ref = 'e' + (++refCounter);
    refs[ref] = element;
    return ref;
  }

  function renderYaml(node, indent) {
    if (!node) return '';
    if (node.type === 'text') {
      return '  '.repeat(indent) + `- text: "${escapeYaml(node.value)}"\n`;
    }

    const prefix = '  '.repeat(indent) + '- ';
    let line = node.role || 'generic';

    if (node.name) {
      line += ` "${escapeYaml(node.name)}"`;
    }

    for (const state of node.state || []) {
      line += ` [${state}]`;
    }

    if (node.ref) {
      line += ` [ref=${node.ref}]`;
    }

    const hasChildren = node.children && node.children.length > 0;
    const hasProps = node.properties && Object.keys(node.properties).length > 0;

    if (hasChildren || hasProps) {
      line += ':';
    }

    let result = prefix + line + '\n';

    // Properties
    if (hasProps) {
      for (const [key, value] of Object.entries(node.properties)) {
        result += '  '.repeat(indent + 1) + `- /${key}: ${value}\n`;
      }
    }

    // Children
    for (const child of node.children || []) {
      result += renderYaml(child, indent + 1);
    }

    return result;
  }

  function escapeYaml(str) {
    return str.replace(/"/g, '\\"').replace(/\n/g, '\\n');
  }

  window.__karate = {
    getAriaTree,
    refs
  };
})();
```

---

## Ref Resolution (Java Side)

```java
private Element resolveElement(String locator) {
    if (locator.startsWith("ref:")) {
        String ref = locator.substring(4);
        ensureAriaScriptInjected();

        // Check if ref exists
        Boolean exists = (Boolean) delegate.script(
            "window.__karate.refs['" + ref + "'] !== undefined"
        );
        if (!Boolean.TRUE.equals(exists)) {
            throw new DriverException(
                "ref:" + ref + " is stale, call ariaTree() to refresh"
            );
        }

        // Return element wrapper
        return delegate.scriptElement("window.__karate.refs['" + ref + "']");
    }
    return delegate.element(locator);
}
```

---

## External References

- [WAI-ARIA Specification](https://www.w3.org/TR/wai-aria-1.2/) - ARIA roles and states
- [Accessible Name Computation](https://www.w3.org/TR/accname-1.2/) - Name algorithm
- [HTML-AAM](https://www.w3.org/TR/html-aam-1.0/) - Implicit role mappings
- [dev-browser](https://github.com/anthropics/dev-browser) - Reference implementation
