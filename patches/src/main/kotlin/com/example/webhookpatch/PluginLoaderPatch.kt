package com.example.webhookpatch

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import org.w3c.dom.Element

// Universal loader hook: no compatibleWith(...) call at all, so this is
// selectable against ANY app in Morphe Manager, not just Twitter/X.
//
// The one-time, per-app cost that can't be eliminated: finding a spot in
// THAT app's compiled bytecode to call PatchLoader.dispatch(...) from.
// Rather than fingerprinting some UI class (which differs per app and per
// app version, like WebhookButtonPatch.kt has to for Twitter), this hooks
// the app's own declared Application subclass constructor - a class name
// that's just sitting in plaintext in the manifest, not obfuscated, and
// not something you need to reverse-engineer to find.
//
// Once this builds successfully for a given app, you never repatch that
// app again for a NEW feature idea - only for a new app entirely.

private const val LOADER_CLASS = "Lcom/example/webhookpatch/loader/PatchLoader;"
private const val HOOK_NAME = "app.launch"

@Suppress("unused")
val pluginLoaderPatch = bytecodePatch(
    name = "Generic plugin loader",
    description = "Hooks this app's Application class once and dispatches to a " +
        "generic, hot-swappable plugin loader (see plugin-loader repo). No app " +
        "restriction - selectable against any app.",
) {
    extendWith("extensions/extension.mpe")

    execute {
        // UNVERIFIED PIECE #1 (bigger unknown than #2 below): whether manifest
        // access via document(...) is available directly in this patch's
        // execute block, or whether it needs `dependsOn` on a separate resource
        // patch first so the manifest is decoded before this runs. Check
        // against a real Morphe resource-editing patch (e.g. anything in
        // morphe-patches that edits AndroidManifest.xml) before trusting this
        // line to even compile.
        val applicationClassName = document("AndroidManifest.xml").use { doc ->
            val applicationElement = doc.getElementsByTagName("application").item(0) as? Element
                ?: throw PatchException("No <application> element found in AndroidManifest.xml")
            applicationElement.getAttributeNode("android:name")?.value
        }

        if (applicationClassName.isNullOrEmpty()) {
            throw PatchException(
                "This app doesn't declare a custom Application class, so there's no " +
                    "constructor here to hook. Pick a different hook point for this " +
                    "app instead (a specific Activity's onCreate, for example) - see " +
                    "plugin-loader/README.md."
            )
        }

        // "com.example.SomeApp" -> "Lcom/example/SomeApp;"
        val applicationDescriptor = "L" +
            applicationClassName.trimStart('.').replace('.', '/') + ";"

        val applicationClass = classDefByOrNull(applicationDescriptor)
            ?: throw PatchException("Could not find class $applicationDescriptor in the APK")

        val constructor = applicationClass.methods.firstOrNull { it.name == "<init>" }
            ?: throw PatchException("$applicationDescriptor has no <init> method")

        val implementation = constructor.implementation
            ?: throw PatchException("$applicationDescriptor's <init> has no implementation")

        // UNVERIFIED PIECE #2 (same category of risk as WebhookButtonPatch.kt's
        // known-unverified hook): assumes the call to the superclass
        // constructor is an invoke-direct ending in <init>()V, that inserting
        // right after it is safe, and that v0 is a free register at that
        // point. Every Application subclass's constructor is different
        // compiled bytecode - this needs a fresh build-and-check per app, the
        // same way the Twitter hook did. If the build fails with a verifier
        // error here, that's the first place to look.
        val superCallIndex = implementation.instructions.indexOfFirst {
            it.opcode == Opcode.INVOKE_DIRECT &&
                (it as? ReferenceInstruction)?.reference?.toString()?.endsWith("<init>()V") == true
        }
        if (superCallIndex == -1) {
            throw PatchException(
                "Could not find a no-arg super() call in $applicationDescriptor's " +
                    "constructor - it may take constructor arguments, in which case " +
                    "this simple insert-after-super approach won't work as-is."
            )
        }

        // p0 (this) is the Application instance, and Application IS-A Context,
        // so it can be passed directly as the dispatch() Context parameter -
        // no getContext() call needed here, unlike the Twitter hook.
        constructor.addInstruction(
            superCallIndex + 1,
            """
                const-string v0, "$HOOK_NAME"
                invoke-static { p0, v0, p0 }, $LOADER_CLASS->dispatch(Landroid/content/Context;Ljava/lang/String;Ljava/lang/Object;)V
            """
        )
    }
}
