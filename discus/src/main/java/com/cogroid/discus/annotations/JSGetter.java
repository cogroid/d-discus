/* CD
 *
 * Modified by Dinh Thoai Tran <progrocus@gmail.com>
 *
 * Ref: https://github.com/mozilla/rhino/blob/Rhino1_7R3_RELEASE/src/org/mozilla/javascript/annotations/JSGetter.java
 *
 * CD
 */

package com.cogroid.discus.annotations;

import java.lang.annotation.*;

/**
 * An annotation that marks a Java method as JavaScript getter. This can
 * be used as an alternative to the <code>jsGet_</code> prefix desribed in
 * {@link com.cogroid.discus.ScriptableObject#defineClass(com.cogroid.discus.Scriptable, java.lang.Class)}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface JSGetter {
    String value() default "";
}
