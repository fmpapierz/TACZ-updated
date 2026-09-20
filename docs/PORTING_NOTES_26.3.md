# Porting notes: Minecraft 26.2 → 26.3

Written while moving this project from the 26.2 tree. 26.3 is not an incremental release: it
swaps the windowing/input backend, moves the whole GPU abstraction into a new library, replaces
global render-output redirection with explicit render passes, and converts first-person hand
rendering to the extracted-render-state model.

Everything below was read off the deobfuscated 26.3 jar with `javap`, not from memory.

**Status:** the port builds and runs. `./gradlew build` is green and the Fabric, Quilt and NeoForge
dev clients all reach the main menu with no mixin failures; the Fabric dedicated server reaches
`Done`. See *Verification performed* at the end for exactly what was and was not exercised. Forge
is the one gap, and only because Forge has published nothing for 26.3 — details below.

## Toolchain

| | 26.2 | 26.3 | Why |
|---|---|---|---|
| Gradle wrapper | 9.5.1 | **9.7.1** | Loom 1.18 declares `org.gradle.plugin.api-version` 9.7.0 and will not resolve on 9.5.1 |
| Fabric Loom | 1.17.20 | **1.18.2** | first Loom line that knows 26.3 |
| NeoForged ModDev | 2.0.147 | 2.0.147 | unchanged |
| ForgeGradle | 7.0.40 | 7.0.40 | unchanged |
| Java | 25 | 25 | 26.3's `javaVersion.majorVersion` is still 25 |

## Loader / dependency availability (checked 2026-09-17, two days after 26.3 released)

- **Forge has published nothing for 26.3.** Newest build is `26.2-65.1.3`; the Forge maven's
  `lastUpdated` is 2026-08-27, before 26.3 shipped on 09-15. The `forge` module and its sources are
  intact but excluded from the build — see `forge_enabled` in `gradle.properties`.
- **NeoForge is beta-only**: `26.3.0.4-beta` is the newest.
- **REI** and **Shoulder Surfing Reloaded** have no 26.3 build. Their integration code is untouched
  and still compiles against the 26.2 API jars (both are `compileOnly`, so nothing ships).
- Available: Fabric API `0.160.7+26.3`, Cloth Config `26.3.158`, JEI `31.0.0.5`
  (coordinate is now `jei-26.3-common-api`), Mod Menu `21.0.0-beta.1`, PlayerAnimationLib `1.2.7`.

## GLFW → SDL

26.3 drops `lwjgl-glfw` and `lwjgl-tinyfd` and adds `lwjgl-sdl:3.4.3`. `org.lwjgl.glfw` is simply
not on the classpath any more.

| 26.2 | 26.3 |
|---|---|
| `GLFW.GLFW_PRESS` / `_RELEASE` / `_REPEAT` | `InputConstants.PRESS` / `.RELEASE` / `.REPEAT` |
| `GLFW.GLFW_KEY_<X>` | `InputConstants.KEY_<X>` |
| `GLFW.GLFW_MOUSE_BUTTON_LEFT` etc. | `InputConstants.MOUSE_BUTTON_LEFT` etc. |
| `InputConstants.Type.KEYSYM` | `InputConstants.Type.KEYBOARD` |
| `InputConstants.isKeyDown(window, key)` | `InputConstants.isKeyDown(key)` |
| `KeyEvent#scancode()` | `KeyEvent#keycode()` |

`InputConstants.KEY_*` names follow SDL, not GLFW: `KEY_LCONTROL`, `KEY_RETURN`, `KEY_LBRACKET`,
`KEY_GRAVE`. Numeric values differ from the GLFW ones, so default keybinds must go through the
new constants rather than carrying old integers over.

## blaze3d GPU layer → `com.mojang.renderpearl`

Mojang split the GPU abstraction into `com.mojang.renderpearl`, which now has a Vulkan backend
(`renderpearl.backend.vulkan`) beside the OpenGL one. Straight relocations:

| 26.2 | 26.3 |
|---|---|
| `com.mojang.blaze3d.GpuFormat` | `com.mojang.renderpearl.api.GpuFormat` |
| `com.mojang.blaze3d.PrimitiveTopology` | `com.mojang.renderpearl.api.pipeline.PrimitiveTopology` |
| `com.mojang.blaze3d.buffers.GpuBuffer` / `GpuBufferSlice` | `com.mojang.renderpearl.api.buffers.*` |
| `com.mojang.blaze3d.pipeline.{BindGroupLayout,BlendFunction,ColorTargetState,DepthStencilState,RenderPipeline}` | `com.mojang.renderpearl.api.pipeline.*` |
| `com.mojang.blaze3d.systems.{CommandEncoder,RenderPass}` | `com.mojang.renderpearl.api.commands.*` |
| `com.mojang.blaze3d.textures.{FilterMode,GpuSampler,GpuTexture,GpuTextureView}` | `com.mojang.renderpearl.api.textures.*` |
| `com.mojang.blaze3d.platform.CompareOp` | `com.mojang.renderpearl.api.pipeline.CompareOp` |

Behavioural changes, not just moves:

- `RenderPass#bindTexture(name, view, sampler)` → **`setUniform(name, view, sampler)`**.
- `RenderPass#setPipeline` now takes a **`CompiledRenderPipeline`**. Get one with
  `RenderSystem.getCompiledPipeline(pipeline)` (there is also `getCompiledPipelineNullable`);
  `GpuDevice#compilePipeline` itself is async and returns
  `CompletableFuture<CompiledRenderPipeline.Pending>`.
- `BindGroupLayout.Builder#withSampler(name)` → **`withUniform(name, UniformType.COMBINED_IMAGE_SAMPLER)`**.
  `UniformType` is `COMBINED_IMAGE_SAMPLER` / `UNIFORM_BUFFER` / `TEXEL_BUFFER`.
- `TextureTarget(label, w, h, boolean useDepth, GpuFormat color)` →
  **`TextureTarget(label, w, h, GpuFormat color, GpuFormat depth)`**; pass `null` depth for none
  (`RenderTarget#hasDepth()` reflects it). Depth formats are `D32_FLOAT`, `D24_UNORM_S8_UINT`,
  `D16_UNORM`.
- The access widener must use the new descriptor for the pipeline snippets:
  `Lcom/mojang/renderpearl/api/pipeline/RenderPipeline$Snippet;`. An AW line whose descriptor no
  longer matches fails silently — the field just stays private and you get a confusing
  "has private access" error at the use site.

## Other API changes hit by this mod

| 26.2 | 26.3 |
|---|---|
| `PoseStack#mulPose(Quaternionf)` | `PoseStack#rotate(Quaternionfc)` (the `Matrix4fc` overload keeps the `mulPose` name) |
| `PoseStack#mulPoseAround(q, x, y, z)` | `PoseStack#rotateAround(Quaternionfc, x, y, z)` |
| `Block.CODEC` + `codec()` override | **gone** — 26.3 removed the block codec system; delete both |
| `Util.getPlatform().openUri(...)` | `Blaze3D.openUri(URI)`; `ConfirmLinkScreen` also takes a `URI` now. Parse untrusted strings with `Util.parseAndValidateUntrustedUri(String)` |
| `LivingEntity#swing(hand)` | `swing(hand, SwingAnimation, boolean)`, returning `boolean` |
| `LivingEntity#drop(stack, boolean)` | `drop(stack, boolean, Prediction)` — `Prediction.SERVER_ONLY` / `PREDICTED` |
| `PushReaction.DESTROY` | `PushReaction.POPPED` |
| `SoundInstance#resolve(SoundManager)` | `getOrResolve(SoundManager)` |
| `EntityRenderer#shouldRender(e, frustum, x, y, z)` | gained a trailing `float` |
| `FriendlyByteBuf#readMap` / `writeMap` | **removed with no replacement** (`ByteBufCodecs` has `collection()` but no `map()`). This port adds `com.tacz.guns.util.BufMap`, keeping vanilla's old varint-count wire shape |
| `AbstractPackResources` | split into `PackMetadataResources` (interface), `AbstractPackMetadataResources` (base) and `PackResources` (interface). Extend the base **and** implement `PackResources` |
| `Pack.ResourcesSupplier#openPrimary(loc)` | `openMetadata(loc)` returning `PackMetadataResources` |
| `Pack.ResourcesSupplier#openFull(loc, meta)` | `openResources(loc, meta)` returning **`Stream<PackResources>`** (primary + overlays) |
| `WorldVersion#packVersion(type)` returning `int` | returns `PackFormat`; `PackMetadataSection` takes `InclusiveRange<PackFormat>` |
| `net.minecraft.client.renderer.ItemInHandRenderer` | `net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer` |

`net.minecraft.Util` also lives at `net.minecraft.util.Util` (that move predates 26.3, as does
`ResourceLocation` → `net.minecraft.resources.Identifier`, which this tree already used).

## The render-output model changed: global override → explicit `RenderPass`

This is the single most consequential change for this mod, so it gets its own section.

26.2 chose the render output with **global state**: `RenderSystem.outputColorTextureOverride` and
`outputDepthTextureOverride`. You set them, called `renderAllFeatures(storage)` (an instance
method), and restored them afterwards. TACZ's scope used exactly that to redraw the ocular ring
into the main target after the shader-pack composite, and the mesh GPU renderer had defensive
guards against *other* mods leaving an override set.

26.3 deletes both fields — `RenderSystem` has no output redirection at all — and threads the target
through explicitly instead:

- `FeatureRenderDispatcher.renderAllFeatures` is now **static**, `(RenderPass, PreparedFrame)`.
- `prepareFrame(SubmitNodeStorage)` stays an instance method and returns the `PreparedFrame`.
- Every `PreparedFrame.executeXxx` takes a `RenderPass`.
- `renderAllFeatures` runs the five execute phases and does **not** close the frame (bytecode
  checked), so the caller owns it — use try-with-resources.

So the migration is: open your own pass on the target you want, and pass it in.

```java
CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
try (FeatureRenderDispatcher.PreparedFrame prepared = dispatcher.prepareFrame(storage);
     RenderPass pass = encoder.createRenderPass(() -> "label",
             target.getColorTextureView(), Optional.empty(),
             target.getDepthTextureView(), OptionalDouble.empty())) {
    FeatureRenderDispatcher.renderAllFeatures(pass, prepared);
}
```

Both `Optional`s empty means "do not clear" — the right choice when drawing *over* finished
content. Two consequences worth keeping in mind:

- The mesh renderer's "another mod redirected the output" guards were **deleted**, not ported: with
  the target now a parameter held by whoever opened the pass, nobody can redirect it behind TACZ's
  back, so the hazard is gone structurally.
- `PreparedRenderType#drawFromBuffer` also moved to the explicit model:
  `(StagedVertexBuffer.ExecuteInfo, RenderPass)`, where `ExecuteInfo` bundles the old six arguments
  plus a `PrimitiveTopology`. Because it no longer opens its own pass, the caller's pass now spans
  the `RenderType.prepare()` calls — and `prepare()` resolves textures. Lazy texture loading inside
  an open pass throws, so warm the textures **before** opening it (`PolyMeshGpuRenderer` does, and
  the existing note on `drawList` explains why).

## First-person hand rendering is now extracted render state

`ItemInHandRenderer` became `FirstPersonHandsAndItemsRenderer`, and it is **stateless**: no
`mainHandItem`, no `mainHandHeight`, no `tick`. That state moved to
`net.minecraft.client.player.FirstPersonHandsAndItems`, reachable via the public accessor
`LocalPlayer#firstPersonHandsAndItems()`, which still has the same three fields and a
`tick(LocalPlayer)`.

So TACZ's single `ItemInHandRendererMixin` became two:

- `FirstPersonHandsAndItemsMixin` on `client.player.FirstPersonHandsAndItems` — carries the
  `KeepingItemRenderer` implementation (the put-away viewmodel window), because that is where the
  state now lives. `KeepingItemRenderer#getRenderer()` resolves it off the local player and falls
  back to an empty no-op implementation when there is no player, so call sites need no null checks.
- `FirstPersonHandsAndItemsRendererMixin` on the renderer — keeps the `submitHandsWithItems` hook
  and the `submitArmWithItem` `@WrapOperation`. `submitArmWithItem`'s leading
  `AbstractClientPlayer` became **two** parameters, `PlayerRenderState` +
  `FirstPersonHandsAndItemsRenderState`; the live `LocalPlayer` comes from `Minecraft` instead,
  which is the same object, this being the first-person path by definition.

Related: `EntityRenderDispatcher#getPlayerRenderer(player)` is gone. Use the generic
`getRenderer(entity)` and pattern-match `AvatarRenderer<?>` (that is all `getPlayerRenderer` did);
the other overload, `getRenderer(AvatarRenderState)`, wants a render state rather than an entity.

## Mixin targets

Mixin failures land at *apply* time, not compile time, so they need their own pass. Two different
mistakes are possible and both were present here:

1. **A stale `@At`/`method` descriptor** — the target no longer exists. All 26 Minecraft `@At`
   targets were checked against the 26.3 jar, walking superclasses (Mixin resolves inherited
   members, so a naive check false-positives on `LocalPlayer;setSprinting` and friends). 22 were
   valid; the four that moved are fixed: `LocalPlayer;swing` (now
   `(…InteractionHand;…SwingAnimation;Z)Z` — note it returns `boolean` now),
   `PreparedFrame;executeSolid` (now takes a `RenderPass`), `LevelRenderer;render`, and
   `ItemInHandRenderer;submitArmWithItem` (now on `FirstPersonHandsAndItemsRenderer`).
2. **A handler whose parameters no longer mirror the target's** — the descriptor resolves but the
   injection is rejected. Mixin accepts either the target's full parameter list followed by the
   callback, or a handler that captures nothing but the callback. These were fixed:
   - `LivingEntityMixin#tacz$swingHand` — `swing` gained `SwingAnimation` and now returns
     `boolean`, so the handler takes `CallbackInfoReturnable<Boolean>` and suppresses the swing with
     `setReturnValue(false)`. Vanilla returns `swingState.startIfAble(...)`, i.e. "did a swing
     start", so `false` is the faithful equivalent of 26.2's `ci.cancel()`.
   - `GameRendererMixin#tacz$beginHandPass` / `tacz$endHandPass` — `renderItemInHand` is now
     `(CameraRenderState, PlayerRenderState, GpuTextureView)`.
   - `GameRendererMixin#tacz$renderTickStart` / `tacz$renderTickEnd` — `GameRenderer#render()` takes
     no parameters now; the partial tick comes from `minecraft.getDeltaTracker()`.
   - `GameRendererMixin#tacz$captureSceneForScopePip` / `tacz$renderScopePipView` —
     `renderLevel()` also lost its `DeltaTracker` parameter.
   - `FeatureRenderDispatcherMixin`'s two `renderAllFeatures` handlers had to become **static**
     with `(RenderPass, PreparedFrame)`, since the target method is static now.

A static audit catches class 1 reliably and class 2 only partly (parsing Java parameter lists from
source is fiddly, and the legal no-capture form looks like a mismatch). **The dev client is the
authority** — it names the exact expected descriptor and aborts on the first bad injection, so
iterate: launch, read the one error, fix, relaunch.

Three mixins target methods that no longer exist and are **deliberately left unregistered**, as on
26.2 — `ShapedRecipeMixin` (superseded by vanilla's `ItemStackTemplate` components),
`SoundEngineMixin` and `ChannelAccessHandleMixin` (a Kilt-derived pair with zero consumers; their
own javadoc records the real lambda names and why enabling them is negative value). They compile
but Mixin never loads them.

## Access widening

`tacz.accesswidener` only ever covered members that NeoForge and Forge already open through their
own access transformers — and the access widener is a Loom concept, so `moddev` and ForgeGradle do
not read it. 26.3's NeoForge AT no longer opens `Entity#invulnerableTime`, so recompiling the
common sources for NeoForge failed where Fabric had been fine.

Fixed the way the access widener's own header prescribes: an accessor mixin
(`com.tacz.guns.mixin.accessor.EntityAccessor`). One file, identical behaviour on all four loaders,
and no per-loader AT config. All six call sites only ever clear the field, so a setter is enough.

## Verification performed

`./gradlew build` and `./gradlew check` both pass, the latter including `common:checkLoaderNeutral`
(no loader API leaked into `common`). Build output: `tacz-fabric`, `tacz-quilt` and
`tacz-neoforge` 1.1.8+mc26.3 jars, plus their `-slim` and `-sources` variants.

Every runnable configuration was started and checked:

| | dev client | dedicated server |
|---|---|---|
| Fabric | main menu, 0 mixin failures | `Done (1.609s)`, 0 mixin failures |
| Quilt | main menu, 0 mixin failures | `Done (1.508s)`, 0 mixin failures |
| NeoForge | main menu, 0 mixin failures, Cloth Config loads | `Done (2.547s)`, 0 mixin failures |
| Forge | — no 26.3 build exists — | — |

On every one of them TACZ initialises fully: synced entity data keys registered, the mesh loader
registers its `mesh` gun model type, the default gun pack is exported and rescanned, and LRTactical
loads 7 throwable / 1 melee / 1 consumable indexes. On NeoForge the gun pack is additionally picked
up as a data pack (`Found new data pack tacz_resources`).

Log noise that is **not** a regression: the OSHI/JNA performance-counter probe failures and the
Realms auth failure (both normal in any dev environment), the first-run
`Failed to load properties from file: server.properties`, and Quilt Loader's
`not included in the loom 'mods' block` discovery message — that last one appears identically in
the 26.2 project's logs.

### In-world sessions

Played sessions were run on all three loaders, and the logs are clean:

- **Quilt** — full session in `New World`, guns equipped and handled, clean logout
  (`Stopping singleplayer server as player logged out`, worlds saved). Zero exceptions from world
  load to shutdown.
- **Fabric** — ~6 minute session with firing and reloading (rpg7 reload, fire and mag-in sound
  events). Zero exceptions, clean exit.
- **NeoForge** — in-world session, guns handled. Zero exceptions.

The evidence that the rewritten paths actually ran, rather than merely loading:

- `[TACZ Case08] ConstraintCompensateMode effective=3 (config=3, irisHandActive=false)` appears on
  every loader — that is the first-person viewmodel constraint code executing, i.e. the
  `FirstPersonHandsAndItemsRenderer` / extracted-render-state rewrite working in a real frame.
- The mesh loader's `poly_mesh stats` lines for blocks, ammo, shells and gun LODs — the asset path
  that feeds `PolyMeshGpuRenderer`, whose draw call was moved onto the explicit-`RenderPass` model.
- Gun sound and reload events, i.e. the animation state machine advancing.

The only TACZ warning in any in-world session is
`[TACZ Sound] Missing gun sound resource, skipped. sound=minecraft:<name>` — **pre-existing, not a
port regression**: the 26.2 project's own logs carry the identical warnings for the same sounds
(`bruenmk9_raise`, `rpg7_reload_lower`, …) across its fabric, forge and neoforge run dirs. The
namespace on those paths is `minecraft:` rather than `tacz:`, which is a gun-pack content issue
that predates this port.

Two other in-world messages, both from outside the mod: Fabric API's dev-only untranslated-item-tag
nag, and vanilla's `Requested post effect does not exist: minecraft:end_of_frame`.

### Still not covered

**Scope picture-in-picture has not been confirmed on screen.** It is off by default
(`ScopePipEnable` / `ScopePipRerender` in the `[render]` section of `tacz-client.toml`) and is the
most heavily rewritten area — its output path moved from the deleted `RenderSystem` overrides onto
an explicitly opened `RenderPass`. The sessions above did not turn it on, so aiming down a
magnifying scope with PiP enabled is the remaining thing to try.

Automating in-world entry is not currently possible, for the record: `--quickPlaySingleplayer` is a
no-op on 26.3 dev launches (see the README note — the flag is on the JVM command line, but even a
nonexistent world name draws no complaint, so nothing reaches `QuickPlay`), and screen automation
cannot reach a Gradle-launched `java.exe`.

Nothing in the four loader modules (`fabric`, `quilt`, `neoforge`, `forge`) touches a changed
Minecraft API — they are entrypoints and `ServiceLoader` platform glue only. The one loader-side
break was NeoForge's own API: `TagsUpdatedEvent.UpdateCause` is gone in favour of the
`TagsUpdatedEvent.ClientPacketReceived` / `ServerDataLoad` subclasses, so the single
cause-checking listener became two listeners.
