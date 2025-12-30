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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LocatorsTest {

    // ========== CSS Selector Tests ==========

    @Test
    void testCssSelectorById() {
        String result = Locators.selector("#myId");
        assertEquals("document.querySelector(\"#myId\")", result);
    }

    @Test
    void testCssSelectorByClass() {
        String result = Locators.selector(".myClass");
        assertEquals("document.querySelector(\".myClass\")", result);
    }

    @Test
    void testCssSelectorByTag() {
        String result = Locators.selector("button");
        assertEquals("document.querySelector(\"button\")", result);
    }

    @Test
    void testCssSelectorComplex() {
        String result = Locators.selector("div.container > p.text");
        assertEquals("document.querySelector(\"div.container > p.text\")", result);
    }

    @Test
    void testCssSelectorWithContext() {
        String result = Locators.selector("#child", "parentElement");
        assertEquals("parentElement.querySelector(\"#child\")", result);
    }

    // ========== XPath Selector Tests ==========

    @Test
    void testIsXpathSlash() {
        assertTrue(Locators.isXpath("//div"));
        assertTrue(Locators.isXpath("/html/body"));
    }

    @Test
    void testIsXpathRelative() {
        assertTrue(Locators.isXpath("./span"));
        assertTrue(Locators.isXpath("../parent"));
    }

    @Test
    void testIsXpathParenthesized() {
        assertTrue(Locators.isXpath("(//div)[1]"));
    }

    @Test
    void testIsNotXpath() {
        assertFalse(Locators.isXpath("#id"));
        assertFalse(Locators.isXpath(".class"));
        assertFalse(Locators.isXpath("div"));
    }

    @Test
    void testXpathSelector() {
        String result = Locators.selector("//div[@id='test']");
        assertTrue(result.contains("document.evaluate"));
        assertTrue(result.contains("//div[@id='test']"));
        assertTrue(result.contains("9, null"));  // FIRST_ORDERED_NODE_TYPE
    }

    @Test
    void testXpathSelectorWithContext() {
        String result = Locators.selector("//span", "parentElement");
        assertTrue(result.contains(".//span"));  // Should be made relative
        assertTrue(result.contains("parentElement"));
    }

    @Test
    void testXpathRelativeSelector() {
        String result = Locators.selector("./child", "parentElement");
        assertTrue(result.contains("./child"));  // Already relative
        assertTrue(result.contains("parentElement"));
    }

    // ========== Wildcard Locator Tests ==========

    @Test
    void testWildcardExact() {
        String result = Locators.expandWildcard("{div}Login");
        assertEquals("//div[normalize-space(text())='Login']", result);
    }

    @Test
    void testWildcardContains() {
        String result = Locators.expandWildcard("{^div}Log");
        assertEquals("//div[contains(normalize-space(text()),'Log')]", result);
    }

    @Test
    void testWildcardAnyTag() {
        String result = Locators.expandWildcard("{}Submit");
        assertEquals("//*[normalize-space(text())='Submit']", result);
    }

    @Test
    void testWildcardWithIndex() {
        String result = Locators.expandWildcard("{div:2}Item");
        assertEquals("(//div[normalize-space(text())='Item'])[2]", result);
    }

    @Test
    void testWildcardAnyTagWithIndex() {
        String result = Locators.expandWildcard("{:3}Option");
        assertEquals("(//*[normalize-space(text())='Option'])[3]", result);
    }

    @Test
    void testWildcardContainsWithIndex() {
        String result = Locators.expandWildcard("{^span:1}Click");
        assertEquals("(//span[contains(normalize-space(text()),'Click')])[1]", result);
    }

    @Test
    void testWildcardToSelector() {
        String result = Locators.selector("{button}Submit");
        assertTrue(result.contains("document.evaluate"));
        assertTrue(result.contains("//button[normalize-space(text())='Submit']"));
    }

    @Test
    void testWildcardBadFormat() {
        assertThrows(DriverException.class, () -> {
            Locators.expandWildcard("{no-closing-brace");
        });
    }

    // ========== XPath Quote Escaping (v1 Bug Fix) ==========

    @Test
    void testWildcardWithSingleQuote() {
        String result = Locators.expandWildcard("{div}It's working");
        // Should use double quotes
        assertEquals("//div[normalize-space(text())=\"It's working\"]", result);
    }

    @Test
    void testWildcardWithDoubleQuote() {
        String result = Locators.expandWildcard("{div}Say \"Hello\"");
        // Should use single quotes
        assertEquals("//div[normalize-space(text())='Say \"Hello\"']", result);
    }

    @Test
    void testWildcardWithBothQuotes() {
        String result = Locators.expandWildcard("{div}It's \"complex\"");
        // Should use concat()
        assertTrue(result.contains("concat("));
    }

    @Test
    void testEscapeXpathStringSimple() {
        assertEquals("'hello'", Locators.escapeXpathString("hello"));
    }

    @Test
    void testEscapeXpathStringSingleQuote() {
        assertEquals("\"it's\"", Locators.escapeXpathString("it's"));
    }

    @Test
    void testEscapeXpathStringDoubleQuote() {
        assertEquals("'say \"hi\"'", Locators.escapeXpathString("say \"hi\""));
    }

    @Test
    void testEscapeXpathStringBothQuotes() {
        String result = Locators.escapeXpathString("it's \"complex\"");
        assertTrue(result.startsWith("concat("));
    }

    // ========== JS Expression Passthrough ==========

    @Test
    void testPureJsPassthrough() {
        String js = "(document.getElementById('test'))";
        assertEquals(js, Locators.selector(js));
    }

    @Test
    void testParenthesizedXpathNotPassedThrough() {
        String xpath = "(//div)[1]";
        String result = Locators.selector(xpath);
        assertTrue(result.contains("document.evaluate"));
    }

    // ========== selectorAll Tests ==========

    @Test
    void testSelectorAllCss() {
        String result = Locators.selectorAll("div.item");
        assertEquals("document.querySelectorAll(\"div.item\")", result);
    }

    @Test
    void testSelectorAllXpath() {
        String result = Locators.selectorAll("//div[@class='item']");
        assertTrue(result.contains("document.evaluate"));
        assertTrue(result.contains("5, null"));  // ORDERED_NODE_ITERATOR_TYPE
    }

    @Test
    void testSelectorAllWildcard() {
        String result = Locators.selectorAll("{li}Option");
        assertTrue(result.contains("document.evaluate"));
        assertTrue(result.contains("//li[normalize-space(text())='Option']"));
    }

    // ========== toFunction Tests ==========

    @Test
    void testToFunctionIdentity() {
        assertEquals("function(_){ return _ }", Locators.toFunction(null));
        assertEquals("function(_){ return _ }", Locators.toFunction(""));
    }

    @Test
    void testToFunctionUnderscore() {
        assertEquals("function(_){ return _.value }", Locators.toFunction("_.value"));
        assertEquals("function(_){ return _.textContent }", Locators.toFunction("_.textContent"));
    }

    @Test
    void testToFunctionNegation() {
        assertEquals("function(_){ return !_.disabled }", Locators.toFunction("!_.disabled"));
    }

    @Test
    void testToFunctionArrow() {
        String arrow = "e => e.value";
        assertEquals(arrow, Locators.toFunction(arrow));
    }

    @Test
    void testToFunctionRegular() {
        String fn = "function(e){ return e.id }";
        assertEquals(fn, Locators.toFunction(fn));
    }

    // ========== wrapInFunctionInvoke Tests ==========

    @Test
    void testWrapInFunctionInvoke() {
        String result = Locators.wrapInFunctionInvoke("return 42");
        assertEquals("(function(){ return 42 })()", result);
    }

    // ========== scriptSelector Tests ==========

    @Test
    void testScriptSelector() {
        String result = Locators.scriptSelector("#myInput", "_.value");
        assertTrue(result.startsWith("(function(){"));
        assertTrue(result.contains("var fun = function(_){ return _.value }"));
        assertTrue(result.contains("document.querySelector(\"#myInput\")"));
        assertTrue(result.contains("return fun(e)"));
        assertTrue(result.endsWith("})()"));
    }

    @Test
    void testScriptAllSelector() {
        String result = Locators.scriptAllSelector("li", "_.textContent");
        assertTrue(result.startsWith("(function(){"));
        assertTrue(result.contains("querySelectorAll"));
        assertTrue(result.contains("forEach"));
    }

    @Test
    void testScriptAllSelectorXpath() {
        String result = Locators.scriptAllSelector("//li", "_.textContent");
        assertTrue(result.startsWith("(function(){"));
        assertTrue(result.contains("document.evaluate"));
        assertTrue(result.contains("iterateNext"));
    }

    // ========== UI Helper Tests ==========

    @Test
    void testHighlightJs() {
        String result = Locators.highlight("#btn", 3000);
        assertTrue(result.contains("document.querySelector(\"#btn\")"));
        assertTrue(result.contains("background: yellow"));
        assertTrue(result.contains("border: 2px solid red"));
        assertTrue(result.contains("setTimeout"));
        assertTrue(result.contains("3000"));
    }

    @Test
    void testOptionSelectorByValue() {
        String result = Locators.optionSelector("#dropdown", "us");
        assertTrue(result.contains("e.options[i].value === t"));
    }

    @Test
    void testOptionSelectorByExactText() {
        String result = Locators.optionSelector("#dropdown", "{}United States");
        assertTrue(result.contains("e.options[i].text === t"));
        assertTrue(result.contains("United States"));
    }

    @Test
    void testOptionSelectorByTextContains() {
        String result = Locators.optionSelector("#dropdown", "{^}United");
        assertTrue(result.contains("e.options[i].text.indexOf(t) !== -1"));
    }

    @Test
    void testGetPositionJs() {
        String result = Locators.getPositionJs("#element");
        assertTrue(result.contains("getBoundingClientRect"));
        assertTrue(result.contains("scrollX"));
        assertTrue(result.contains("scrollY"));
        assertTrue(result.contains("width: r.width"));  // v1 bug fix: no scroll offset on dimensions
        assertTrue(result.contains("height: r.height"));
    }

    @Test
    void testFocusJs() {
        String result = Locators.focusJs("#input");
        assertTrue(result.contains("focus()"));
        assertTrue(result.contains("selectionStart"));
        assertTrue(result.contains("selectionEnd"));
    }

    @Test
    void testClickJs() {
        String result = Locators.clickJs("#btn");
        assertEquals("document.querySelector(\"#btn\").click()", result);
    }

    @Test
    void testScrollJs() {
        String result = Locators.scrollJs("#element");
        assertTrue(result.contains("scrollIntoView"));
        assertTrue(result.contains("block: 'center'"));
    }

    @Test
    void testInputJs() {
        String result = Locators.inputJs("#name", "John Doe");
        assertTrue(result.contains("focus()"));
        assertTrue(result.contains("e.value = \"John Doe\""));
        assertTrue(result.contains("dispatchEvent"));
        assertTrue(result.contains("input"));
        assertTrue(result.contains("change"));
    }

    @Test
    void testClearJs() {
        String result = Locators.clearJs("#name");
        assertTrue(result.contains("e.value = ''"));
        assertTrue(result.contains("dispatchEvent"));
    }

    @Test
    void testTextJs() {
        String result = Locators.textJs("#content");
        assertTrue(result.contains("textContent"));
    }

    @Test
    void testValueJs() {
        String result = Locators.valueJs("#input");
        assertTrue(result.contains("e.value"));
    }

    @Test
    void testAttributeJs() {
        String result = Locators.attributeJs("#link", "href");
        assertTrue(result.contains("getAttribute(\"href\")"));
    }

    @Test
    void testPropertyJs() {
        String result = Locators.propertyJs("#checkbox", "checked");
        assertTrue(result.contains("e[\"checked\"]"));
    }

    @Test
    void testEnabledJs() {
        String result = Locators.enabledJs("#btn");
        assertTrue(result.contains("!e.disabled"));
    }

    @Test
    void testExistsJs() {
        String result = Locators.existsJs("#element");
        assertTrue(result.contains("!== null"));
    }

    @Test
    void testOuterHtmlJs() {
        String result = Locators.outerHtmlJs("#div");
        assertTrue(result.contains("outerHTML"));
    }

    @Test
    void testInnerHtmlJs() {
        String result = Locators.innerHtmlJs("#div");
        assertTrue(result.contains("innerHTML"));
    }

    @Test
    void testCountJs() {
        String result = Locators.countJs("li.item");
        assertTrue(result.contains("querySelectorAll"));
        assertTrue(result.contains(".length"));
    }

    @Test
    void testCountJsXpath() {
        String result = Locators.countJs("//li");
        assertTrue(result.contains("document.evaluate"));
        assertTrue(result.contains("while(iter.iterateNext())"));
    }

    // ========== JS String Escaping Tests ==========

    @Test
    void testEscapeForJsBasic() {
        assertEquals("hello", Locators.escapeForJs("hello"));
    }

    @Test
    void testEscapeForJsQuotes() {
        assertEquals("say \\\"hello\\\"", Locators.escapeForJs("say \"hello\""));
    }

    @Test
    void testEscapeForJsBackslash() {
        assertEquals("path\\\\to\\\\file", Locators.escapeForJs("path\\to\\file"));
    }

    @Test
    void testEscapeForJsNewline() {
        assertEquals("line1\\nline2", Locators.escapeForJs("line1\nline2"));
    }

    @Test
    void testEscapeForJsCarriageReturn() {
        assertEquals("text\\r\\n", Locators.escapeForJs("text\r\n"));
    }

    @Test
    void testEscapeForJsTab() {
        assertEquals("col1\\tcol2", Locators.escapeForJs("col1\tcol2"));
    }

    @Test
    void testEscapeForJsNull() {
        assertEquals("", Locators.escapeForJs(null));
    }

    // ========== Error Cases ==========

    @Test
    void testSelectorNullThrows() {
        assertThrows(DriverException.class, () -> Locators.selector(null));
    }

    @Test
    void testSelectorEmptyThrows() {
        assertThrows(DriverException.class, () -> Locators.selector(""));
    }

    @Test
    void testSelectorAllNullThrows() {
        assertThrows(DriverException.class, () -> Locators.selectorAll(null));
    }

    @Test
    void testSelectorAllEmptyThrows() {
        assertThrows(DriverException.class, () -> Locators.selectorAll(""));
    }

}
