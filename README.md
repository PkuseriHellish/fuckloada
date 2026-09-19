# plugin-loader

App-agnostic runtime plugin loader for Morphe (ReVanced-style) patches.
This is the whole thing — two files, zero references to any specific
app's types (no ViewGroup, no Context even, in the interface). Drop it
into the Gradle patches repo for *any* app and it does the same job.

```
extensions/extension/src/main/java/com/example/webhookpatch/loader/
├── PatchModule.java   - the interface every plugin implements
└── PatchLoader.java   - dispatcher: scans a folder for .dex plugins,
                          loads them with DexClassLoader, routes hook
                          events to whichever ones support(...) them
```

## What this is (and isn't)

This is a library, not a runnable patch on its own. It gives you:

- A convention for hot-swappable plugins (drop a `.dex` on the device,
  restart the app, no repatch/rebuild of the APK).
- A single dispatch entry point any bytecode patch can call from any
  hook point in any app.

It does **not** include any hook injection — finding a spot in some
app's bytecode and calling `PatchLoader.dispatch(...)` from it is
necessarily app-specific fingerprinting work you (or a plugin author)
still have to do per app, per hook point. See `webhookbutton-plugin`
for a worked example of that half, done for Twitter/X.

## Where this goes in your repo

Copy the folder above directly on top of your repo's matching path
(same structure, merge). Rebuild only when these two files change —
not for plugin logic changes, and not for per-app hook changes either
(those live in whatever app-specific patch calls `dispatch`).

```bash
./gradlew :patches:buildAndroid --no-daemon
```

## Included: an automatic hook (`PluginLoaderPatch.kt`)

```
patches/src/main/kotlin/com/example/webhookpatch/
└── PluginLoaderPatch.kt   - two patches: reads the manifest, then hooks
```

This declares no `compatibleWith(...)` restriction, so Morphe Manager
will let you add it to whatever app you pick, and there's no per-app
constant to fill in - it reads the target app's declared `Application`
class name straight out of `AndroidManifest.xml` automatically.

Getting the manifest read working took three tries, worth documenting
here in case this ever needs revisiting: `document(...)` (for reading
`AndroidManifest.xml`) only exists on `ResourcePatchContext`, never on
`BytecodePatchContext` - confirmed by reading the actual pinned
`morphe-patcher` version's source, not by guessing. So this file is
actually two patches:

1. An internal `resourcePatch` (not shown in Manager's patch list) that
   reads the manifest and stashes the class name in a shared variable.
2. The real `bytecodePatch`, which `dependsOn(...)` patch 1 (guaranteeing
   it runs first) and does the actual hook: find that class, get its
   `<init>`, insert the `dispatch(...)` call right after `super()`.

**One piece is still genuinely unverified and can't be checked without
your actual APK:** whether inserting right after the `super()` call is
register-safe for a *given* app's constructor. This is a fresh
build-and-check per app, same as the known-unverified register note in
`webhookbutton-plugin`'s Twitter hook. If the build fails with a
verifier error, that's the first place to look - paste the error and
the constructor's smali and it's a quick fix from there.

It also simply won't apply to apps that don't declare a custom
`Application` subclass - you'd pick a different hook point for those
(see below), the same way you would have had to without this patch at
all.

Build and test against one real app first. Once it works for that app,
you never repatch it again for a new feature idea - only plugin `.dex`
edits from then on. A different app just needs this same patch selected
again in Manager - no new constant, no new fingerprinting.

## Writing your own hook for a new app

If the universal hook above doesn't apply (no custom `Application`
class, or you want a different hook point), any bytecode patch, anywhere,
just needs one call at whatever hook point you've fingerprinted:

```
invoke-static { v0, v1, p0 }, Lcom/example/webhookpatch/loader/PatchLoader;->dispatch(Landroid/content/Context;Ljava/lang/String;Ljava/lang/Object;)V
```

- `v0` — a `Context`
- `v1` — a `const-string` naming the hook, e.g. `"myapp.someView.onFinishInflate"`
- `p0` (or whatever register) — one raw arg to hand to plugins (use the
  varargs overload from real Kotlin/Java code if you need more than one)

Plugins decide for themselves whether they care about a given
`(targetPackage, hookName)` pair via `PatchModule.supports(...)`, and
fully own how they interpret the raw `args` they're handed — the
loader never assumes anything about their type or count.

## Plugin file convention

A plugin file `foo_bar.dex` must contain a class named `FooBarModule`
in package `com.example.webhookpatch.plugins`, implementing
`PatchModule` with a public no-arg constructor. `PatchLoader` looks
for `.dex` files in:

```
Android/data/<targetPackage>/files/patches/
```
