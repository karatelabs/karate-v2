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

import io.karatelabs.common.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.thymeleaf.context.ITemplateContext;
import org.thymeleaf.engine.AttributeName;
import org.thymeleaf.model.IProcessableElementTag;
import org.thymeleaf.processor.element.AbstractAttributeTagProcessor;
import org.thymeleaf.processor.element.IElementTagStructureHandler;
import org.thymeleaf.templatemode.TemplateMode;

import java.io.InputStream;

public class KaScriptAttrProcessor extends AbstractAttributeTagProcessor {

    private static final Logger logger = LoggerFactory.getLogger(KaScriptAttrProcessor.class);

    private static final String SRC = "src";

    private final String hostContextPath;
    private final TemplateSourceResolver resolver;

    public KaScriptAttrProcessor(String dialectPrefix, TemplateSourceResolver resolver, String hostContextPath) {
        super(TemplateMode.HTML, dialectPrefix, null, false, SRC, false, 1000, false);
        this.resolver = resolver;
        this.hostContextPath = hostContextPath;
    }

    @Override
    protected void doProcess(ITemplateContext ctx, IProcessableElementTag tag, AttributeName an, String src, IElementTagStructureHandler sh) {
        String scope = tag.getAttributeValue(getDialectPrefix(), KaScriptElemProcessor.SCOPE);
        if (scope == null) {
            if (hostContextPath != null) {
                src = hostContextPath + src;
            }
            String noCache = tag.getAttributeValue(getDialectPrefix(), KaScriptElemProcessor.NOCACHE);
            if (noCache != null) {
                try {
                    TemplateSource resource = resolver.resolve(src, null);
                    src = src + "?ts=" + resource.getLastModified();
                } catch (Exception e) {
                    logger.warn("nocache failed: {}", e.getMessage());
                }
                sh.removeAttribute(getDialectPrefix(), KaScriptElemProcessor.NOCACHE);
            }
            sh.setAttribute(SRC, src);
            return;
        }
        InputStream is = resolver.resolve(src, null).getStream();
        String js = FileUtils.toString(is);
        KarateEngineContext kec = (KarateEngineContext) ctx;
        if (KaScriptElemProcessor.LOCAL.equals(scope)) {
            kec.evalLocal(js);
        } else {
            kec.evalGlobal(js);
        }
        sh.removeElement();
    }

}
