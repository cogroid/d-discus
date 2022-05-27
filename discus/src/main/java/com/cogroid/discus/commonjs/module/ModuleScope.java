/* CD
 *
 * Modified by Dinh Thoai Tran <progrocus@gmail.com>
 *
 * Ref: https://github.com/mozilla/rhino/blob/Rhino1_7R3_RELEASE/src/org/mozilla/javascript/commonjs/module/ModuleScope.java
 *
 * CD
 */

package com.cogroid.discus.commonjs.module;

import com.cogroid.discus.Scriptable;
import com.cogroid.discus.TopLevel;

import java.net.URI;

/**
 * A top-level module scope. This class provides methods to retrieve the
 * module's source and base URIs in order to resolve relative module IDs
 * and check sandbox constraints.
 */
public class ModuleScope extends TopLevel {

    private static final long serialVersionUID = 1L;

    private final URI uri;
    private final URI base;

    public ModuleScope(Scriptable prototype, URI uri, URI base) {
        this.uri = uri;
        this.base = base;
        setPrototype(prototype);
        cacheBuiltins();
    }

    public URI getUri() {
        return uri;
    }

    public URI getBase() {
        return base;
    }
}
