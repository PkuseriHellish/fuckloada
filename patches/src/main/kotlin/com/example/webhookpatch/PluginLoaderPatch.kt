package com.example.webhookpatch

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import org.w3c.dom.Element

// Confirmed against the real morphe-patcher 1.13.0 source (not guessed):
// ResourcePatchContext.document(path) exists ONLY on ResourcePatchContext,
// never on BytecodePatchContext - which is exactly why both earlier attempts
// (document(...), then resourceContext.document(...)) failed to compile from
// inside a bytecodePatch's execute block. The fix is two patches: a small
// resource patch reads the manifest, the bytecode patch (dependsOn it) does
// the hook, and they share one value via this module-level var. dependsOn(...)
// guarantees the resource patch's execute runs first.
private var applicationClassName: String? = null

private const val LOADER_CLASS = "Lcom/example/webhookpatch/loader/PatchLoader;"
private const val HOOK_NAME = "app.launch"

// Internal (name = null): not shown in Morphe Manager's patch list, but
// required for pluginLoaderPatch below to function. Same convention as this
// template's own internalPatch in InternalPatch.kt.
private val readApplicationClassNamePatch = resourcePatch {
    execute {
        // document(...) here is ResourcePatchContext.document - confirmed
        // real, returns a Document that IS a org.w3c.dom.Document (via `by`
        // delegation) and IS Closeable, so .use{} is correct and needs no
        // further unwrapping (no .file property, unlike very old ReVanced).
        applicationClassName = document("AndroidManifest.xml").use { document ->
            val applicationElement = document.getElementsByTagName("application").item(0) as? Element
                ?: throw PatchException("No <application> element found in AndroidManifest.xml")
            applicationElement.getAttributeNode("android:name")?.value
        }
    }
}

@Suppress("unused")
val pluginLoaderPatch = bytecodePatch(
    name = "Generic plugin loader",
    description = "Hooks this app's Application class once and dispatches to a " +
        "generic, hot-swappable plugin loader (see plugin-loader repo). No app " +
        "restriction, no per-app constant to fill in - the Application class " +
        "name is read from the manifest automatically.",
) {
    dependsOn(readApplicationClassNamePatch)
    extendWith("extensions/extension.mpe")

    execute {
        val className = applicationClassName
        if (className.isNullOrEmpty()) {
            throw PatchException(
                "This app doesn't declare a custom Application class, so there's no " +
                    "constructor here to hook. Pick a different hook point for this " +
                    "app instead - see plugin-loader/README.md."
            )
        }

        // "com.example.SomeApp" -> "Lcom/example/SomeApp;"
        val applicationDescriptor = "L" + className.trimStart('.').replace('.', '/') + ";"

        // classDefByOrNull / mutableClassDefBy: confirmed exact signatures
        // from BytecodePatchContext.kt in the real source. classDefByOrNull
        // hands back an IMMUTABLE ClassDef; mutableClassDefBy(classDef) gets
        // the mutable proxy whose methods are MutableMethod and can actually
        // be edited (this is what addInstruction needs as its receiver).
        val classDef = classDefByOrNull(applicationDescriptor)
            ?: throw PatchException("Could not find class $applicationDescriptor in the APK")

        val mutableClass = mutableClassDefBy(classDef)

        val constructor = mutableClass.methods.firstOrNull { it.name == "<init>" }
            ?: throw PatchException("$applicationDescriptor has no <init> method")

        // UNVERIFIED PIECE (same category of risk as WebhookButtonPatch.kt's
        // known-unverified hook, and the one thing left that genuinely can't
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
