/* CD
 *
 * Modified by Dinh Thoai Tran <progrocus@gmail.com>
 *
 * Ref: https://github.com/mozilla/rhino/blob/Rhino1_7R3_RELEASE/src/org/mozilla/javascript/annotations/JSConstructor.java
 *
 * CD
 */

package com.cogroid.discus.annotations;

import java.lang.annotation.*;

/**
 * An annotation that marks a Java method as JavaScript constructor. This can
 * be used as an alternative to the <code>jsConstructor</code> naming convention desribed in
 * {@link com.cogroid.discus.ScriptableObject#defineClass(com.cogroid.discus.Scriptable, java.lang.Class)}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.CONSTRUCTOR, ElementType.METHOD})
public @interface JSConstructor {
}
