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
package io.karatelabs.markup;

import io.karatelabs.common.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.thymeleaf.context.ITemplateContext;
import org.thymeleaf.engine.AttributeName;
import org.thymeleaf.model.IProcessableElementTag;
import org.thymeleaf.processor.element.AbstractAttributeTagProcessor;
import org.thymeleaf.processor.element.IElementTagStructureHandler;
import org.thymeleaf.templatemode.TemplateMode;

public class KaScriptSrcAttrProcessor extends AbstractAttributeTagProcessor {

    private static final Logger logger = LoggerFactory.getLogger(KaScriptSrcAttrProcessor.class);

    private final String hostContextPath;
    private final ResourceResolver resolver;

    public KaScriptSrcAttrProcessor(String dialectPrefix, ResourceResolver resolver, String hostContextPath) {
        super(TemplateMode.HTML, dialectPrefix, null, false, KaScriptElemProcessor.SRC, false, 1000, false);
        this.resolver = resolver;
        this.hostContextPath = hostContextPath;
    }

    @Override
    protected void doProcess(ITemplateContext ctx, IProcessableElementTag tag, AttributeName an, String src, IElementTagStructureHandler sh) {
        String scope = tag.getAttributeValue(getDialectPrefix(), KaScriptElemProcessor.SCOPE);
        Resource srcResource = resolver.resolve(src, null);
        if (scope == null) { // no js evaluation, we just update the html for nocache
            if (hostContextPath != null) {
                src = hostContextPath + src;
            }
            String noCache = tag.getAttributeValue(getDialectPrefix(), KaScriptElemProcessor.NOCACHE);
            if (noCache != null) {
                try {
                    src = src + "?ts=" + srcResource.getLastModified();
                } catch (Exception e) {
                    logger.warn("nocache failed: {}", e.getMessage());
                }
                sh.removeAttribute(getDialectPrefix(), KaScriptElemProcessor.NOCACHE);
            }
            sh.setAttribute(KaScriptElemProcessor.SRC, src);
        } else { // karate js evaluation
            String js = srcResource.getText();
            KarateEngineContext kec = (KarateEngineContext) ctx;
            if (KaScriptElemProcessor.LOCAL.equals(scope)) {
                kec.evalLocal(js);
            } else {
                kec.evalGlobal(js);
            }
            sh.removeElement();
        }
    }

}
