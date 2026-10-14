package com.doublegate.rustynes

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityOptions
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.lang.ref.WeakReference
import kotlin.math.roundToInt
import uniffi.rustynes_mobile.NesController
import uniffi.rustynes_mobile.RaLoginStatus

/**
 * AYN Thor dual-screen support.
 *
 * The Thor's lower panel (3.92", 1240 x 1080) is a second built-in display.
 * Android only lets an app draw there through a window that lives ON that
 * display, so RustyNES opens [ThorSecondScreenActivity] on it, in its own task,
 * whenever the main activity is visible, and closes it when the app leaves the
 * screen. It shows either:
 *
 *  - the matching `.exp` expansion for the running game (a live panel drawn
 *    from the game's memory), or
 *  - the RustyNES panel: game controls, expansion import/management and the
 *    RetroAchievements status and list. Never an empty screen.
 *
 * The bottom window is created not-focusable, so the hardware controller and
 * the D-pad always stay with the game on the top screen; any key that still
 * reaches it is forwarded to the main activity.
 */
object ThorDualScreen {
    private const val TAG = "RustyNES-thor"
    const val PREFS = "thor_dual_screen"
    const val KEY_ENABLED = "enabled"

    @Volatile
    var emulator: EmulatorHandle? = null

    @Volatile
    var romName: String? = null

    /** The app's settings object (set by the top screen), shared so both screens agree. */
    @Volatile
    var settings: AppSettings? = null

    /** Top-screen state mirrored for the bottom page. */
    @Volatile
    var raStatusText: String = ""

    @Volatile
    var paused = false

    @Volatile
    var turbo = false

    /** Actions the bottom screen asks the top screen to run (see EmulatorScreen). */
    val commands = kotlinx.coroutines.flow.MutableSharedFlow<ThorCommand>(extraBufferCapacity = 16)

    fun send(cmd: ThorCommand) {
        commands.tryEmit(cmd)
    }

    var main: WeakReference<Activity> = WeakReference(null)
    var bottom: WeakReference<ThorSecondScreenActivity> = WeakReference(null)

    /** Set while a system picker we opened covers a screen, so it isn't taken as "app left". */
    @Volatile
    var pickerOpen = false

    /** Set when the main activity's own "Import expansion" was used: the bottom page refreshes. */
    @Volatile
    var expansionsChanged = 0

    private var displayListener: DisplayManager.DisplayListener? = null
    private val ui = Handler(Looper.getMainLooper())
    private var mainStarted = false

    fun isEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(activity: Activity, on: Boolean) {
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, on).apply()
        if (on) show(activity) else close()
    }

    /**
     * The bottom display: the first valid public display other than the main
     * one, preferring built-in panels (the Thor's lower screen) over HDMI /
     * cast presentation displays.
     */
    fun findBottomDisplay(ctx: Context): Display? {
        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return null
        var fallback: Display? = null
        for (d in dm.displays) {
            if (d.displayId == Display.DEFAULT_DISPLAY || !d.isValid) continue
            if ((d.flags and Display.FLAG_PRIVATE) != 0) continue
            if ((d.flags and Display.FLAG_PRESENTATION) == 0) return d
            if (fallback == null) fallback = d
        }
        return fallback
    }

    fun hasBottomDisplay(ctx: Context): Boolean = findBottomDisplay(ctx) != null

    /** The Activity behind a (possibly wrapped) Compose / dialog context. */
    fun findActivity(ctx: Context): Activity? {
        var c: Context? = ctx
        while (c != null) {
            if (c is Activity) return c
            c = (c as? android.content.ContextWrapper)?.baseContext
        }
        return main.get()
    }

    /** Called from MainActivity.onCreate. */
    fun attach(activity: Activity, handle: EmulatorHandle) {
        main = WeakReference(activity)
        emulator = handle
        // Debug builds only (like the ROM autoload): pick up .exp files pushed to
        // <external files>/expansions/ so CI and on-device tests need no picker.
        if (BuildConfig.DEBUG) {
            Thread {
                val drop = java.io.File(activity.getExternalFilesDir(null), "expansions")
                drop.listFiles { f -> f.name.endsWith(".exp") }?.forEach { f ->
                    runCatching { ExpansionStore.import(activity, android.net.Uri.fromFile(f)) }
                        .onSuccess { f.delete(); expansionsChanged++; Log.i(TAG, "debug-imported ${it.id}") }
                        .onFailure { Log.w(TAG, "debug import of ${f.name} failed", it) }
                }
            }.start()
        }
        val dm = activity.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
        if (displayListener == null) {
            val l = object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) {
                    main.get()?.let { if (mainStarted) show(it) }
                }

                override fun onDisplayRemoved(displayId: Int) {
                    val b = bottom.get() ?: return
                    if (b.displayIdCompat() == displayId) b.finish()
                }

                override fun onDisplayChanged(displayId: Int) = Unit
            }
            dm.registerDisplayListener(l, ui)
            displayListener = l
        }
    }

    fun detach(activity: Activity) {
        if (main.get() === activity) {
            displayListener?.let {
                (activity.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)?.unregisterDisplayListener(it)
            }
            displayListener = null
            if (activity.isFinishing) close()
        }
    }

    fun onMainStart(activity: Activity) {
        mainStarted = true
        main = WeakReference(activity)
        show(activity)
    }

    fun onMainStop(activity: Activity) {
        mainStarted = false
        if (activity.isChangingConfigurations) return
        // Closing waits a moment: a picker or the bottom screen itself may be what
        // covered the main activity, and those are not the user leaving the app.
        ui.postDelayed({
            if (!mainStarted && !pickerOpen) close()
        }, 700)
    }

    /** Open the bottom page on the second display if there is one and it is not already up. */
    fun show(activity: Activity) {
        if (!isEnabled(activity)) return
        val target = findBottomDisplay(activity) ?: return
        val current = bottom.get()
        if (current != null && !current.isFinishing && !current.isDestroyed) return
        val intent = Intent(activity, ThorSecondScreenActivity::class.java)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION,
            )
        val opts = ActivityOptions.makeBasic().setLaunchDisplayId(target.displayId)
        try {
            activity.startActivity(intent, opts.toBundle())
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't open the bottom screen on display ${target.displayId}", e)
        }
    }

    fun close() {
        bottom.get()?.let { if (!it.isFinishing) it.finish() }
    }

    /** Forward a hardware key / joystick event that landed on the bottom screen to the game. */
    fun forwardKey(event: KeyEvent): Boolean = (main.get() as? MainActivity)?.handleForwardedKey(event) ?: false

    fun forwardMotion(event: MotionEvent): Boolean = (main.get() as? MainActivity)?.handleForwardedMotion(event) ?: false

    /**
     * Give the input focus back to the game's task (top screen). Opening the bottom
     * page makes its display the focused one; the game should own the controls.
     */
    fun focusGame(ctx: Context) {
        val m = main.get() ?: return
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager ?: return
        runCatching {
            am.appTasks.firstOrNull { t ->
                val info = t.taskInfo
                val id = when {
                    info == null -> -1
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> info.taskId
                    else -> @Suppress("DEPRECATION") info.id
                }
                id == m.taskId
            }?.moveToFront()
        }.onFailure { Log.w(TAG, "Couldn't return focus to the game", it) }
    }

    /** Export the CPU bus (`$0000-$FFFF`) to Download/RustyNES/ for finding addresses. */
    fun dumpMemory(ctx: Context): String {
        val ctrl = emulator?.controller ?: return "No game running"
        // In 4 KB pieces so the emulator lock is never held for long.
        val bytes = runCatching {
            val out = java.io.ByteArrayOutputStream(0x10000)
            for (chunk in 0 until 16) out.write(ctrl.debugReadMemory((chunk * 0x1000).toUShort(), 0x1000u))
            out.toByteArray()
        }.getOrNull() ?: return "Couldn't read memory"
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "Needs Android 10+"
        val name = "ram-" + (romName ?: "game").replace(Regex("[^A-Za-z0-9._-]"), "_").take(40) +
            "-" + System.currentTimeMillis() / 1000 + ".bin"
        val cv = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/RustyNES")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
            ?: return "Couldn't create the file"
        ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
        return "Saved Download/RustyNES/$name (64 KB CPU bus, \$0000-\$FFFF)"
    }
}


/** What the bottom screen can ask the top screen to do. */
sealed class ThorCommand {
    object OpenRom : ThorCommand()
    object CloseRom : ThorCommand()
    object Reset : ThorCommand()
    object PauseToggle : ThorCommand()
    object TurboToggle : ThorCommand()
    object Screenshot : ThorCommand()
    object Settings : ThorCommand()
    object States : ThorCommand()
    object Controllers : ThorCommand()
    object Netplay : ThorCommand()
    object About : ThorCommand()
    object Menu : ThorCommand()
    data class RaLogin(val user: String, val password: String) : ThorCommand()
    object RaLogout : ThorCommand()
    data class RaEnabled(val on: Boolean) : ThorCommand()
    data class RaHardcore(val on: Boolean) : ThorCommand()
}

/**
 * The page on the Thor's lower screen: the RustyNES menu (game actions, display,
 * audio, emulation, input, RetroAchievements, expansions), or a game's `.exp`
 * panel when one matches the running ROM.
 */
class ThorSecondScreenActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var homeScroll: ScrollView
    private lateinit var home: LinearLayout
    private lateinit var expView: ExpansionView

    private lateinit var gameLine: TextView
    private lateinit var showPanelBtn: Button
    private lateinit var expList: LinearLayout
    private lateinit var raStatus: TextView
    private lateinit var raLoginBox: LinearLayout
    private lateinit var raSignedInBox: LinearLayout
    private lateinit var raList: LinearLayout
    private lateinit var userField: EditText
    private lateinit var passField: EditText

    /** Buttons whose text (and highlight) follow live state; refreshed together. */
    private val dynamic = ArrayList<() -> Unit>()

    private val ui = Handler(Looper.getMainLooper())
    private var worker: HandlerThread? = null
    private var workerHandler: Handler? = null

    @Volatile private var identityFor: ByteArray? = null
    private var identity: RomIdentity? = null
    @Volatile private var active: Expansion? = null
    @Volatile private var expVisible = false
    private var userChoseHome = false
    private var seenExpansionsChange = 0
    private val lastLookups = HashMap<String, Any>()
    private var sessionStart = SystemClock.elapsedRealtime()
    private var lastStatus = 0L
    private var raSignature = ""
    private var focusReturned = false

    @Volatile private var raLoggedIn = false
    @Volatile private var hasGame = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThorDualScreen.bottom = WeakReference(this)
        // The window must stay focusable: a non-focusable activity makes Android's input
        // dispatcher wait for a focused window on this display and raise an ANR at the first
        // key press. Controller input that lands here is forwarded to the game instead
        // (dispatchKeyEvent below), and the game's task gets the focus back after launch.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        window.decorView.setBackgroundColor(BG)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE

        root = FrameLayout(this)
        root.setBackgroundColor(BG)
        buildHome()
        expView = ExpansionView(
            this,
            onAction = { action -> onExpansionAction(action) },
            onLongPress = { toast(ThorDualScreen.dumpMemory(this)) },
        )
        expView.visibility = View.GONE
        root.addView(homeScroll, FrameLayout.LayoutParams(-1, -1))
        root.addView(expView, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        refreshDynamic()
    }

    fun displayIdCompat(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display?.displayId ?: -1
        else @Suppress("DEPRECATION") windowManager.defaultDisplay.displayId

    override fun onStart() {
        super.onStart()
        sessionStart = SystemClock.elapsedRealtime()
        val t = HandlerThread("thor-bottom").also { it.start() }
        worker = t
        workerHandler = Handler(t.looper).also { it.post(poll) }
        Log.i("RustyNES-thor", "bottom screen started on display ${displayIdCompat()}")
        // Debug builds: save what this screen shows, so CI can check it without a device.
        if (BuildConfig.DEBUG) {
            for (d in listOf(15000L, 40000L, 70000L)) ui.postDelayed({ debugSnapshot() }, d)
        }
    }

    private fun debugSnapshot() {
        if (isFinishing || root.width <= 0 || root.height <= 0) return
        runCatching {
            val bmp = android.graphics.Bitmap.createBitmap(root.width, root.height, android.graphics.Bitmap.Config.ARGB_8888)
            root.draw(android.graphics.Canvas(bmp))
            val f = java.io.File(getExternalFilesDir(null), "thor-bottom.png")
            f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            // A second image of the menu page (scrolled to the top), whatever page shows now.
            if (homeScroll.width > 0) {
                val h = home.height.coerceAtLeast(1)
                val menu = android.graphics.Bitmap.createBitmap(homeScroll.width, h, android.graphics.Bitmap.Config.ARGB_8888)
                val c = android.graphics.Canvas(menu)
                c.drawColor(BG)
                home.draw(c)
                java.io.File(getExternalFilesDir(null), "thor-menu.png").outputStream()
                    .use { menu.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
            Log.i(
                "RustyNES-thor",
                "snapshot ${root.width}x${root.height} expansion=${active?.id} " +
                    "page=${if (expVisible) "expansion" else "home"} rom=${ThorDualScreen.romName} " +
                    "running=${ThorDualScreen.emulator?.controller != null} " +
                    "installed=${ExpansionStore.all(this).map { it.id }}",
            )
        }.onFailure { Log.w("RustyNES-thor", "snapshot failed", it) }
    }

    override fun onStop() {
        super.onStop()
        workerHandler?.removeCallbacksAndMessages(null)
        worker?.quitSafely()
        worker = null
        workerHandler = null
    }

    override fun onDestroy() {
        super.onDestroy()
        if (ThorDualScreen.bottom.get() === this) ThorDualScreen.bottom = WeakReference(null)
    }

    // Back on the bottom screen only goes back to the RustyNES menu; it never closes it.
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (expView.visibility == View.VISIBLE) {
            userChoseHome = true
            showHome()
        }
    }

    /**
     * Controller buttons belong to the game even after a tap moved the focus down
     * here; Back, volume and typing (the login fields) stay on this screen.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        val local = code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_VOLUME_UP ||
            code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_MUTE
        val src = event.source
        val fromController = KeyEvent.isGamepadButton(code) ||
            (src and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (src and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
            ((src and InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD && event.device?.isVirtual == false)
        if (!local && fromController && ThorDualScreen.forwardKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onResume() {
        super.onResume()
        if (!focusReturned) {
            focusReturned = true
            ui.postDelayed({ ThorDualScreen.focusGame(this) }, 400)
        }
        refreshDynamic()
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if ((event.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK &&
            ThorDualScreen.forwardMotion(event)
        ) return true
        return super.dispatchGenericMotionEvent(event)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        ThorDualScreen.pickerOpen = false
        if (requestCode != REQ_IMPORT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        importFrom(this, uri) { msg ->
            toast(msg)
            refreshExpansions()
            active = null
            identityFor = null
            userChoseHome = false
        }
    }

    // ------------------------------------------------------------ polling

    private val poll = object : Runnable {
        override fun run() {
            val h = workerHandler ?: return
            try {
                tick()
            } catch (e: Exception) {
                Log.w("RustyNES-thor", "bottom screen update failed", e)
            }
            h.postDelayed(this, 250)
        }
    }

    /** Runs on the worker thread: everything that takes the emulator lock happens here. */
    private fun tick() {
        val emu = ThorDualScreen.emulator
        val ctrl = emu?.controller
        hasGame = ctrl != null
        val rom = emu?.romBytes
        if (rom !== identityFor) {
            identityFor = rom
            identity = rom?.let { RomIdentity(ThorDualScreen.romName, it) }
            lastLookups.clear()
            val exp = ExpansionStore.forRom(this, identity)
            ui.post { onExpansionResolved(exp) }
        }
        if (ThorDualScreen.expansionsChanged != seenExpansionsChange) {
            seenExpansionsChange = ThorDualScreen.expansionsChanged
            identityFor = null
            ui.post { refreshExpansions() }
        }
        val exp = active
        if (exp != null && ctrl != null && expVisible) {
            // Only the 256-byte pages the expansion uses, each one short bridge call.
            val bus = PagedBus { start -> ctrl.debugReadMemory(start.toUShort(), 0x100u) }
            val secs = (SystemClock.elapsedRealtime() - sessionStart) / 1000
            val ev = ValueEvaluator(exp.values, bus, lastLookups, secs)
            ev.prefetch()
            ui.post { if (active === exp) expView.values = ev }
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastStatus > 1000) {
            lastStatus = now
            val status = buildStatus(emu, ctrl)
            ui.post { applyStatus(status) }
        }
    }

    private class Status(
        val game: String,
        val ra: String,
        val loggedIn: Boolean,
        val achievements: List<Triple<String, String, Boolean>>,
        val signature: String,
    )

    private fun buildStatus(emu: EmulatorHandle?, ctrl: NesController?): Status {
        val s = ThorDualScreen.settings
        val game = when {
            ctrl == null -> "No game running. Tap Open game, or pick one on the top screen."
            else -> "Playing: " + (ThorDualScreen.romName ?: "game") + if (emu?.paused == true) "  (paused)" else ""
        }
        var loggedIn = false
        val list = ArrayList<Triple<String, String, Boolean>>()
        val lines = ArrayList<String>()
        if (s == null || !s.raEnabled) {
            lines += "RetroAchievements is off."
        } else {
            val mode = if (s.raHardcore) "Hardcore" else "Softcore"
            var stateLine: String? = null
            if (ctrl != null) runCatching {
                if (ctrl.raIsEnabled()) {
                    val user = ctrl.raUser()
                    stateLine = when (ctrl.raLoginStatus()) {
                        RaLoginStatus.LOGGED_IN -> {
                            loggedIn = true
                            "Signed in as ${user?.displayName ?: s.raUsername} · ${user?.score ?: 0u} points"
                        }
                        RaLoginStatus.LOGGING_IN -> "Signing in…"
                        RaLoginStatus.ERROR -> "Sign-in failed. Check the name and password."
                        else -> null
                    }
                    val sum = ctrl.raGameSummary()
                    if (sum.size >= 6 && sum[0] > 0u) {
                        lines += "This game: ${sum[2]} of ${sum[0]} unlocked · ${sum[5]} of ${sum[4]} points"
                    }
                    val rp = ctrl.raRichPresence()
                    if (rp.isNotEmpty()) lines += rp
                    for (a in ctrl.raAchievementList()) {
                        val pct = if (!a.unlocked && a.measuredPercent > 0f) " (${a.measuredPercent.toInt()}%)" else ""
                        list += Triple("${a.title} · ${a.points}$pct", a.description, a.unlocked)
                    }
                }
            }
            if (stateLine == null) {
                stateLine = when {
                    s.raToken.isNotEmpty() -> {
                        loggedIn = true
                        "Signed in as ${s.raUsername}. Achievements connect when a game starts."
                    }
                    ThorDualScreen.raStatusText.isNotEmpty() && ThorDualScreen.raStatusText != "Logged out" ->
                        ThorDualScreen.raStatusText
                    else -> "Not signed in."
                }
            }
            lines.add(0, "$stateLine  ·  $mode mode")
        }
        val ra = lines.joinToString("\n")
        val sig = ra + list.joinToString("|") { it.first + it.third }
        return Status(game, ra, loggedIn, list, sig)
    }

    private fun applyStatus(st: Status) {
        gameLine.text = st.game
        raLoggedIn = st.loggedIn
        refreshDynamic()
        if (st.signature == raSignature) return
        raSignature = st.signature
        raStatus.text = st.ra
        raList.removeAllViews()
        if (st.achievements.isNotEmpty()) {
            raList.addView(label("Achievements", 30f, MUTED, bold = true))
        }
        for ((name, desc, unlocked) in st.achievements) {
            val row = TextView(this)
            row.text = (if (unlocked) "★ " else "☆ ") + name + if (desc.isNotEmpty()) "\n     $desc" else ""
            row.setTextColor(if (unlocked) GOLD else MUTED)
            row.setTextSize(TypedValue.COMPLEX_UNIT_PX, 28f)
            row.setPadding(0, 6, 0, 6)
            raList.addView(row)
        }
    }

    // ------------------------------------------------------------ pages

    private fun onExpansionResolved(exp: Expansion?) {
        active = exp
        expView.expansion = exp
        if (exp != null && !userChoseHome) showExpansion() else if (exp == null) showHome()
        refreshDynamic()
    }

    private fun showExpansion() {
        homeScroll.visibility = View.GONE
        expView.visibility = View.VISIBLE
        expVisible = true
    }

    private fun showHome() {
        expView.visibility = View.GONE
        expVisible = false
        homeScroll.visibility = View.VISIBLE
        refreshExpansions()
    }

    private fun onExpansionAction(action: String) {
        when (action) {
            "menu", "save_quit" -> {
                userChoseHome = true
                showHome()
            }
            "screen:main" -> expView.page = "main"
            "screen:pixel" -> expView.page = "pixel"
        }
    }

    // ------------------------------------------------------------ the menu

    private fun settings(): AppSettings? = ThorDualScreen.settings

    private fun send(cmd: ThorCommand) {
        ThorDualScreen.send(cmd)
        ui.postDelayed({ refreshDynamic() }, 150)
    }

    private fun buildHome() {
        homeScroll = ScrollView(this)
        homeScroll.isFillViewport = true
        home = LinearLayout(this)
        home.orientation = LinearLayout.VERTICAL
        home.setPadding(36, 24, 36, 48)
        homeScroll.addView(home, ViewGroup.LayoutParams(-1, -2))

        home.addView(label("RustyNES", 50f, Color.WHITE, bold = true))
        gameLine = label("", 30f, MUTED)
        home.addView(gameLine)

        // Game
        home.addView(header("Game"))
        home.addView(grid(
            action("Open game") { send(ThorCommand.OpenRom) },
            action("Close game", enabledIf = { hasGame }) { send(ThorCommand.CloseRom) },
            action("Reset", enabledIf = { hasGame }) { send(ThorCommand.Reset) },
            toggle({ if (ThorDualScreen.paused) "Resume" else "Pause" }, { ThorDualScreen.paused }, { hasGame }) {
                send(ThorCommand.PauseToggle)
            },
            toggle({ "Fast-forward" }, { ThorDualScreen.turbo }, { hasGame }) { send(ThorCommand.TurboToggle) },
            action("Save states…", enabledIf = { hasGame }) { send(ThorCommand.States) },
            action("Screenshot", enabledIf = { hasGame }) { send(ThorCommand.Screenshot) },
            action("Menu bar on top") { send(ThorCommand.Menu) },
        ))

        // Settings that open full pages on the top screen
        home.addView(header("Settings"))
        home.addView(grid(
            action("All settings…") { send(ThorCommand.Settings) },
            action("Controllers & mapping…") { send(ThorCommand.Controllers) },
            action("Netplay…") { send(ThorCommand.Netplay) },
            action("About") { send(ThorCommand.About) },
        ))
        home.addView(label("These open on the top screen. With a controller, Start + Select opens the menu there too.", 26f, MUTED))

        // Display
        home.addView(header("Display"))
        home.addView(grid(
            cycler({ "Aspect: " + (settings()?.aspectMode?.label ?: "-") }) { s ->
                s.aspectMode = AspectMode.entries[(s.aspectMode.ordinal + 1) % AspectMode.entries.size]
            },
            toggle({ "Integer scaling" }, { settings()?.integerScale == true }) {
                settings()?.let { it.integerScale = !it.integerScale }
            },
            cycler({ "Crop: " + (settings()?.overscan?.label ?: "-") }) { s ->
                s.overscan = OverscanCrop.entries[(s.overscan.ordinal + 1) % OverscanCrop.entries.size]
            },
            cycler({ "Filter: " + (settings()?.filter?.label ?: "-") }) { s -> s.filter = s.filter.next() },
            toggle({ "Smooth scaling" }, { settings()?.smoothScaling == true }) {
                settings()?.let { it.smoothScaling = !it.smoothScaling }
            },
        ))

        // Audio
        home.addView(header("Audio"))
        home.addView(grid(
            toggle({ "Sound" }, { settings()?.muted == false }) { settings()?.let { it.muted = !it.muted } },
            action("Volume −") { settings()?.let { it.masterVolume = ((it.masterVolume * 10).roundToInt() - 1) / 10f } },
            info { "Volume ${((settings()?.masterVolume ?: 1f) * 100).roundToInt()}%" },
            action("Volume +") { settings()?.let { it.masterVolume = ((it.masterVolume * 10).roundToInt() + 1) / 10f } },
            cycler({ "Filter: " + (settings()?.audioFilter?.label ?: "-") }) { s ->
                s.audioFilter = AudioFilterMode.entries[(s.audioFilter.ordinal + 1) % AudioFilterMode.entries.size]
            },
            cycler({ "Buffer: " + (settings()?.audioLatency?.label ?: "-") }) { s ->
                s.audioLatency = AudioLatency.entries[(s.audioLatency.ordinal + 1) % AudioLatency.entries.size]
            },
        ))
        home.addView(label("Channel volumes are in All settings. A bigger buffer stops crackle and stutter.", 26f, MUTED))

        // Emulation
        home.addView(header("Emulation"))
        home.addView(grid(
            toggle({ "No sprite limit" }, { settings()?.spriteLimitOff == true }) {
                settings()?.let { it.spriteLimitOff = !it.spriteLimitOff }
            },
            cycler({ "Overclock: " + (settings()?.cpuOverclock?.let { if (it == 1) "Off" else "${it}x" } ?: "-") }) { s ->
                s.cpuOverclock = s.cpuOverclock % 4 + 1
            },
        ))

        // Input
        home.addView(header("Input"))
        home.addView(grid(
            action("Controllers & mapping…") { send(ThorCommand.Controllers) },
            toggle({ "Turbo A/B" }, { settings()?.autofireAB == true }) {
                settings()?.let { s ->
                    val on = !s.autofireAB
                    (ThorDualScreen.main.get() as? MainActivity)?.setAutofire(on, s) ?: run { s.autofireAB = on }
                }
            },
            toggle({ "Cancel opposite directions" }, { settings()?.cancelOpposites == true }) {
                settings()?.let { it.cancelOpposites = !it.cancelOpposites }
            },
            toggle({ "Hide touch pad with controller" }, { settings()?.autoHideControllerOnPad == true }) {
                settings()?.let { it.autoHideControllerOnPad = !it.autoHideControllerOnPad }
            },
        ))

        // RetroAchievements
        home.addView(header("RetroAchievements"))
        home.addView(grid(
            toggle({ "RetroAchievements" }, { settings()?.raEnabled == true }) {
                settings()?.let { send(ThorCommand.RaEnabled(!it.raEnabled)) }
            },
            toggle({ "Hardcore mode" }, { settings()?.raHardcore == true }) {
                settings()?.let { send(ThorCommand.RaHardcore(!it.raHardcore)) }
            },
        ))
        raStatus = label("", 30f, TEXT)
        home.addView(raStatus)

        raLoginBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        userField = field("RetroAchievements username", password = false)
        passField = field("Password", password = true)
        raLoginBox.addView(userField, LinearLayout.LayoutParams(-1, 110).apply { topMargin = 8 })
        raLoginBox.addView(passField, LinearLayout.LayoutParams(-1, 110).apply { topMargin = 12 })
        raLoginBox.addView(
            button("Sign in", primary = true) {
                val user = userField.text.toString().trim()
                val pass = passField.text.toString()
                if (user.isEmpty() || pass.isEmpty()) {
                    toast("Enter your RetroAchievements username and password")
                } else {
                    send(ThorCommand.RaLogin(user, pass))
                    passField.setText("")
                    hideKeyboard()
                    toast(if (hasGame) "Signing in…" else "Saved. Signing in when a game starts.")
                }
            },
            LinearLayout.LayoutParams(-1, 112).apply { topMargin = 14 },
        )
        raLoginBox.addView(label("Your password is only used to sign in; RustyNES keeps the sign-in token, never the password.", 24f, MUTED))
        home.addView(raLoginBox)
        raSignedInBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        raSignedInBox.addView(
            button("Sign out") { send(ThorCommand.RaLogout) },
            LinearLayout.LayoutParams(-1, 104).apply { topMargin = 10 },
        )
        home.addView(raSignedInBox)
        raList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        home.addView(raList)

        // Expansions
        home.addView(header("Second-screen expansions (.exp)"))
        showPanelBtn = button("Show game panel", primary = true) {
            userChoseHome = false
            if (active != null) showExpansion()
        }
        home.addView(showPanelBtn, LinearLayout.LayoutParams(-1, 112))
        home.addView(grid(
            action("Import expansion (.exp)…") { pickExpansion() },
            action("Save RAM dump", enabledIf = { hasGame }) { toast(ThorDualScreen.dumpMemory(this)) },
        ))
        expList = LinearLayout(this)
        expList.orientation = LinearLayout.VERTICAL
        home.addView(expList)

        dynamic += {
            val s = settings()
            val enabled = s?.raEnabled == true
            val signed = raLoggedIn || (s?.raToken?.isNotEmpty() == true)
            raLoginBox.visibility = if (enabled && !signed) View.VISIBLE else View.GONE
            raSignedInBox.visibility = if (enabled && signed) View.VISIBLE else View.GONE
            raList.visibility = if (enabled) View.VISIBLE else View.GONE
            if (s != null && userField.text.isEmpty() && s.raUsername.isNotEmpty() && !userField.hasFocus()) {
                userField.setText(s.raUsername)
            }
            showPanelBtn.visibility = if (active != null && !expVisible) View.VISIBLE else View.GONE
            active?.let { showPanelBtn.text = "Show ${it.name}" }
        }
        refreshExpansions()
    }

    private fun refreshDynamic() {
        if (!::home.isInitialized) return
        for (f in dynamic) runCatching { f() }
    }

    private fun refreshExpansions() {
        if (!::expList.isInitialized) return
        expList.removeAllViews()
        val all = ExpansionStore.all(this)
        if (all.isEmpty()) {
            expList.addView(label("No expansions installed. An expansion draws a live panel for one game on this screen.", 26f, MUTED))
            return
        }
        for (e in all) {
            val line = LinearLayout(this)
            line.orientation = LinearLayout.HORIZONTAL
            line.gravity = Gravity.CENTER_VERTICAL
            val text = label(
                "${e.name}  v${e.version}" + (if (e === active) "  · this game" else "") +
                    (if (e.game.isNotEmpty()) "\n${e.game}" else ""),
                28f, TEXT,
            )
            line.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
            line.addView(button("Remove") {
                ExpansionStore.delete(this, e)
                if (active === e) {
                    active = null
                    expView.expansion = null
                    showHome()
                }
                identityFor = null
                refreshExpansions()
            }, LinearLayout.LayoutParams(220, 96))
            line.setPadding(0, 10, 0, 10)
            expList.addView(line)
        }
        refreshDynamic()
    }

    private fun pickExpansion() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
        ThorDualScreen.pickerOpen = true
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_IMPORT)
        } catch (e: Exception) {
            ThorDualScreen.pickerOpen = false
            toast("No file picker available")
        }
    }

    // ------------------------------------------------------------ view helpers

    private fun label(s: String, px: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = s
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, px)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 6, 0, 6)
        }

    private fun header(s: String): TextView = label(s, 36f, ACCENT, bold = true).apply {
        setPadding(0, 36, 0, 8)
    }

    private fun bg(color: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = 22f
        if (stroke != null) setStroke(3, stroke)
    }

    private fun button(text: String, primary: Boolean = false, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        isAllCaps = false
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, 30f)
        background = bg(if (primary) ACCENT_DARK else BTN)
        stateListAnimator = null
        setOnClickListener { onClick() }
    }

    /** A plain action button; greyed out while [enabledIf] is false. */
    private fun action(text: String, enabledIf: () -> Boolean = { true }, onClick: () -> Unit): Button {
        val b = button(text) {
            onClick()
            refreshDynamic()
        }
        dynamic += {
            val on = enabledIf()
            b.isEnabled = on
            b.alpha = if (on) 1f else 0.4f
        }
        return b
    }

    /** An on/off button: highlighted while [isOn]. */
    private fun toggle(
        text: () -> String,
        isOn: () -> Boolean,
        enabledIf: () -> Boolean = { true },
        onClick: () -> Unit,
    ): Button {
        val b = button("") {
            onClick()
            refreshDynamic()
        }
        dynamic += {
            val on = isOn()
            b.text = text() + if (on) "  ●" else "  ○"
            b.background = if (on) bg(ACCENT_DARK, ACCENT) else bg(BTN)
            val en = enabledIf()
            b.isEnabled = en
            b.alpha = if (en) 1f else 0.4f
        }
        return b
    }

    /** A button that steps a setting to its next value. */
    private fun cycler(text: () -> String, next: (AppSettings) -> Unit): Button {
        val b = button("") {
            settings()?.let(next) ?: toast("Open RustyNES on the top screen first")
            refreshDynamic()
        }
        dynamic += { b.text = text() }
        return b
    }

    /** A read-only cell in a grid (e.g. the current volume). */
    private fun info(text: () -> String): TextView {
        val t = label("", 30f, TEXT).apply { gravity = Gravity.CENTER }
        dynamic += { t.text = text() }
        return t
    }

    private fun grid(vararg views: View, columns: Int = 2): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        views.toList().chunked(columns).forEach { chunk ->
            val row = LinearLayout(this@ThorSecondScreenActivity)
            row.orientation = LinearLayout.HORIZONTAL
            chunk.forEachIndexed { i, v ->
                val lp = LinearLayout.LayoutParams(0, 108, 1f)
                if (i > 0) lp.leftMargin = 16
                row.addView(v, lp)
            }
            // Keep a lone last cell half-width, like the others.
            for (pad in chunk.size until columns) {
                row.addView(View(this@ThorSecondScreenActivity), LinearLayout.LayoutParams(0, 108, 1f).apply { leftMargin = 16 })
            }
            addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 14 })
        }
    }

    private fun field(hint: String, password: Boolean): EditText = EditText(this).apply {
        this.hint = hint
        setHintTextColor(MUTED)
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, 32f)
        isSingleLine = true
        setPadding(28, 0, 28, 0)
        background = bg(BTN, 0xFF55565C.toInt())
        inputType = if (password) {
            android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        } else {
            android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        imm?.hideSoftInputFromWindow(root.windowToken, 0)
        userField.clearFocus()
        passField.clearFocus()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        const val REQ_IMPORT = 4401

        private val BG = 0xFF1E1F22.toInt()
        private val BTN = 0xFF34363B.toInt()
        private val TEXT = 0xFFE6E6E6.toInt()
        private val MUTED = 0xFF9EA1A8.toInt()
        private val ACCENT = 0xFF8E83F5.toInt()
        private val ACCENT_DARK = 0xFF4B4396.toInt()
        private val GOLD = 0xFFFFD54F.toInt()

        /** Import on a worker thread, then report on the UI thread. */
        fun importFrom(activity: Activity, uri: android.net.Uri, done: (String) -> Unit) {
            Thread {
                val msg = try {
                    val e = ExpansionStore.import(activity, uri)
                    ThorDualScreen.expansionsChanged++
                    "Installed ${e.name}" + (if (e.version.isNotEmpty()) " v${e.version}" else "")
                } catch (ex: Exception) {
                    "Import failed: ${ex.message}"
                }
                activity.runOnUiThread { done(msg) }
            }.start()
        }
    }
}
