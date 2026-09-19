package com.example.pluginloader;

/**
 * Fully generic - deliberately has no reference to any specific app's
 * types (no ViewGroup, no Context even). A plugin decides for itself:
 *   - which app(s) it applies to (via supports())
 *   - which hook name(s) it cares about
 *   - how to interpret/cast the raw args it's handed
 *
 * This means the loader can be baked into a patch for ANY app - Twitter,
 * some other app entirely, doesn't matter - and plugins remain fully
 * responsible for their own logic. The loader just routes events by name.
 */
public interface PatchModule {
    /**
     * Return true if this plugin wants to run for the given app package
     * and hook name. A plugin written for one specific app should check
     * targetPackage; a plugin meant to be reusable across apps might
     * ignore targetPackage and just check hookName, or even ignore both
     * and inspect args at runtime.
     */
    boolean supports(String targetPackage, String hookName);

    /**
     * Called when a matching hook fires. args is whatever the patch that
     * triggered this hook decided to pass - could be a View, a raw
     * reflection object, a String, anything. The plugin is fully
     * responsible for casting/validating args itself; the loader makes
     * no assumptions about their type or count.
     */
    void onEvent(String hookName, Object... args);
}
