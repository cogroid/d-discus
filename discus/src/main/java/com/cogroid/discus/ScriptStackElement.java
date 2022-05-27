/* CD
 *
 * Modified by Dinh Thoai Tran <progrocus@gmail.com>
 *
 * Ref: https://github.com/mozilla/rhino/blob/Rhino1_7R3_RELEASE/src/org/mozilla/javascript/ScriptStackElement.java
 *
 * CD
 */

package com.cogroid.discus;

import java.io.Serializable;

/**
 * This class represents an element on the script execution stack.
 * @see RhinoException#getScriptStack()
 * @author Hannes Wallnoefer
 * @since 1.7R3
 */
public final class ScriptStackElement implements Serializable {

    public final String fileName;
    public final String functionName;
    public final int lineNumber;

    public ScriptStackElement(String fileName, String functionName, int lineNumber) {
        this.fileName = fileName;
        this.functionName = functionName;
        this.lineNumber = lineNumber;
    }

    public String toString() {
        StringBuilder sb = new StringBuilder();
        renderMozillaStyle(sb);
        return sb.toString();
    }

    /**
     * Render stack element in Java-inspired style:
     * <code>    at fileName:lineNumber (functionName)</code>
     * @param sb the StringBuilder to append to
     */
    public void renderJavaStyle(StringBuilder sb) {
        sb.append("\tat ").append(fileName);
        if (lineNumber > -1) {
            sb.append(':').append(lineNumber);
        }
        if (functionName != null) {
            sb.append(" (").append(functionName).append(')');
        }
    }

    /**
     * Render stack element in Mozilla/Firefox style:
     * <code>functionName()@fileName:lineNumber</code>
     * @param sb the StringBuilder to append to
     */
    public void renderMozillaStyle(StringBuilder sb) {
        if (functionName != null) {
            sb.append(functionName).append("()");
        }
        sb.append('@').append(fileName);
        if (lineNumber > -1) {
            sb.append(':').append(lineNumber);
        }
    }

}
