package org.htmlunit.corejs.javascript.xmlimpl;

import org.htmlunit.corejs.javascript.LazilyLoadedCtor;
import org.htmlunit.corejs.javascript.ScopeObject;
import org.htmlunit.corejs.javascript.xml.XMLLib;
import org.htmlunit.corejs.javascript.xml.XMLLoader;

public class XMLLoaderImpl implements XMLLoader {
    @Override
    public void load(ScopeObject scope, boolean sealed) {
        String implClass = XMLLibImpl.class.getName();
        new LazilyLoadedCtor<>(scope, "XML", implClass, sealed, true);
        new LazilyLoadedCtor<>(scope, "XMLList", implClass, sealed, true);
        new LazilyLoadedCtor<>(scope, "Namespace", implClass, sealed, true);
        new LazilyLoadedCtor<>(scope, "QName", implClass, sealed, true);
    }

    @Override
    public XMLLib.Factory getFactory() {
        return XMLLib.Factory.create(XMLLibImpl.class.getName());
    }
}
