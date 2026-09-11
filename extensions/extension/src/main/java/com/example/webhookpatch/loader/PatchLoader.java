package com.example.webhookpatch.loader;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import dalvik.system.DexClassLoader;

/**
 * App-agnostic dispatcher. This class never references any specific app's
 * types - it only knows about hook name strings and raw Object args. It
 * can be baked into a patch for Twitter, some other app, or several apps
 * at once, and never needs to change no matter what a plugin actually does.
 *
 * Convention: a plugin file "foo_bar.dex" must contain a class named
 * "FooBarModule" in PLUGIN_PACKAGE, implementing PatchModule with a public
 * no-arg constructor.
 *
 * A patch (per app, per hook point - this part is still necessarily
 * app-specific and still requires the usual fingerprinting work) only
 * ever needs one line, e.g.:
 *   invoke-static { p0, p1 }, L.../PatchLoader;->dispatch(Landroid/content/Context;...)V
 * passing whatever hookName + args make sense for that hook point.
 */
public final class PatchLoader {

    private static final String LOG_TAG = "PatchLoader";
    private static final String MODULES_SUBDIR = "patches";
    private static final String PLUGIN_PACKAGE = "com.example.webhookpatch.plugins";

    private static List<PatchModule> cachedModules;

    private PatchLoader() {}

    /**
     * Call this from any hook point in any app's patch. hookName identifies
     * which hook fired; args is whatever that specific hook point has
     * available and wants to hand off.
     */
    public static void dispatch(Context context, String hookName, Object... args) {
        String targetPackage = context.getPackageName();
        for (PatchModule module : loadModules(context)) {
            try {
                if (module.supports(targetPackage, hookName)) {
                    module.onEvent(hookName, args);
                }
            } catch (Throwable t) {
                Log.e(LOG_TAG, "module failed: " + module.getClass().getName()
                        + " (hook=" + hookName + ")", t);
            }
        }
    }

    /**
     * Single-arg convenience overload - deliberately kept separate from the
     * varargs version above. Injected smali at a hook point can only pass
     * fixed, already-existing register values; it can't safely construct a
     * new array (new-array/aput) without real risk of getting register
     * allocation wrong. This overload takes one plain Object, so the patch
     * side only ever needs a plain invoke-static with no array-building -
     * the varargs wrapping happens here, in real compiled Java, not in
     * hand-written smali.
     */
    public static void dispatch(Context context, String hookName, Object arg) {
        dispatch(context, hookName, new Object[] { arg });
    }

    private static synchronized List<PatchModule> loadModules(Context context) {
        if (cachedModules != null) return cachedModules;

        List<PatchModule> modules = new ArrayList<>();
        File modulesDir = getModulesDir(context);

        File[] dexFiles = modulesDir.listFiles((dir, name) -> name.endsWith(".dex"));
        if (dexFiles == null || dexFiles.length == 0) {
            Log.i(LOG_TAG, "no plugin .dex files found in " + modulesDir.getAbsolutePath());
            cachedModules = modules;
            return modules;
        }

        for (File dexFile : dexFiles) {
            try {
                DexClassLoader classLoader = new DexClassLoader(
                        dexFile.getAbsolutePath(),
                        context.getCacheDir().getAbsolutePath(),
                        null,
                        context.getClassLoader());

                String className = toClassName(dexFile.getName());
                Class<?> clazz = classLoader.loadClass(className);
                Object instance = clazz.getDeclaredConstructor().newInstance();

                if (instance instanceof PatchModule) {
                    modules.add((PatchModule) instance);
                    Log.i(LOG_TAG, "loaded plugin: " + className);
                } else {
                    Log.e(LOG_TAG, className + " does not implement PatchModule");
                }
            } catch (Throwable t) {
                Log.e(LOG_TAG, "failed to load " + dexFile.getName(), t);
            }
        }

        cachedModules = modules;
        return modules;
    }

    // "webhook_button.dex" -> "com.example.webhookpatch.plugins.WebhookButtonModule"
    private static String toClassName(String fileName) {
        String base = fileName.substring(0, fileName.lastIndexOf('.'));
        StringBuilder pascalCase = new StringBuilder();
        for (String part : base.split("_")) {
            if (part.isEmpty()) continue;
            pascalCase.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return PLUGIN_PACKAGE + "." + pascalCase + "Module";
    }

    private static File getModulesDir(Context context) {
        File external = new File(
                android.os.Environment.getExternalStorageDirectory(),
                "Android/data/" + context.getPackageName() + "/files/" + MODULES_SUBDIR);
        if (!external.exists()) {
            external.mkdirs();
        }
        return external;
    }
}
