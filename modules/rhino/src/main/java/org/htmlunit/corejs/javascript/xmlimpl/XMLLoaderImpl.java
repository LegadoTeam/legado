/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.htmlunit.corejs.javascript.xmlimpl;

import org.htmlunit.corejs.javascript.LazilyLoadedCtor;
import org.htmlunit.corejs.javascript.ScriptableObject;
import org.htmlunit.corejs.javascript.xml.XMLLib;
import org.htmlunit.corejs.javascript.xml.XMLLoader;

public class XMLLoaderImpl implements XMLLoader {
    @Override
    public void load(ScriptableObject scope, boolean sealed) {
        String implClass = XMLLibImpl.class.getName();
        new LazilyLoadedCtor(scope, "XML", implClass, sealed, true);
        new LazilyLoadedCtor(scope, "XMLList", implClass, sealed, true);
        new LazilyLoadedCtor(scope, "Namespace", implClass, sealed, true);
        new LazilyLoadedCtor(scope, "QName", implClass, sealed, true);
    }

    @Override
    public XMLLib.Factory getFactory() {
        return XMLLib.Factory.create(XMLLibImpl.class.getName());
    }
}
