/* CD
 *
 * Modified by Dinh Thoai Tran <progrocus@gmail.com>
 *
 * Ref: https://github.com/mozilla/rhino/blob/Rhino1_7R3_RELEASE/toolsrc/org/mozilla/javascript/tools/shell/Main.java
 *
 * CD
 */

/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * ***** BEGIN LICENSE BLOCK *****
 * Version: MPL 1.1/GPL 2.0
 *
 * The contents of this file are subject to the Mozilla Public License Version
 * 1.1 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.mozilla.org/MPL/
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 * for the specific language governing rights and limitations under the
 * License.
 *
 * The Original Code is Rhino code, released
 * May 6, 1998.
 *
 * The Initial Developer of the Original Code is
 * Netscape Communications Corporation.
 * Portions created by the Initial Developer are Copyright (C) 1997-1999
 * the Initial Developer. All Rights Reserved.
 *
 * Contributor(s):
 *   Patrick Beard
 *   Norris Boyd
 *   Igor Bukanov
 *   Rob Ginda
 *   Kurt Westerfeld
 *   Hannes Wallnoefer
 *
 * Alternatively, the contents of this file may be used under the terms of
 * the GNU General Public License Version 2 or later (the "GPL"), in which
 * case the provisions of the GPL are applicable instead of those above. If
 * you wish to allow use of your version of this file only under the terms of
 * the GPL and not to allow others to use your version of this file under the
 * MPL, indicate your decision by deleting the provisions above and replacing
 * them with the notice and other provisions required by the GPL. If you do
 * not delete the provisions above, a recipient may use your version of this
 * file under either the MPL or the GPL.
 *
 * ***** END LICENSE BLOCK ***** */

package com.cogroid.discus.tools.shell;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.SoftReference;
import java.lang.reflect.UndeclaredThrowableException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.cogroid.discus.Context;
import com.cogroid.discus.ContextAction;
import com.cogroid.discus.EvaluatorException;
import com.cogroid.discus.Function;
import com.cogroid.discus.GeneratedClassLoader;
import com.cogroid.discus.Kit;
import com.cogroid.discus.NativeArray;
import com.cogroid.discus.RhinoException;
import com.cogroid.discus.Script;
import com.cogroid.discus.Scriptable;
import com.cogroid.discus.ScriptableObject;
import com.cogroid.discus.SecurityController;
import com.cogroid.discus.commonjs.module.Require;
import com.cogroid.discus.tools.SourceReader;
import com.cogroid.discus.tools.ToolErrorReporter;

/**
 * The shell program.
 *
 * Can execute scripts interactively or in batch mode at the command line.
 * An example of controlling the JavaScript engine.
 *
 * @author Norris Boyd
 */
public class Main
{
    public static ShellContextFactory
        shellContextFactory = new ShellContextFactory();

    public static Global global = new Global();
    static protected ToolErrorReporter errorReporter;
    static protected int exitCode = 0;
    static private final int EXITCODE_RUNTIME_ERROR = 3;
    static private final int EXITCODE_FILE_NOT_FOUND = 4;
    static boolean processStdin = true;
    static List<String> fileList = new ArrayList<String>();
    static List<String> modulePath;
    static String mainModule;
    static boolean sandboxed = false;
    static Require require;
    private static SecurityProxy securityImpl;
    private final static ScriptCache scriptCache = new ScriptCache(32);

    static {
        global.initQuitAction(new IProxy(IProxy.SYSTEM_EXIT));
    }

    /**
     * Proxy class to avoid proliferation of anonymous classes.
     */
    private static class IProxy implements ContextAction, QuitAction
    {
        private static final int PROCESS_FILES = 1;
        private static final int EVAL_INLINE_SCRIPT = 2;
        private static final int SYSTEM_EXIT = 3;

        private int type;
        String[] args;
        String scriptText;

        IProxy(int type)
        {
            this.type = type;
        }

        public Object run(Context cx)
        {
            if (modulePath != null || mainModule != null) {
                require = global.installRequire(cx, modulePath, sandboxed);
            }
            if (type == PROCESS_FILES) {
                processFiles(cx, args);
            } else if (type == EVAL_INLINE_SCRIPT) {
                Script script = loadScriptFromSource(cx, scriptText,
                                                     "<command>", 1, null);
                if (script != null) {
                    evaluateScript(script, cx, getGlobal());
                }
            } else {
                throw Kit.codeBug();
            }
            return null;
        }

        public void quit(Context cx, int exitCode)
        {
            if (type == SYSTEM_EXIT) {
                System.exit(exitCode);
                return;
            }
            throw Kit.codeBug();
        }
    }

    /**
     * Main entry point.
     *
     * Process arguments as would a normal Java program. Also
     * create a new Context and associate it with the current thread.
     * Then set up the execution environment and begin to
     * execute scripts.
     */
    public static void main(String args[]) {
        try {
            if (Boolean.getBoolean("rhino.use_java_policy_security")) {
                initJavaPolicySecuritySupport();
            }
        } catch (SecurityException ex) {
            ex.printStackTrace(System.err);
        }

        int result = exec(args);
        if (result != 0) {
            System.exit(result);
        }
    }

    /**
     *  Execute the given arguments, but don't System.exit at the end.
     */
    public static int exec(String origArgs[])
    {
        errorReporter = new ToolErrorReporter(false, global.getErr());
        shellContextFactory.setErrorReporter(errorReporter);
        String[] args = processOptions(origArgs);
        if (mainModule != null && !fileList.contains(mainModule))
            fileList.add(mainModule);
        if (processStdin)
            fileList.add(null);

        if (!global.initialized) {
            global.init(shellContextFactory);
        }
        IProxy iproxy = new IProxy(IProxy.PROCESS_FILES);
        iproxy.args = args;
        shellContextFactory.call(iproxy);

        return exitCode;
    }

    static void processFiles(Context cx, String[] args)
    {
        // define "arguments" array in the top-level object:
        // need to allocate new array since newArray requires instances
        // of exactly Object[], not ObjectSubclass[]
        Object[] array = new Object[args.length];
        System.arraycopy(args, 0, array, 0, args.length);
        Scriptable argsObj = cx.newArray(global, array);
        global.defineProperty("arguments", argsObj,
                              ScriptableObject.DONTENUM);

        for (String file: fileList) {
            processSource(cx, file);
        }
    }

    public static Global getGlobal()
    {
        return global;
    }

    /**
     * Parse arguments.
     */
    public static String[] processOptions(String args[])
    {
        String usageError;
        goodUsage: for (int i = 0; ; ++i) {
            if (i == args.length) {
                return new String[0];
            }
            String arg = args[i];
            if (!arg.startsWith("-")) {
                processStdin = false;
                fileList.add(arg);
                String[] result = new String[args.length - i - 1];
                System.arraycopy(args, i+1, result, 0, args.length - i - 1);
                return result;
            }
            if (arg.equals("-version")) {
                if (++i == args.length) {
                    usageError = arg;
                    break goodUsage;
                }
                int version;
                try {
                    version = Integer.parseInt(args[i]);
                } catch (NumberFormatException ex) {
                    usageError = args[i];
                    break goodUsage;
                }
                if (!Context.isValidLanguageVersion(version)) {
                    usageError = args[i];
                    break goodUsage;
                }
                shellContextFactory.setLanguageVersion(version);
                continue;
            }
            if (arg.equals("-opt") || arg.equals("-O")) {
                if (++i == args.length) {
                    usageError = arg;
                    break goodUsage;
                }
                int opt;
                try {
                    opt = Integer.parseInt(args[i]);
                } catch (NumberFormatException ex) {
                    usageError = args[i];
                    break goodUsage;
                }
                if (opt == -2) {
                    // Compatibility with Cocoon Rhino fork
                    opt = -1;
                } else if (!Context.isValidOptimizationLevel(opt)) {
                    usageError = args[i];
                    break goodUsage;
                }
                shellContextFactory.setOptimizationLevel(opt);
                continue;
            }
            if (arg.equals("-encoding")) {
                if (++i == args.length) {
                    usageError = arg;
                    break goodUsage;
                }
                String enc = args[i];
                shellContextFactory.setCharacterEncoding(enc);
                continue;
            }
            if (arg.equals("-strict")) {
                shellContextFactory.setStrictMode(true);
                shellContextFactory.setAllowReservedKeywords(false);
                errorReporter.setIsReportingWarnings(true);
                continue;
            }
            if (arg.equals("-fatal-warnings")) {
                shellContextFactory.setWarningAsError(true);
                continue;
            }
            if (arg.equals("-e")) {
                processStdin = false;
                if (++i == args.length) {
                    usageError = arg;
                    break goodUsage;
                }
                if (!global.initialized) {
                    global.init(shellContextFactory);
                }
                IProxy iproxy = new IProxy(IProxy.EVAL_INLINE_SCRIPT);
                iproxy.scriptText = args[i];
                shellContextFactory.call(iproxy);
                continue;
            }
            if (arg.equals("-modules")) {
                if (++i == args.length) {
                    usageError = arg;
                    break goodUsage;
                }
                if (modulePath == null) {
                    modulePath = new ArrayList<String>();
                }
                modulePath.add(args[i]);
                continue;
            }
            if (arg.equals("-main")) {
                if (++i == args.length) {
                    usageError = arg;
                    break goodUsage;
                }
                mainModule = args[i];
                processStdin = false;
                continue;
            }
            if (arg.equals("-sandbox")) {
                sandboxed = true;
                continue;
            }
            if (arg.equals("-w")) {
                errorReporter.setIsReportingWarnings(true);
                continue;
            }
            if (arg.equals("-f")) {
                processStdin = false;
                if (++i == args.length) {
                    usageError = arg;
                    break goodUsage;
                }
                fileList.add(args[i].equals("-") ? null : args[i]);
                continue;
            }
            if (arg.equals("-sealedlib")) {
                global.setSealedStdLib(true);
                continue;
            }
            if (arg.equals("-debug")) {
                shellContextFactory.setGeneratingDebug(true);
                continue;
            }
            if (arg.equals("-?") ||
                arg.equals("-help")) {
                // print usage message
                global.getOut().println(
                    ToolErrorReporter.getMessage("msg.shell.usage", Main.class.getName()));
                System.exit(1);
            }
            usageError = arg;
            break goodUsage;
        }
        // print error and usage message
        global.getOut().println(
            ToolErrorReporter.getMessage("msg.shell.invalid", usageError));
        global.getOut().println(
            ToolErrorReporter.getMessage("msg.shell.usage", Main.class.getName()));
        System.exit(1);
        return null;
    }

    private static void initJavaPolicySecuritySupport()
    {
        Throwable exObj;
        try {
            Class<?> cl = Class.forName
                ("com.cogroid.discus.tools.shell.JavaPolicySecurity");
            securityImpl = (SecurityProxy)cl.newInstance();
            SecurityController.initGlobal(securityImpl);
            return;
        } catch (ClassNotFoundException ex) {
            exObj = ex;
        } catch (IllegalAccessException ex) {
            exObj = ex;
        } catch (InstantiationException ex) {
            exObj = ex;
        } catch (LinkageError ex) {
            exObj = ex;
        }
        throw Kit.initCause(new IllegalStateException(
            "Can not load security support: "+exObj), exObj);
    }

    /**
     * Evaluate JavaScript source.
     *
     * @param cx the current context
     * @param filename the name of the file to compile, or null
     *                 for interactive mode.
     */
    public static void processSource(Context cx, String filename)
    {
        if (filename == null || filename.equals("-")) {
            PrintStream ps = global.getErr();
            if (filename == null) {
                // print implementation version
                ps.println(cx.getImplementationVersion());
            }

            String charEnc = shellContextFactory.getCharacterEncoding();
            if(charEnc == null)
            {
                charEnc = System.getProperty("file.encoding");
            }
            BufferedReader in;
            try
            {
                in = new BufferedReader(new InputStreamReader(global.getIn(), 
                        charEnc));
            }
            catch(UnsupportedEncodingException e)
            {
                throw new UndeclaredThrowableException(e);
            }
            int lineno = 1;
            boolean hitEOF = false;
            while (!hitEOF) {
            	String[] prompts = global.getPrompts(cx);
                if (filename == null)
                    ps.print(prompts[0]);
                ps.flush();
                String source = "";

                // Collect lines of source to compile.
                while (true) {
                    String newline;
                    try {
                        newline = in.readLine();
                    }
                    catch (IOException ioe) {
                        ps.println(ioe.toString());
                        break;
                    }
                    if (newline == null) {
                        hitEOF = true;
                        break;
                    }
                    source = source + newline + "\n";
                    lineno++;
                    if (cx.stringIsCompilableUnit(source))
                        break;
                    ps.print(prompts[1]);
                }
                Script script = loadScriptFromSource(cx, source, "<stdin>",
                                                     lineno, null);
                if (script != null) {
                    Object result = evaluateScript(script, cx, global);
                    // Avoid printing out undefined or function definitions.
                    if (result != Context.getUndefinedValue() &&
                        !(result instanceof Function &&
                          source.trim().startsWith("function")))
                    {
                        try {
                            ps.println(Context.toString(result));
                        } catch (RhinoException rex) {
                            ToolErrorReporter.reportException(
                                cx.getErrorReporter(), rex);
                        }
                    }
                    NativeArray h = global.history;
                    h.put((int)h.getLength(), h, source);
                }
            }
            ps.println();
        } else if (filename.equals(mainModule)) {
            try {
                require.requireMain(cx, filename);
            } catch (RhinoException rex) {
                ToolErrorReporter.reportException(
                        cx.getErrorReporter(), rex);
                exitCode = EXITCODE_RUNTIME_ERROR;
            } catch (VirtualMachineError ex) {
                // Treat StackOverflow and OutOfMemory as runtime errors
                ex.printStackTrace();
                String msg = ToolErrorReporter.getMessage(
                        "msg.uncaughtJSException", ex.toString());
                exitCode = EXITCODE_RUNTIME_ERROR;
                Context.reportError(msg);
            }
        } else {
            processFile(cx, global, filename);
        }
    }

    public static void processFile(Context cx, Scriptable scope,
                                   String filename)
    {
        if (securityImpl == null) {
            processFileSecure(cx, scope, filename, null);
        } else {
            securityImpl.callProcessFileSecure(cx, scope, filename);
        }
    }

    static void processFileSecure(Context cx, Scriptable scope,
                                  String path, Object securityDomain) {

        boolean isClass = path.endsWith(".class");
        Object source = readFileOrUrl(path, !isClass);

        if (source == null) {
            exitCode = EXITCODE_FILE_NOT_FOUND;
            return;
        }

        byte[] digest = getDigest(source);
        String key = path + "_" + cx.getOptimizationLevel();
        ScriptReference ref = scriptCache.get(key, digest);
        Script script = ref != null ? ref.get() : null;

        if (script == null) {
            if (isClass) {
                script = loadCompiledScript(cx, path, (byte[])source, securityDomain);
            } else {
                String strSrc = (String) source;
                // Support the executable script #! syntax:  If
                // the first line begins with a '#', treat the whole
                // line as a comment.
                if (strSrc.length() > 0 && strSrc.charAt(0) == '#') {
                    for (int i = 1; i != strSrc.length(); ++i) {
                        int c = strSrc.charAt(i);
                        if (c == '\n' || c == '\r') {
                            strSrc = strSrc.substring(i);
                            break;
                        }
                    }
                }
                script = loadScriptFromSource(cx, strSrc, path, 1, securityDomain);
            }
            scriptCache.put(key, digest, script);
        }

        if (script != null) {
            evaluateScript(script, cx, scope);
        }
    }

    public static Script loadScriptFromSource(Context cx, String scriptSource,
                                              String path, int lineno,
                                              Object securityDomain)
    {
        try {
            return cx.compileString(scriptSource, path, lineno,
                                    securityDomain);
        } catch (EvaluatorException ee) {
            // Already printed message.
            exitCode = EXITCODE_RUNTIME_ERROR;
        } catch (RhinoException rex) {
            ToolErrorReporter.reportException(
                cx.getErrorReporter(), rex);
            exitCode = EXITCODE_RUNTIME_ERROR;
        } catch (VirtualMachineError ex) {
            // Treat StackOverflow and OutOfMemory as runtime errors
            ex.printStackTrace();
            String msg = ToolErrorReporter.getMessage(
                "msg.uncaughtJSException", ex.toString());
            exitCode = EXITCODE_RUNTIME_ERROR;
            Context.reportError(msg);
        }
        return null;
    }

    private static byte[] getDigest(Object source) {
        byte[] bytes, digest = null;

        if (source != null) {
            if (source instanceof String) {
                try {
                    bytes = ((String)source).getBytes("UTF-8");
                } catch (UnsupportedEncodingException ue) {
                    bytes = ((String)source).getBytes();
                }
            } else {
                bytes = (byte[])source;
            }
            try {
                MessageDigest md = MessageDigest.getInstance("MD5");
                digest = md.digest(bytes);
            } catch (NoSuchAlgorithmException nsa) {
                // Should not happen
                throw new RuntimeException(nsa);
            }
        }

        return digest;
    }

    private static Script loadCompiledScript(Context cx, String path,
                                             byte[] data, Object securityDomain)
    {
        if (data == null) {
            exitCode = EXITCODE_FILE_NOT_FOUND;
            return null;
        }
        // XXX: For now extract class name of compiled Script from path
        // instead of parsing class bytes
        int nameStart = path.lastIndexOf('/');
        if (nameStart < 0) {
            nameStart = 0;
        } else {
            ++nameStart;
        }
        int nameEnd = path.lastIndexOf('.');
        if (nameEnd < nameStart) {
            // '.' does not exist in path (nameEnd < 0)
            // or it comes before nameStart
            nameEnd = path.length();
        }
        String name = path.substring(nameStart, nameEnd);
        try {
            GeneratedClassLoader loader = SecurityController.createLoader(cx.getApplicationClassLoader(), securityDomain);
            Class<?> clazz = loader.defineClass(name, data);
            loader.linkClass(clazz);
            if (!Script.class.isAssignableFrom(clazz)) {
                throw Context.reportRuntimeError("msg.must.implement.Script");
            }
            return (Script) clazz.newInstance();
         } catch (RhinoException rex) {
            ToolErrorReporter.reportException(
                cx.getErrorReporter(), rex);
            exitCode = EXITCODE_RUNTIME_ERROR;
        } catch (IllegalAccessException iaex) {
            exitCode = EXITCODE_RUNTIME_ERROR;
            Context.reportError(iaex.toString());
        } catch (InstantiationException inex) {
            exitCode = EXITCODE_RUNTIME_ERROR;
            Context.reportError(inex.toString());
        }
        return null;
    }

    public static Object evaluateScript(Script script, Context cx,
                                        Scriptable scope)
    {
        try {
            return script.exec(cx, scope);
        } catch (RhinoException rex) {
            ToolErrorReporter.reportException(
                cx.getErrorReporter(), rex);
            exitCode = EXITCODE_RUNTIME_ERROR;
        } catch (VirtualMachineError ex) {
            // Treat StackOverflow and OutOfMemory as runtime errors
            ex.printStackTrace();
            String msg = ToolErrorReporter.getMessage(
                "msg.uncaughtJSException", ex.toString());
            exitCode = EXITCODE_RUNTIME_ERROR;
            Context.reportError(msg);
        }
        return Context.getUndefinedValue();
    }

    public static InputStream getIn() {
        return getGlobal().getIn();
    }

    public static void setIn(InputStream in) {
        getGlobal().setIn(in);
    }

    public static PrintStream getOut() {
        return getGlobal().getOut();
    }

    public static void setOut(PrintStream out) {
        getGlobal().setOut(out);
    }

    public static PrintStream getErr() {
        return getGlobal().getErr();
    }

    public static void setErr(PrintStream err) {
        getGlobal().setErr(err);
    }

    /**
     * Read file or url specified by <tt>path</tt>.
     * @return file or url content as <tt>byte[]</tt> or as <tt>String</tt> if
     * <tt>convertToString</tt> is true.
     */
    private static Object readFileOrUrl(String path, boolean convertToString)
    {
        try {
            return SourceReader.readFileOrUrl(path, convertToString, 
                    shellContextFactory.getCharacterEncoding());
        } catch (IOException ex) {
            Context.reportError(ToolErrorReporter.getMessage(
                    "msg.couldnt.read.source", path, ex.getMessage()));
            return null;
        }
    }

    static class ScriptReference extends SoftReference<Script> {
        String path;
        byte[] digest;

        ScriptReference(String path, byte[] digest,
                        Script script, ReferenceQueue<Script> queue) {
            super(script, queue);
            this.path = path;
            this.digest = digest;
        }
    }

    static class ScriptCache extends LinkedHashMap<String, ScriptReference> {
        ReferenceQueue<Script> queue;
        int capacity;

        ScriptCache(int capacity) {
            super(capacity + 1, 2f, true);
            this.capacity = capacity;
            queue = new ReferenceQueue<Script>();
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, ScriptReference> eldest) {
            return size() > capacity;
        }

        ScriptReference get(String path, byte[] digest) {
            ScriptReference ref;
            while((ref = (ScriptReference) queue.poll()) != null) {
                remove(ref.path);
            }
            ref = get(path);
            if (ref != null && !Arrays.equals(digest, ref.digest)) {
                remove(ref.path);
                ref = null;
            }
            return ref;
        }

        void put(String path, byte[] digest, Script script) {
            put(path, new ScriptReference(path, digest, script, queue));
        }

    }
}
