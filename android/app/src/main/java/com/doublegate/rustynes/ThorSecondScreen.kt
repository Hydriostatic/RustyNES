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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.lang.ref.WeakReference
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
    fun forwardKey(event: KeyEvent): Boolean = main.get()?.dispatchKeyEvent(event) ?: false

    fun forwardMotion(event: MotionEvent): Boolean = main.get()?.dispatchGenericMotionEvent(event) ?: false

    /** Export the CPU bus (`$0000-$FFFF`) to Download/RustyNES/ for finding addresses. */
    fun dumpMemory(ctx: Context): String {
        val ctrl = emulator?.controller ?: return "No game running"
        val bytes = runCatching { ctrl.debugReadMemory(0u.toUShort(), 0x10000u) }.getOrNull()
            ?: return "Couldn't read memory"
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

/** The page on the Thor's lower screen. */
class ThorSecondScreenActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var homeScroll: ScrollView
    private lateinit var home: LinearLayout
    private lateinit var expView: ExpansionView

    private lateinit var title: TextView
    private lateinit var gameLine: TextView
    private lateinit var pauseBtn: Button
    private lateinit var turboBtn: Button
    private lateinit var muteBtn: Button
    private lateinit var showPanelBtn: Button
    private lateinit var expList: LinearLayout
    private lateinit var raStatus: TextView
    private lateinit var raList: LinearLayout

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
    private var lastRaRefresh = 0L
    private var raSignature = ""

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThorDualScreen.bottom = WeakReference(this)
        // Touch works, but this window never takes the key focus from the game.
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.setBackgroundColor(ExpansionView.GRAY)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE

        root = FrameLayout(this)
        root.setBackgroundColor(ExpansionView.GRAY)
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
        if (BuildConfig.DEBUG) ui.postDelayed({ debugSnapshot() }, 15000)
    }

    private fun debugSnapshot() {
        if (isFinishing || root.width <= 0 || root.height <= 0) return
        runCatching {
            val bmp = android.graphics.Bitmap.createBitmap(root.width, root.height, android.graphics.Bitmap.Config.ARGB_8888)
            root.draw(android.graphics.Canvas(bmp))
            val f = java.io.File(getExternalFilesDir(null), "thor-bottom.png")
            f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            Log.i("RustyNES-thor", "snapshot ${root.width}x${root.height} expansion=${active?.id} page=${if (expVisible) "expansion" else "home"}")
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

    // Back on the bottom screen only goes back to the RustyNES panel; it never closes it.
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (expView.visibility == View.VISIBLE) {
            userChoseHome = true
            showHome()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val fromPad = (event.source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (event.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
            (event.source and InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD
        if (fromPad && ThorDualScreen.forwardKey(event)) return true
        return super.dispatchKeyEvent(event)
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
            val mem = runCatching { ctrl.debugReadMemory(0u.toUShort(), 0x10000u) }.getOrNull()
            val secs = (SystemClock.elapsedRealtime() - sessionStart) / 1000
            val ev = ValueEvaluator(exp.values, mem, lastLookups, secs)
            ui.post { if (active === exp) expView.values = ev }
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastRaRefresh > 1000) {
            lastRaRefresh = now
            val status = buildStatus(emu, ctrl)
            ui.post { applyStatus(status) }
        }
    }

    private class Status(
        val game: String,
        val paused: Boolean,
        val turbo: Boolean,
        val muted: Boolean,
        val ra: String,
        val achievements: List<Triple<String, String, Boolean>>,
        val signature: String,
    )

    private fun buildStatus(emu: EmulatorHandle?, ctrl: NesController?): Status {
        val game = when {
            ctrl == null -> "No game loaded — open a ROM on the top screen"
            else -> ThorDualScreen.romName ?: "Game running"
        }
        var ra = "RetroAchievements: off (enable it in Settings on the top screen)"
        val list = ArrayList<Triple<String, String, Boolean>>()
        if (ctrl != null) runCatching {
            if (ctrl.raIsEnabled()) {
                val user = ctrl.raUser()
                val mode = if (ctrl.raHardcore()) "Hardcore" else "Softcore"
                val s = ctrl.raGameSummary()
                ra = buildString {
                    append("RetroAchievements: ")
                    append(
                        when (ctrl.raLoginStatus()) {
                            RaLoginStatus.LOGGED_IN -> "signed in as ${user?.displayName ?: "?"} (${user?.score ?: 0u} pts)"
                            RaLoginStatus.LOGGING_IN -> "signing in…"
                            RaLoginStatus.ERROR -> "login failed — check Settings"
                            else -> "signed out"
                        },
                    )
                    append(" · ").append(mode)
                    if (s.size >= 6 && s[0] > 0u) {
                        append("\nThis game: ${s[2]}/${s[0]} unlocked · ${s[5]}/${s[4]} points")
                    }
                    val rp = ctrl.raRichPresence()
                    if (rp.isNotEmpty()) append("\n").append(rp)
                }
                for (a in ctrl.raAchievementList()) {
                    val pct = if (!a.unlocked && a.measuredPercent > 0f) " (${a.measuredPercent.toInt()}%)" else ""
                    list += Triple("${a.title} · ${a.points}$pct", a.description, a.unlocked)
                }
            }
        }
        val sig = ra + list.joinToString("|") { it.first + it.third }
        return Status(game, emu?.paused == true, emu?.turbo == true, emu?.muted == true, ra, list, sig)
    }

    private fun applyStatus(s: Status) {
        gameLine.text = s.game
        pauseBtn.text = if (s.paused) "Resume" else "Pause"
        turboBtn.text = if (s.turbo) "Fast-forward: on" else "Fast-forward: off"
        muteBtn.text = if (s.muted) "Sound: off" else "Sound: on"
        showPanelBtn.visibility = if (active != null) View.VISIBLE else View.GONE
        if (s.signature == raSignature) return
        raSignature = s.signature
        raStatus.text = s.ra
        raList.removeAllViews()
        for ((name, desc, unlocked) in s.achievements) {
            val row = TextView(this)
            row.text = (if (unlocked) "★ " else "☆ ") + name + if (desc.isNotEmpty()) "\n   $desc" else ""
            row.setTextColor(if (unlocked) Color.rgb(255, 213, 79) else Color.rgb(170, 170, 170))
            row.setTextSize(TypedValue.COMPLEX_UNIT_PX, 30f)
            row.setPadding(0, 8, 0, 8)
            raList.addView(row)
        }
    }

    // ------------------------------------------------------------ pages

    private fun onExpansionResolved(exp: Expansion?) {
        active = exp
        expView.expansion = exp
        if (exp != null && !userChoseHome) showExpansion() else if (exp == null) showHome()
        showPanelBtn.visibility = if (exp != null) View.VISIBLE else View.GONE
        if (exp != null) showPanelBtn.text = "Show ${exp.name} panel"
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
        when {
            action == "menu" || action == "save_quit" -> {
                userChoseHome = true
                showHome()
            }
            action == "screen:main" -> expView.page = "main"
            action == "screen:pixel" -> expView.page = "pixel"
        }
    }

    // ------------------------------------------------------------ home panel

    @SuppressLint("SetTextI18n")
    private fun buildHome() {
        homeScroll = ScrollView(this)
        homeScroll.isFillViewport = true
        home = LinearLayout(this)
        home.orientation = LinearLayout.VERTICAL
        home.setPadding(40, 28, 40, 40)
        homeScroll.addView(home, ViewGroup.LayoutParams(-1, -2))

        title = text("RustyNES · AYN Thor", 52f, Color.WHITE, bold = true)
        home.addView(title)
        gameLine = text("", 32f, Color.rgb(200, 200, 200))
        home.addView(gameLine)

        home.addView(header("Game"))
        home.addView(row(
            button("Pause") { ThorDualScreen.emulator?.let { it.paused = !it.paused }; lastRaRefresh = 0 }.also { pauseBtn = it },
            button("Fast-forward: off") { ThorDualScreen.emulator?.let { it.turbo = !it.turbo }; lastRaRefresh = 0 }.also { turboBtn = it },
            button("Sound: on") { ThorDualScreen.emulator?.let { it.muted = !it.muted }; lastRaRefresh = 0 }.also { muteBtn = it },
        ))

        home.addView(header("Second-screen expansions (.exp)"))
        showPanelBtn = button("Show panel") {
            userChoseHome = false
            if (active != null) showExpansion()
        }
        showPanelBtn.visibility = View.GONE
        home.addView(showPanelBtn, LinearLayout.LayoutParams(-1, 120))
        home.addView(row(
            button("Import expansion (.exp)…") { pickExpansion() },
            button("Save RAM dump") { toast(ThorDualScreen.dumpMemory(this)) },
        ))
        expList = LinearLayout(this)
        expList.orientation = LinearLayout.VERTICAL
        home.addView(expList)

        home.addView(header("RetroAchievements"))
        raStatus = text("", 30f, Color.rgb(220, 220, 220))
        home.addView(raStatus)
        raList = LinearLayout(this)
        raList.orientation = LinearLayout.VERTICAL
        home.addView(raList)
        refreshExpansions()
    }

    @SuppressLint("SetTextI18n")
    private fun refreshExpansions() {
        if (!::expList.isInitialized) return
        expList.removeAllViews()
        val all = ExpansionStore.all(this)
        if (all.isEmpty()) {
            expList.addView(text("No expansions installed. An expansion draws a live panel for one game on this screen.", 28f, Color.rgb(170, 170, 170)))
            return
        }
        for (e in all) {
            val line = LinearLayout(this)
            line.orientation = LinearLayout.HORIZONTAL
            line.gravity = Gravity.CENTER_VERTICAL
            val label = text(
                "${e.name}  v${e.version}" + (if (e === active) "  ← this game" else "") +
                    (if (e.game.isNotEmpty()) "\n${e.game}" else ""),
                30f, Color.WHITE,
            )
            line.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
            line.addView(button("Remove") {
                ExpansionStore.delete(this, e)
                if (active === e) {
                    active = null
                    expView.expansion = null
                    showHome()
                }
                identityFor = null
                refreshExpansions()
            }, LinearLayout.LayoutParams(220, 100))
            line.setPadding(0, 10, 0, 10)
            expList.addView(line)
        }
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

    // ------------------------------------------------------------ small view helpers

    private fun text(s: String, px: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = s
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, px)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 6, 0, 6)
        }

    private fun header(s: String): TextView = text(s, 36f, Color.rgb(124, 111, 240), bold = true).apply {
        setPadding(0, 34, 0, 10)
    }

    private fun button(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, 32f)
        background = GradientDrawable().apply {
            setColor(Color.rgb(64, 64, 64))
            cornerRadius = 22f
        }
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for ((i, v) in views.withIndex()) {
            val lp = LinearLayout.LayoutParams(0, 120, 1f)
            if (i > 0) lp.leftMargin = 18
            addView(v, lp)
        }
        setPadding(0, 8, 0, 8)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        const val REQ_IMPORT = 4401

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
