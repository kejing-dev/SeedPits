package io.embodit.seedpits.ui.board

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.DirectionalLightComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.Resource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import io.embodit.seedpits.domain.game.Phase
import io.embodit.seedpits.domain.rules.Kalah
import io.embodit.seedpits.domain.rules.Side
import io.embodit.seedpits.domain.rules.SowResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

internal fun Entity.transform(): TransformComponent = components[TransformComponent::class.java]!!

/** What the board needs from the game, in one value (built by BoardView from GameState + Settings). */
data class BoardInput(
    val gameId: Int = 0,
    val phase: Phase = Phase.LOADING,
    val cells: List<Int> = Kalah.initial().cells,
    val shown: List<Int> = Kalah.initial().cells,
    val toMove: Side = Side.SOUTH,
    val lit: List<Int> = emptyList(),
    val previewPit: Int? = null,
    val preview: SowResult? = null,
    val hintPit: Int? = null,
    val hint: SowResult? = null,
    val sowId: Int? = null,
    val sow: SowResult? = null,
    val sowByAi: Boolean = false,
    val alertId: Int? = null,
    val alertPit: Int? = null,
    val lastFrom: Int? = null,
    val againSide: Side? = null,
    val winner: Side? = null,
    val ended: Boolean = false,
    val coach: Boolean = false,
    val reducedMotion: Boolean = false,
    val showDigits: Boolean = true,
    val tiltDeg: Int = 15,
    val plateText: String = "",
    val plateDot: Int = BoardTextures.OCHRE,
    val plateTappable: Boolean = false,
    val testGlyph: Boolean = false,
    val frozen: Boolean = false,
)

sealed interface SceneEvent {
    data class Step(val id: Int, val step: Int) : SceneEvent
    data class Done(val id: Int) : SceneEvent
    data class Sound(val kind: SoundKind, val rate: Float = 1f, val pan: Float = 0f) : SceneEvent
}

enum class SoundKind { DROP, STORE, GRAB, AGAIN, CAPTURE, ILLEGAL, SWEEP, WIN, LOSE, DRAW }

/**
 * Owns every entity in the volume and plays the sowing animation.
 *
 * ```
 * content root (volume centre, +Z toward the player)
 * ├ light                         warm key light from the front right
 * ├ tiltRoot                      on the near edge, tipped 0 / 15° (far edge up)
 * │ └ boardRoot                   board top centre; board.glb, 12 pit colliders, 48 stones, marks, 14 digit tiles
 * └ seatPlate                     upright card over the far edge (never tilted), faces +Z
 * ```
 * Main thread only: [apply] from Compose, [onFrame] from [BoardFrameSystem]. Per-frame motion lives here.
 */
class BoardScene(private val context: Context, private val onEvent: (SceneEvent) -> Unit) {
    val root: Entity = Entity().apply { setName("seedRoot") }
    private val tiltRoot = Entity().apply { setName("tiltRoot") }
    private val boardRoot = Entity().apply { setName("boardRoot") }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val textures = BoardTextures(context)

    private class Res(
        val all: List<Resource>,
        val unitPlane: MeshResource,
        val fallbackSlab: MeshResource,
        val slabMat: Material,
        val pitShape: ShapeResource,
        val boardShape: ShapeResource,
        val plateShape: ShapeResource,
        val physics: PhysicsMaterialResource,
        val litMat: Material,
        val selectMat: Material,
        val hintMat: Material,
        val dotMat: Material,
        val endMat: Material,
        val captureMat: Material,
        val crossMat: Material,
        val turnMat: Material,
        val lastMat: Material,
        val digitBlank: Material,
    )

    private var res: Res? = null
    private var boardModel: Entity? = null
    private var stoneTemplates: List<Entity>? = null
    private var built = false

    private class Stone(val entity: Entity, val jitter: P3, val yaw: Float) {
        var pos = P3(0f, 0f, 0f)
    }
    private val stones = ArrayList<Stone>()
    private val cellStones = Array(Kalah.CELLS) { ArrayList<Int>() }

    private val litRings = ArrayList<ModelEntity>()
    private val pathDots = ArrayList<ModelEntity>()
    private var selectRing: ModelEntity? = null
    private var endRing: ModelEntity? = null
    private var captureRing: ModelEntity? = null
    private var cross: ModelEntity? = null
    private var turnGlow: ModelEntity? = null
    private var lastRing: ModelEntity? = null
    private val digitTiles = ArrayList<ModelEntity>()
    private val digitMats = HashMap<String, Material>()
    private val digitPending = HashSet<String>()
    private var seatPlate: ModelEntity? = null
    private val plateMats = HashMap<String, Material>()
    private val platePending = HashSet<String>()
    private var plateKey: String? = null

    private var input = BoardInput()
    private var regionHalf: P3? = null
    private var tiltNow = 15f
    private var tiltTarget = 15f
    private var clock = 0f
    private var crossUntil = 0f
    private var shownAlert: Int? = null
    private var glowFlashUntil = 0f
    private var glowCell = Kalah.SOUTH_STORE

    private var timeline: Timeline? = null
    private var playingId: Int? = null
    private var appliedGame = -1

    val hasResources: Boolean get() = res != null

    // ------------------------------------------------------------------ resources

    /** Creates meshes, materials, shapes and textures off the main thread (each is a blocking renderer round trip). */
    suspend fun createResources() {
        if (res != null) return
        val t0 = SystemClock.elapsedRealtime()
        val r = withContext(Dispatchers.IO) { buildResources() }
        res = r
        Log.i(TAG, "RES created in ${SystemClock.elapsedRealtime() - t0} ms")
        tryBuild()
        // Digit textures for every count, in the background, a few at a time.
        scope.launch { prewarmDigits() }
    }

    private suspend fun buildResources(): Res = coroutineScope {
        val all = ArrayList<Resource>()
        fun <T : Resource> T.keep(): T { toGlobal(); all += this; return this }
        val white = Color4(1f, 1f, 1f, 1f)
        fun markMat(tex: TextureResource): Material = UnlitMaterial.create(BlendingMode.TRANSPARENT).apply {
            setBaseColor(white); setBaseColorTexture(tex); setCullingMode(MaterialCullingMode.NONE); setApplyToneMapping(false)
        }.keep()
        val texJobs = listOf(
            async(Dispatchers.IO) { TextureResource.create(textures.ring(BoardTextures.LIT, soft = true, alpha = 235)).keep() },
            async(Dispatchers.IO) { TextureResource.create(textures.ring(BoardTextures.OCHRE, stroke = 0.09f)).keep() },
            async(Dispatchers.IO) { TextureResource.create(textures.ring(BoardTextures.AMBER, stroke = 0.12f)).keep() },
            async(Dispatchers.IO) { TextureResource.create(textures.dot(BoardTextures.BLUE)).keep() },
            async(Dispatchers.IO) { TextureResource.create(textures.ring(BoardTextures.AMBER, stroke = 0.08f)).keep() },
            async(Dispatchers.IO) { TextureResource.create(textures.ring(BoardTextures.ORANGE, stroke = 0.10f)).keep() },
            async(Dispatchers.IO) { TextureResource.create(textures.cross(BoardTextures.RED)).keep() },
            async(Dispatchers.IO) { TextureResource.create(textures.ring(BoardTextures.OCHRE, stroke = 0.16f, alpha = 235)).keep() },
            async(Dispatchers.IO) { TextureResource.create(textures.ring(BoardTextures.SHADE, stroke = 0.05f, dashed = true, alpha = 170)).keep() },
            async(Dispatchers.IO) { TextureResource.create(textures.digit("")).keep() },
        )
        val plane = async(Dispatchers.IO) { MeshResource.createPlane(1f, 1f, 0f).keep() }
        // gate:allow geometry fallback slab, only drawn when board.glb fails to load
        val slab = async(Dispatchers.IO) { MeshResource.createBox(Vector3(PitLayout.BOARD_W, PitLayout.BOARD_T, PitLayout.BOARD_D), 0.004f).keep() }
        val t = texJobs.awaitAll()
        Res(
            all = all,
            unitPlane = plane.await(),
            fallbackSlab = slab.await(),
            slabMat = PhysicallyBasedMaterial.create(BlendingMode.OPAQUE).apply { setBaseColor(Color4(0.48f, 0.29f, 0.15f, 1f)); setRoughness(0.75f); setMetallic(0f) }.keep(),
            pitShape = ShapeResource.createBox(Vector3(PitLayout.COLLIDER_W, PitLayout.COLLIDER_H, PitLayout.COLLIDER_W)).keep(),
            boardShape = ShapeResource.createBox(Vector3(PitLayout.BOARD_W, 0.004f, PitLayout.BOARD_D)).keep(),
            plateShape = ShapeResource.createBox(Vector3(PitLayout.SEAT_W, PitLayout.SEAT_H, 0.01f)).keep(),
            physics = PhysicsMaterialResource().keep(),
            litMat = markMat(t[0]), selectMat = markMat(t[1]), hintMat = markMat(t[2]), dotMat = markMat(t[3]),
            endMat = markMat(t[4]), captureMat = markMat(t[5]), crossMat = markMat(t[6]), turnMat = markMat(t[7]),
            lastMat = markMat(t[8]),
            digitBlank = UnlitMaterial.create(BlendingMode.OPAQUE).apply { setBaseColor(white); setBaseColorTexture(t[9]); setApplyToneMapping(false) }.keep(),
        )
    }

    private suspend fun prewarmDigits() {
        val gate = Semaphore(6)
        coroutineScope {
            (0..Kalah.TOTAL).map { n -> launch { gate.withPermit { ensureDigit(n.toString()) } } }
        }
        Log.i(TAG, "DIGITS ready ${digitMats.size}")
    }

    private suspend fun ensureDigit(key: String) {
        if (digitMats.containsKey(key) || !digitPending.add(key)) return
        val mat = withContext(Dispatchers.IO) {
            runCatching {
                val tex = TextureResource.create(textures.digit(key)).also { it.toGlobal() }
                UnlitMaterial.create(BlendingMode.OPAQUE).apply { setBaseColor(Color4(1f, 1f, 1f, 1f)); setBaseColorTexture(tex); setApplyToneMapping(false); toGlobal() }
            }.onFailure { Log.e(TAG, "digit $key failed", it) }.getOrNull()
        }
        digitPending.remove(key)
        if (mat != null) { digitMats[key] = mat; refreshDigits() }
    }

    private fun requestPlate(key: String, text: String, dot: Int) {
        if (plateMats.containsKey(key) || !platePending.add(key)) return
        scope.launch {
            val mat = withContext(Dispatchers.IO) {
                runCatching {
                    val tex = TextureResource.create(textures.plate(text, dot)).also { it.toGlobal() }
                    UnlitMaterial.create(BlendingMode.TRANSPARENT).apply { setBaseColor(Color4(1f, 1f, 1f, 1f)); setBaseColorTexture(tex); setApplyToneMapping(false); setCullingMode(MaterialCullingMode.NONE); toGlobal() }
                }.onFailure { Log.e(TAG, "plate failed", it) }.getOrNull()
            }
            platePending.remove(key)
            if (mat != null) { plateMats[key] = mat; updatePlate() }
        }
    }

    /** Models loaded by BoardView: the board and one template per stone colour. */
    fun install(board: Entity?, stoneTemplates: List<Entity>) {
        boardModel = board
        this.stoneTemplates = stoneTemplates
        tryBuild()
    }

    private fun tryBuild() {
        val r = res ?: return
        val templates = stoneTemplates ?: return
        if (built) return
        built = true
        val t0 = SystemClock.elapsedRealtime()
        root.addChild(tiltRoot)
        tiltRoot.addChild(boardRoot)
        boardRoot.transform().setPosition(Vector3(0f, 0f, -PitLayout.BOARD_D / 2))
        buildLight()
        val board = boardModel
        if (board != null) {
            board.setName("boardSkin")
            boardRoot.addChild(board)
        } else {
            add(r.fallbackSlab, r.slabMat, P3(0f, -PitLayout.BOARD_T / 2, 0f), "boardFallback", flat = false)
            Log.w(TAG, "board.glb missing: fallback slab")
        }
        // A thin collider on the board top: a tap on the board but not a pit cancels the preview.
        boardRoot.addChild(Entity().apply {
            setName(BOARD_NAME)
            transform().setPosition(Vector3(0f, -0.002f, 0f))
            components.set(CollisionComponent(collisionShape = listOf(r.boardShape), physicsMaterial = r.physics))
            components.set(InteractableComponent())
        })
        // Pit colliders: 6.4 cm boxes, 5 cm tall, covering the stones piled in them (D17). Stores are not tap targets.
        for (pit in (0..5) + (7..12)) {
            val c = PitLayout.center(pit)
            boardRoot.addChild(Entity().apply {
                setName("pit:$pit")
                transform().setPosition(Vector3(c.x, PitLayout.COLLIDER_CENTER_Y, c.z))
                components.set(CollisionComponent(collisionShape = listOf(r.pitShape), physicsMaterial = r.physics))
                components.set(InteractableComponent())
            })
        }
        // Stones: 12 clones of each of the four greys, colour order shuffled with a fixed seed.
        val rnd = Random(48)
        val colours = (0 until Kalah.TOTAL).map { it % 4 }.shuffled(rnd)
        for (i in 0 until Kalah.TOTAL) {
            val tpl = templates[colours[i] % templates.size]
            val e = tpl.clone(Entity.CloneOptions(recursive = true, shouldShareMaterialInstance = true)) ?: continue
            e.setName("stone:$i")
            e.enabled = true
            boardRoot.addChild(e)
            stones += Stone(e, P3((rnd.nextFloat() - 0.5f) * 0.002f, 0f, (rnd.nextFloat() - 0.5f) * 0.002f), rnd.nextFloat() * (2 * PI).toFloat())
        }
        // Marks (all flat textured planes on the board top).
        repeat(6) { litRings += add(r.unitPlane, r.litMat, P3(0f, 0.0012f, 0f), "lit:$it", scale = 0.074f).also { it.enabled = false } }
        repeat(14) { pathDots += add(r.unitPlane, r.dotMat, P3(0f, 0.0018f, 0f), "dot:$it", scale = 0.013f).also { it.enabled = false } }
        selectRing = add(r.unitPlane, r.selectMat, P3(0f, 0.0016f, 0f), "selectRing", scale = 0.070f).also { it.enabled = false }
        endRing = add(r.unitPlane, r.endMat, P3(0f, 0.0020f, 0f), "endRing", scale = 0.078f).also { it.enabled = false }
        captureRing = add(r.unitPlane, r.captureMat, P3(0f, 0.0020f, 0f), "captureRing", scale = 0.074f).also { it.enabled = false }
        cross = add(r.unitPlane, r.crossMat, P3(0f, 0.045f, 0f), "cross", scale = 0.05f).also { it.enabled = false }
        turnGlow = add(r.unitPlane, r.turnMat, P3(0f, 0.0010f, 0f), "turnGlow", scale = 0.11f).also { it.enabled = false }
        lastRing = add(r.unitPlane, r.lastMat, P3(0f, 0.0014f, 0f), "lastRing", scale = 0.066f).also { it.enabled = false }
        for (cell in 0 until Kalah.CELLS) {
            val at = PitLayout.digitTile(cell)
            val store = Kalah.isStore(cell)
            digitTiles += add(r.unitPlane, r.digitBlank, at, "digit:$cell", scaleX = if (store) 0.030f else 0.024f, scaleZ = if (store) 0.024f else 0.016f)
        }
        // Seat plate: upright, outside the tilt, positioned in placeRoot().
        seatPlate = ModelEntity(r.unitPlane, r.digitBlank).apply {
            setName(PLATE_NAME)
            transform().setScaleVector(Vector3(PitLayout.SEAT_W, PitLayout.SEAT_H, 1f))
            enabled = false
        }
        root.addChild(seatPlate!!)
        placeRoot()
        syncStones(input.cells, snap = true)
        Log.i(TAG, "SCENE built in ${SystemClock.elapsedRealtime() - t0} ms stones=${stones.size} board=${board != null}")
        apply(input, force = true)
    }

    private fun buildLight() {
        runCatching {
            val light = Entity().apply { setName("keyLight") }
            light.components.set(DirectionalLightComponent(Color4(1f, 0.94f, 0.86f, 1f), lightIntensity, true))
            // From the front right, above: yaw 35° to the right, pitch 50° down.
            val q = Quat(Vector3(0f, 1f, 0f), (35 * PI / 180).toFloat()).times(Quat(Vector3(1f, 0f, 0f), (-50 * PI / 180).toFloat()))
            light.transform().setQuaternion(q)
            root.addChild(light)
            keyLight = light
        }.onFailure { Log.w(TAG, "LIGHT unavailable: $it") }
    }

    private var keyLight: Entity? = null
    var lightIntensity = 1.0f
        set(value) {
            field = value
            keyLight?.components?.get(DirectionalLightComponent::class.java)?.intensity = value
        }

    private fun add(
        mesh: MeshResource, material: Material, at: P3, name: String, scale: Float = 1f, scaleX: Float = scale, scaleZ: Float = scale, flat: Boolean = true,
    ): ModelEntity {
        val e = ModelEntity(mesh, material).apply {
            setName(name)
            transform().setPosition(Vector3(at.x, at.y, at.z))
            if (flat) {
                // createPlane is vertical (XY, facing +Z): pitch -90° lays it on the board, texture top toward the far edge.
                transform().setQuaternion(Quat(Vector3(1f, 0f, 0f), (-PI / 2).toFloat()))
                transform().setScaleVector(Vector3(scaleX, scaleZ, 1f))
            }
        }
        boardRoot.addChild(e)
        return e
    }

    // ------------------------------------------------------------------ layout

    /** The volume's half extents, from BoardView. The lowest point of the tipped board rests just above the floor. */
    fun setRegion(half: P3) {
        val known = regionHalf
        if (known != null && kotlin.math.abs(known.x - half.x) < 1e-4f && kotlin.math.abs(known.y - half.y) < 1e-4f) return
        regionHalf = half
        Log.i(TAG, "REGION half=(${half.x}, ${half.y}, ${half.z})")
        placeRoot()
    }

    private fun placeRoot() {
        val half = regionHalf ?: P3(0.36f, 0.14f, 0.16f)
        val a = (tiltNow * PI / 180).toFloat()
        val y = -half.y + FLOOR_LIFT + PitLayout.BOARD_T * cos(a)
        val nearZ = minOf(half.z - 0.035f, PitLayout.BOARD_D / 2 + 0.02f)
        tiltRoot.transform().setPosition(Vector3(0f, y, nearZ))
        tiltRoot.transform().setQuaternion(Quat(Vector3(1f, 0f, 0f), a))
        // Seat plate: the board-space far point carried through the tilt, but the card itself stays upright.
        val p = PitLayout.SEAT_PLATE
        val bz = p.z - PitLayout.BOARD_D / 2   // relative to the pivot on the near edge
        val wy = y + p.y * cos(a) - bz * sin(a)
        val wz = nearZ + p.y * sin(a) + bz * cos(a)
        seatPlate?.transform()?.setPosition(Vector3(0f, wy, wz))
    }

    // ------------------------------------------------------------------ state

    fun apply(next: BoardInput, force: Boolean = false) {
        val prev = input
        input = next
        if (!built) return
        tiltTarget = next.tiltDeg.toFloat()
        if (next.reducedMotion && tiltNow != tiltTarget) { tiltNow = tiltTarget; placeRoot() }
        // A new game, a restore, an undo: whatever is in flight ends, stones go where the position says.
        if (force || next.gameId != appliedGame) {
            appliedGame = next.gameId
            timeline = null
            playingId = null
            syncStones(next.cells, snap = true)
        }
        val sow = next.sow
        if (sow != null && next.sowId != null && next.sowId != playingId) {
            timeline?.finish()
            playingId = next.sowId
            beginSow(next.sowId, sow, next.sowByAi, next.reducedMotion)
        } else if (sow == null && timeline == null) {
            syncStones(next.cells, snap = false)
        }
        if (next.alertId != null && next.alertId != shownAlert) {
            shownAlert = next.alertId
            crossUntil = clock + 0.3f
        }
        if (next.againSide != null && prev.againSide == null) glowFlashUntil = clock + 0.3f
        updateMarks()
        refreshDigits()
        updatePlate()
        if (force || prev.lit != next.lit || prev.previewPit != next.previewPit || prev.phase != next.phase) placeIdleStones()
    }

    /** Puts the stones of each cell on that cell's slots, moving as few stones as possible. */
    private fun syncStones(cells: List<Int>, snap: Boolean) {
        if (stones.isEmpty()) return
        var changed = false
        val spare = ArrayList<Int>()
        for (c in 0 until Kalah.CELLS) {
            val list = cellStones[c]
            while (list.size > cells[c]) { spare += list.removeAt(list.size - 1); changed = true }
        }
        val unassigned = (0 until stones.size).filter { id -> cellStones.none { id in it } && id !in spare }
        spare += unassigned
        for (c in 0 until Kalah.CELLS) {
            val list = cellStones[c]
            while (list.size < cells[c] && spare.isNotEmpty()) { list += spare.removeAt(0); changed = true }
        }
        if (changed || snap) placeIdleStones()
    }

    private fun placeIdleStones() {
        if (timeline != null) return
        for (c in 0 until Kalah.CELLS) {
            val lift = if (input.phase == Phase.PREVIEW && input.previewPit == c) PREVIEW_LIFT else 0f
            cellStones[c].forEachIndexed { i, id -> placeStone(id, PitLayout.slot(c, i) + stones[id].jitter + P3(0f, lift, 0f)) }
        }
    }

    private fun placeStone(id: Int, p: P3, yawExtra: Float = 0f) {
        val s = stones[id]
        s.pos = p
        s.entity.transform().setPosition(Vector3(p.x, p.y, p.z))
        s.entity.transform().setQuaternion(Quat(Vector3(0f, 1f, 0f), s.yaw + yawExtra))
    }

    // ------------------------------------------------------------------ sowing

    private fun beginSow(id: Int, r: SowResult, byAi: Boolean, reduced: Boolean) {
        // Bring the stones to the position before the move first (a restore or an undo may have moved things).
        syncStones(r.before.cells, snap = true)
        val clips = ArrayList<Clip>()
        val hand = ArrayList(cellStones[r.from])
        cellStones[r.from].clear()
        var step = 0
        fun report(s: Int) { if (s > step) { step = s; onEvent(SceneEvent.Step(id, s)) } }
        // Reserve each stone's destination slot now, in drop order.
        val destinations = ArrayList<Pair<Int, Int>>()   // stone id → slot index in target cell
        for ((k, cell) in r.drops.withIndex()) {
            val sid = hand[k]
            cellStones[cell].add(sid)
            destinations += sid to (cellStones[cell].size - 1)
        }
        if (reduced) {
            // Reduced motion: 150 ms straight to the final layout.
            val starts = hand.map { stones[it].pos }
            clips += Clip(0f, 0.15f, onStart = { report(1) }, update = { u ->
                hand.forEachIndexed { k, sid ->
                    val (_, slotIdx) = destinations[k]
                    placeStone(sid, starts[k].lerp(PitLayout.slot(r.drops[k], slotIdx) + stones[sid].jitter, u))
                }
            }, onEnd = { report(1 + r.drops.size) })
            appendCaptureAndSweep(clips, r, 0.15f, 0.12f, id, ::report)
        } else {
            val per = if (byAi) 0.070f else 0.110f
            val flight = per * 1.6f
            val grab = 0.20f
            val origin = PitLayout.center(r.from)
            val rnd = Random(id)
            val offsets = hand.map { P3((rnd.nextFloat() - 0.5f) * 2 * PitLayout.HANDFUL_R * 0.8f, rnd.nextFloat() * 0.012f, (rnd.nextFloat() - 0.5f) * 2 * PitLayout.HANDFUL_R * 0.8f) }
            val starts = hand.map { stones[it].pos }
            // The handful's centre: above the origin, then above each target in turn.
            val stops = listOf(origin) + r.drops.map { PitLayout.center(it) }
            fun handAt(t: Float): P3 {
                val k = ((t - grab) / per).coerceAtLeast(-1f)
                val i = kotlin.math.floor(k).toInt()
                val a = stops[(i + 1).coerceIn(0, stops.size - 1)]
                val b = stops[(i + 2).coerceIn(0, stops.size - 1)]
                val f = (k - i).coerceIn(0f, 1f)
                val c = if (k < 0f) stops[0] else a.lerp(b, Easing.inOut(f) * 0.6f)
                return P3(c.x, PitLayout.HANDFUL_LIFT, c.z)
            }
            var released = 0
            // Grab: every stone of the pit rises into a cluster 8 cm over the rim.
            clips += Clip(0f, grab, onStart = { onEvent(SceneEvent.Sound(SoundKind.GRAB, 1f, pan(origin))); report(1) }, update = { u ->
                val e = Easing.out(u)
                val h = handAt(0f)
                hand.forEachIndexed { k, sid -> placeStone(sid, starts[k].lerp(h + offsets[k], e) + P3(0f, 0.01f * Easing.hump(u), 0f), e * 0.6f) }
            })
            // The cluster drifts along the row while stones leave it one by one.
            val handEnd = grab + per * r.drops.size
            clips += Clip(grab, handEnd - grab + 0.001f, update = { u ->
                val t = grab + u * (handEnd - grab)
                val h = handAt(t)
                for (k in released until hand.size) placeStone(hand[k], h + offsets[k] + P3(0f, 0.002f * sin(t * 30f + k), 0f), 0.6f)
            })
            for ((k, cell) in r.drops.withIndex()) {
                val sid = hand[k]
                val (_, slotIdx) = destinations[k]
                val launch = grab + per * k
                val target = PitLayout.slot(cell, slotIdx) + stones[sid].jitter
                val loud = r.drops.size <= 10 || k % 2 == 1 || k == r.drops.lastIndex
                clips += Clip(launch, flight, onStart = { released = maxOf(released, k + 1) }, update = { u ->
                    val from = handAt(launch) + offsets[k]
                    val e = Easing.inQuad(u) * 0.35f + u * 0.65f
                    val p = from.lerp(target, e) + P3(0f, 0.02f * Easing.hump(u), 0f)
                    placeStone(sid, p, 0.6f * (1 - u))
                }, onEnd = {
                    placeStone(sid, target)
                    if (loud) {
                        val count = cellStones[cell].indexOf(sid) + 1
                        val store = Kalah.isStore(cell)
                        onEvent(SceneEvent.Sound(if (store) SoundKind.STORE else SoundKind.DROP, if (store) 1f else 0.9f + minOf(count, 12) * 0.02f, pan(PitLayout.center(cell))))
                    }
                    if (Kalah.isStore(cell) && k == r.drops.lastIndex) glowFlash(cell)
                    report(2 + k)
                })
            }
            appendCaptureAndSweep(clips, r, grab + per * (r.drops.size - 1) + flight + 0.08f, 1f, id, ::report)
        }
        timeline = Timeline(clips, tail = 0.05f) {
            timeline = null
            report(r.stepCount)
            Log.i(TAG, "SOW played id=$id")
            onEvent(SceneEvent.Done(id))
            placeIdleStones()
        }
        Log.i(TAG, "SOW play id=$id from=${Kalah.letter(r.from)} drops=${r.drops.size} total=${"%.2f".format(timeline?.total ?: 0f)} reduced=$reduced ai=$byAi")
    }

    private fun appendCaptureAndSweep(clips: MutableList<Clip>, r: SowResult, startAt: Float, factor: Float, id: Int, report: (Int) -> Unit) {
        var t = startAt
        var stepBase = 1 + r.drops.size
        val cap = r.capture
        if (cap != null) {
            val dur = 0.40f * factor
            val movers = ArrayList<Int>()
            val starts = ArrayList<P3>()
            val targets = ArrayList<P3>()
            val step = ++stepBase
            clips += Clip(t, dur, onStart = {
                movers.clear(); starts.clear(); targets.clear()
                for (cell in listOf(cap.across, cap.landing)) {
                    for (sid in cellStones[cell]) { movers += sid; starts += stones[sid].pos }
                    cellStones[cell].clear()
                }
                for (sid in movers) { cellStones[cap.store].add(sid); targets += PitLayout.slot(cap.store, cellStones[cap.store].size - 1) + stones[sid].jitter }
                onEvent(SceneEvent.Sound(SoundKind.CAPTURE, 1f, pan(PitLayout.center(cap.store))))
            }, update = { u ->
                val e = Easing.inOut(u)
                movers.forEachIndexed { i, sid -> placeStone(sid, starts[i].lerp(targets[i], e) + P3(0f, 0.05f * Easing.hump(u), 0f)) }
            }, onEnd = { glowFlash(cap.store); report(step) })
            t += dur + 0.05f
        }
        for (sw in r.sweeps) {
            val dur = 0.60f * factor
            val movers = ArrayList<Int>()
            val starts = ArrayList<P3>()
            val targets = ArrayList<P3>()
            val step = ++stepBase
            clips += Clip(t, dur, onStart = {
                movers.clear(); starts.clear(); targets.clear()
                for ((cell, _) in sw.taken) {
                    for (sid in cellStones[cell]) { movers += sid; starts += stones[sid].pos }
                    cellStones[cell].clear()
                }
                for (sid in movers) { cellStones[sw.store].add(sid); targets += PitLayout.slot(sw.store, cellStones[sw.store].size - 1) + stones[sid].jitter }
                onEvent(SceneEvent.Sound(SoundKind.SWEEP, 1f, pan(PitLayout.center(sw.store))))
            }, update = { u ->
                movers.forEachIndexed { i, sid ->
                    val local = ((u * 1.4f) - i * 0.4f / maxOf(1, movers.size)).coerceIn(0f, 1f)
                    placeStone(sid, starts[i].lerp(targets[i], Easing.inOut(local)) + P3(0f, 0.04f * Easing.hump(local), 0f))
                }
            }, onEnd = { report(step) })
            t += dur + 0.05f
        }
    }

    private fun glowFlash(cell: Int) {
        glowCell = cell
        glowFlashUntil = clock + 0.3f
    }

    private fun pan(p: P3): Float = (p.x / (PitLayout.BOARD_W / 2)).coerceIn(-1f, 1f) * 0.6f

    // ------------------------------------------------------------------ marks, digits, plate

    private fun flatAt(e: ModelEntity?, cell: Int, y: Float, sx: Float, sz: Float) {
        e ?: return
        val c = PitLayout.center(cell)
        e.transform().setPosition(Vector3(c.x, y, c.z))
        e.transform().setScaleVector(Vector3(sx, sz, 1f))
        e.enabled = true
    }

    private fun ringScale(cell: Int, base: Float): Pair<Float, Float> =
        if (Kalah.isStore(cell)) (PitLayout.STORE_A * 2 + 0.012f) to (PitLayout.STORE_B * 2 + 0.012f) else base to base

    private fun updateMarks() {
        val s = input
        val idleish = s.phase == Phase.IDLE || s.phase == Phase.PREVIEW
        // Lit pits: a soft glow round every pit you may tap.
        litRings.forEachIndexed { i, ring ->
            val pit = s.lit.getOrNull(i)
            if (pit == null || s.phase == Phase.PREVIEW && pit == s.previewPit) { ring.enabled = false; return@forEachIndexed }
            flatAt(ring, pit, 0.0012f, 0.074f, 0.074f)
        }
        // Preview (S04) or hint (T7): the same marks from the same function.
        val (focusPit, result, mat) = when {
            s.phase == Phase.PREVIEW && s.previewPit != null && s.preview != null -> Triple(s.previewPit, s.preview, res?.selectMat)
            s.phase == Phase.IDLE && s.hintPit != null && s.hint != null -> Triple(s.hintPit, s.hint, res?.hintMat)
            else -> Triple(null, null, null)
        }
        if (focusPit != null && result != null && mat != null) {
            selectRing?.components?.get(ModelComponent::class.java)?.materials?.set(0, mat)
            flatAt(selectRing, focusPit, 0.0016f, 0.070f, 0.070f)
            val path = result.drops.distinct()
            pathDots.forEachIndexed { i, dot ->
                val cell = path.getOrNull(i)
                if (cell == null || cell == result.last) { dot.enabled = false; return@forEachIndexed }
                val c = PitLayout.center(cell)
                val z = if (Kalah.isStore(cell)) c.z + 0.078f else c.z + (if (cell in 0..5) 0.036f else -0.036f)
                dot.transform().setPosition(Vector3(c.x, 0.0018f, z))
                dot.enabled = true
            }
            val (ex, ez) = ringScale(result.last, 0.078f)
            flatAt(endRing, result.last, 0.0020f, ex, ez)
            val cap = result.capture
            if (cap != null) flatAt(captureRing, cap.across, 0.0020f, 0.074f, 0.074f) else captureRing?.enabled = false
        } else {
            selectRing?.enabled = false
            endRing?.enabled = false
            captureRing?.enabled = false
            pathDots.forEach { it.enabled = false }
        }
        // Last move's source pit: a dashed ring while nothing else is going on.
        val last = s.lastFrom
        if (last != null && !Kalah.isStore(last) && (s.phase == Phase.IDLE || s.phase == Phase.THINKING) && last != focusPit) flatAt(lastRing, last, 0.0014f, 0.066f, 0.066f) else lastRing?.enabled = false
        // Whose turn: a glow round that side's store (both on a draw, the winner's at the end).
        val turnCell = when {
            s.ended -> s.winner?.let { Kalah.store(it) }
            s.againSide != null -> Kalah.store(s.againSide)
            s.phase == Phase.THINKING -> Kalah.NORTH_STORE
            idleish -> Kalah.SOUTH_STORE
            s.phase == Phase.SOWING -> Kalah.store(s.toMove.let { if (s.sow != null) s.sow.side else it })
            else -> null
        }
        if (turnCell != null) {
            val (gx, gz) = ringScale(turnCell, 0.11f)
            flatAt(turnGlow, turnCell, 0.0010f, gx * 1.18f, gz * 1.12f)
        } else turnGlow?.enabled = false
        if (cross != null) {
            val pit = s.alertPit
            if (pit != null && (clock < crossUntil || s.frozen)) {
                val c = PitLayout.center(pit)
                cross?.transform()?.setPosition(Vector3(c.x, 0.03f, c.z))
                cross?.enabled = true
            } else cross?.enabled = false
        }
    }

    private fun refreshDigits() {
        if (!built) return
        val s = input
        digitTiles.forEachIndexed { cell, tile ->
            tile.enabled = s.showDigits
            if (!s.showDigits) return@forEachIndexed
            val key = if (s.testGlyph) "F↑" else s.shown[cell].toString()
            val mat = digitMats[key]
            if (mat != null) tile.components[ModelComponent::class.java]?.materials?.set(0, mat)
            else scope.launch { ensureDigit(key) }
        }
    }

    private fun updatePlate() {
        val plate = seatPlate ?: return
        val s = input
        if (s.plateText.isBlank()) { plate.enabled = false; return }
        val key = (if (s.testGlyph) "F↑ " else "") + s.plateText + "|" + s.plateDot
        val mat = plateMats[key]
        if (mat == null) { requestPlate(key, (if (s.testGlyph) "F↑ " else "") + s.plateText, s.plateDot); return }
        if (plateKey != key) {
            plate.components[ModelComponent::class.java]?.materials?.set(0, mat)
            plateKey = key
        }
        plate.enabled = true
        // Only a tap target while the control window is closed (then it reopens it).
        val hasCollider = plate.components[CollisionComponent::class.java] != null
        val r = res ?: return
        if (s.plateTappable && !hasCollider) {
            plate.components.set(CollisionComponent(collisionShape = listOf(r.plateShape), physicsMaterial = r.physics))
            plate.components.set(InteractableComponent())
        } else if (!s.plateTappable && hasCollider) {
            plate.components.remove(CollisionComponent::class.java)
            plate.components.remove(InteractableComponent::class.java)
        }
    }

    // ------------------------------------------------------------------ per frame

    fun onFrame(deltaSeconds: Float) {
        if (!built) return
        val dt = deltaSeconds.coerceIn(0f, 0.1f)
        clock += dt
        if (tiltNow != tiltTarget) {
            val step = dt * 50f // 15° in 300 ms
            tiltNow = if (tiltNow < tiltTarget) minOf(tiltTarget, tiltNow + step) else maxOf(tiltTarget, tiltNow - step)
            placeRoot()
        }
        timeline?.advance(dt)
        val s = input
        // Breathing turn glow (not with reduced motion); a pop when a stone lands in a store or on an extra turn.
        turnGlow?.let { g ->
            if (!g.enabled) return@let
            val cell = when {
                clock < glowFlashUntil -> glowCell
                else -> null
            }
            val pulse = if (s.reducedMotion) 1f else 1f + 0.04f * sin(clock * 3.2f)
            val pop = if (cell != null) 1.12f else 1f
            val base = g.transform().scaleVector
            val target = when {
                s.ended -> s.winner?.let { Kalah.store(it) }
                s.againSide != null -> Kalah.store(s.againSide)
                s.phase == Phase.THINKING -> Kalah.NORTH_STORE
                else -> Kalah.SOUTH_STORE
            } ?: Kalah.SOUTH_STORE
            val (gx, gz) = ringScale(target, 0.11f)
            if (base != null) g.transform().setScaleVector(Vector3(gx * 1.18f * pulse * pop, gz * 1.12f * pulse * pop, 1f))
        }
        if (cross?.enabled == true && clock >= crossUntil && !s.frozen) cross?.enabled = false
        // Lit rings breathe a little in the coach game.
        if (s.coach && !s.reducedMotion) {
            val k = 0.074f * (1f + 0.05f * sin(clock * 4f))
            for (r in litRings) if (r.enabled) r.transform().setScaleVector(Vector3(k, k, 1f))
        }
    }

    /** Debug: finish the animation in flight now. */
    fun finishNow() { timeline?.finish() }

    fun evidence(): String {
        val half = regionHalf
        val v = runCatching { root.getVisualBounds(root.getParent(), true, true) }.getOrNull()
        return "regionHalf=$half tilt=$tiltNow visualMin=${v?.min} visualMax=${v?.max} stones=${stones.size} cells=${cellStones.map { it.size }} built=$built"
    }

    fun dispose() {
        timeline = null
        scope.cancel()
        runCatching { root.destroy(true) }
        res?.all?.forEach { runCatching { it.close() } }
        res = null
    }

    companion object {
        const val TAG = "SeedPits"
        const val BOARD_NAME = "board"
        const val PLATE_NAME = "seatPlate"
        const val FLOOR_LIFT = 0.012f
        const val PREVIEW_LIFT = 0.006f

        fun pitOf(name: String?): Int? = name?.takeIf { it.startsWith("pit:") }?.substring(4)?.toIntOrNull()
    }
}
