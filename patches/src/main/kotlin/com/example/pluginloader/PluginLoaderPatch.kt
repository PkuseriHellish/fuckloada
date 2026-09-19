package com.example.pluginloader

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

// Fill this in per app: the Application subclass name from that app's
// AndroidManifest.xml (<application android:name="...">). This is plain,
// unobfuscated text - no fingerprinting or reverse-engineering needed to
// find it, just unzip the target APK and check AndroidManifest.xml, or
// run `aapt dump badging app.apk | grep application:`.
//
// This is the one per-app manual step this patch couldn't automate away:
// two earlier attempts at reading it straight out of the manifest inside
// this patch (via a guessed document(...) call, then a guessed
// resourceContext.document(...) call) both failed to even compile, and
// rather than guess a third unverified API surface, this trades the
// automation for using only what's confirmed working in the bundled
// ExamplePatch.kt/Fingerprints.kt in this same template.
private const val APPLICATION_CLASS = "Lcom/example/SomeApp;" // TODO: fill in per app

private const val LOADER_CLASS = "Lcom/example/pluginloader/PatchLoader;"
private const val HOOK_NAME = "app.launch"

// Same shape as Fingerprints.kt's AdLoaderFingerprint: definingClass can be
// a full, non-obfuscated class descriptor directly, per that file's own
// comment on StringComparisonType. No filters needed - <init> is a unique
// method name within its own defining class.
private object ApplicationConstructorFingerprint : Fingerprint(
    definingClass = APPLICATION_CLASS,
    name = "<init>",
)

@Suppress("unused")
val pluginLoaderPatch = bytecodePatch(
    name = "Generic plugin loader",
    description = "Hooks this app's Application class once and dispatches to a " +
        "generic, hot-swappable plugin loader (see plugin-loader repo). Set " +
        "APPLICATION_CLASS in PluginLoaderPatch.kt to the target app's declared " +
        "Application class before building.",
) {
    extendWith("extensions/extension.mpe")

    execute {
        val constructor = ApplicationConstructorFingerprint.method

        // UNVERIFIED PIECE (same category of risk as WebhookButtonPatch.kt's
        // known-unverified hook, and the only piece left that genuinely can't
        // be checked without your actual APK): assumes the call to the
        // superclass constructor is an invoke-direct ending in <init>()V,
        // that inserting right after it is safe, and that v0 is a free
        // register at that point. Every Application subclass's constructor
        // is different compiled bytecode - if the build fails with a
        // verifier error here, this is the first place to look.
        val superCallIndex = constructor.instructions.indexOfFirst {
            it.opcode == Opcode.INVOKE_DIRECT &&
                (it as? ReferenceInstruction)?.reference?.toString()?.endsWith("<init>()V") == true
        }
        if (superCallIndex == -1) {
            throw PatchException(
                "Could not find a no-arg super() call in $APPLICATION_CLASS's " +
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
