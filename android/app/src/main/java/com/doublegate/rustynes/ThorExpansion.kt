package com.doublegate.rustynes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipFile

/**
 * Second-screen expansions (`.exp`) for the AYN Thor bottom screen.
 *
 * Same idea and file layout as the M64-DS expansions (a zip renamed to `.exp`
 * holding `manifest.json` + images/icons/fonts), adapted to the NES:
 *
 *  - values are read from the NES **CPU bus** (`$0000-$FFFF`): internal RAM is
 *    `$0000-$07FF`, cartridge PRG-RAM is `$6000-$7FFF`. Multi-byte values are
 *    **little-endian** (the 6502's order); `u16be`/`u32be`, `bcd` and `digits`
 *    cover games that store numbers differently.
 *  - `match` identifies the ROM by `md5` / `sha1` / `sha256` / `crc` (each
 *    accepted for the whole file OR the file without its 16-byte iNES header,
 *    so No-Intro / RetroAchievements hashes both work) or by `name` /
 *    `name_regex` against the ROM's file name.
 *
 * Memory is read with the bridge's side-effect-free `debugReadMemory`, so an
 * expansion can never change the game, desync netplay, or touch achievements.
 */
class Expansion(
    val id: String,
    val name: String,
    val game: String,
    val version: String,
    val author: String,
    val file: File,
    val manifest: JSONObject,
    val images: Map<String, Bitmap>,
    val typeface: Typeface?,
) {
    val match: JSONObject? = manifest.optJSONObject("match")
    val theme: JSONObject = manifest.optJSONObject("theme") ?: JSONObject()
    val screen: JSONObject? = manifest.optJSONObject("screen")
    val pixelScreen: JSONObject? = manifest.optJSONObject("pixel_screen")
    val values: JSONObject = manifest.optJSONObject("values") ?: JSONObject()

    /** Whether this expansion draws a panel (some only carry a picture position). */
    val hasPanel: Boolean get() = screen != null || pixelScreen != null

    /** The game's saved picture zoom and position ("display" in the manifest), if any. */
    fun display(): PictureAdjust? {
        val d = manifest.optJSONObject("display") ?: return null
        return PictureAdjust(
            zoom = (d.optDouble("zoom", 100.0) / 100.0).toFloat().coerceIn(1f, 2f),
            x = d.optDouble("x", 0.0).toFloat().coerceIn(-1f, 1f),
            y = d.optDouble("y", 0.0).toFloat().coerceIn(-1f, 1f),
        )
    }

    fun image(path: String?): Bitmap? = if (path.isNullOrEmpty()) null else images[path.trimStart('/')]

    /** Does this expansion belong to the given ROM? */
    fun matches(rom: RomIdentity): Boolean {
        val m = match ?: return false
        // Strong hashes first: when one is present only it decides (as in M64-DS).
        for (key in listOf("md5", "sha1", "sha256")) {
            if (m.has(key)) {
                val want = stringList(m, key).map { it.lowercase() }
                val have = rom.hashes[key] ?: return false
                return want.any { it in have }
            }
        }
        var any = false
        if (m.has("crc")) {
            any = true
            val want = stringList(m, "crc").map { it.uppercase().removePrefix("0X").padStart(8, '0') }
            if (want.none { it in rom.crcs }) return false
        }
        val fileName = rom.name ?: ""
        if (m.has("name")) {
            any = true
            if (stringList(m, "name").none { fileName.contains(it, ignoreCase = true) }) return false
        }
        if (m.has("name_regex")) {
            any = true
            val ok = runCatching { Regex(m.getString("name_regex")).containsMatchIn(fileName) }.getOrDefault(false)
            if (!ok) return false
        }
        return any
    }

    companion object {
        fun stringList(o: JSONObject, key: String): List<String> {
            val v = o.opt(key) ?: return emptyList()
            return if (v is JSONArray) (0 until v.length()).map { v.optString(it) } else listOf(v.toString())
        }
    }
}

/** Everything an expansion can match a loaded ROM against. */
class RomIdentity(val name: String?, bytes: ByteArray) {
    /** key ("md5"/"sha1"/"sha256") -> lowercase hex of the whole file and of the headerless body. */
    val hashes: Map<String, Set<String>>
    /** Uppercase CRC32s of the whole file and of the headerless body. */
    val crcs: Set<String>

    init {
        val body = headerless(bytes)
        val variants = if (body === bytes) listOf(bytes) else listOf(bytes, body)
        hashes = mapOf(
            "md5" to variants.map { hex(MessageDigest.getInstance("MD5").digest(it)) }.toCollection(LinkedHashSet()),
            "sha1" to variants.map { hex(MessageDigest.getInstance("SHA-1").digest(it)) }.toCollection(LinkedHashSet()),
            "sha256" to variants.map { hex(MessageDigest.getInstance("SHA-256").digest(it)) }.toCollection(LinkedHashSet()),
        )
        crcs = variants.map { b -> CRC32().also { it.update(b) }.value.let { "%08X".format(it) } }.toCollection(LinkedHashSet())
    }

    val headerlessCrc: String get() = crcs.last()

    /** SHA-256 of the ROM without its header (what an expansion made on the device matches). */
    val headerlessSha256: String get() = hashes.getValue("sha256").last()

    companion object {
        /** The ROM without its iNES/NES 2.0 header (and trainer), or the same array if it has none. */
        fun headerless(bytes: ByteArray): ByteArray {
            if (bytes.size < 16 || bytes[0] != 'N'.code.toByte() || bytes[1] != 'E'.code.toByte() ||
                bytes[2] != 'S'.code.toByte() || bytes[3] != 0x1A.toByte()
            ) return bytes
            val trainer = if ((bytes[6].toInt() and 0x04) != 0) 512 else 0
            val start = 16 + trainer
            return if (start >= bytes.size) bytes else bytes.copyOfRange(start, bytes.size)
        }

        private fun hex(d: ByteArray) = d.joinToString("") { "%02x".format(it) }
    }
}

/** Installed expansions: imported copies live in `files/expansions/`. */
object ExpansionStore {
    private const val TAG = "RustyNES-exp"
    private var cache: List<Expansion>? = null

    private fun dir(context: Context): File = File(context.filesDir, "expansions").also { it.mkdirs() }

    @Synchronized
    fun all(context: Context): List<Expansion> {
        cache?.let { return it }
        val list = dir(context).listFiles { f -> f.name.endsWith(".exp") }
            ?.sortedBy { it.name }
            ?.mapNotNull { f ->
                runCatching { load(context, f) }
                    .onFailure { Log.w(TAG, "Couldn't load ${f.name}", it) }
                    .getOrNull()
            } ?: emptyList()
        cache = list
        return list
    }

    @Synchronized
    fun invalidate() {
        cache = null
    }

    fun forRom(context: Context, rom: RomIdentity?): Expansion? =
        if (rom == null) null else all(context).firstOrNull { it.matches(rom) }

    /**
     * Copy the `.exp` at [uri] into the app. An expansion with the same `id`
     * replaces the old one (that is how an expansion is updated). Returns the
     * imported expansion or throws with a readable message.
     */
    fun import(context: Context, uri: Uri): Expansion {
        val tmp = File(context.cacheDir, "import-${System.nanoTime()}.exp")
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Couldn't open the file" }
                tmp.outputStream().use { input.copyTo(it) }
            }
            val probe = try {
                load(context, tmp)
            } catch (e: Exception) {
                throw IllegalArgumentException("Not a valid .exp: ${e.message}", e)
            }
            val safe = probe.id.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "expansion" }
            val dest = File(dir(context), "$safe.exp")
            tmp.copyTo(dest, overwrite = true)
            invalidate()
            return all(context).first { it.file == dest }
        } finally {
            tmp.delete()
        }
    }

    /**
     * Save a game's picture zoom and position into its expansion: the existing one
     * (manifest rewritten, every other file kept, version bumped, as every change to
     * an expansion must), or a new picture-only expansion that matches this ROM by
     * its headerless SHA-256. Returns the saved expansion's id.
     */
    fun saveDisplay(context: Context, exp: Expansion?, rom: RomIdentity, title: String, adj: PictureAdjust): String {
        val display = JSONObject()
            .put("zoom", Math.round(adj.zoom * 100f))
            .put("x", Math.round(adj.x * 100f) / 100.0)
            .put("y", Math.round(adj.y * 100f) / 100.0)
        if (exp != null) {
            val m = JSONObject(exp.manifest.toString())
            m.put("display", display)
            m.put("version", bumpVersion(m.optString("version")))
            rewrite(exp.file, m)
            invalidate()
            return exp.id
        }
        val id = "display-" + rom.headerlessSha256.take(12)
        val m = JSONObject()
            .put("format", 1)
            .put("id", id)
            .put("name", "$title - picture")
            .put("game", title)
            .put("version", "1.0")
            .put("author", "RustyNES (made on device)")
            .put("match", JSONObject().put("sha256", rom.headerlessSha256))
            .put("display", display)
        val f = File(dir(context), "$id.exp")
        java.util.zip.ZipOutputStream(f.outputStream()).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry("manifest.json"))
            z.write(m.toString(2).toByteArray())
            z.closeEntry()
        }
        invalidate()
        return id
    }

    /** Copy an installed expansion (with everything saved in it) to [uri]. */
    fun export(context: Context, exp: Expansion, uri: Uri) {
        context.contentResolver.openOutputStream(uri).use { out ->
            requireNotNull(out) { "Couldn't write the file" }
            exp.file.inputStream().use { it.copyTo(out) }
        }
    }

    /** "1.6" -> "1.7", "2" -> "2.1", "" -> "1.1". */
    fun bumpVersion(v: String): String {
        val parts = v.split('.').mapNotNull { it.toIntOrNull() }
        return when {
            parts.isEmpty() -> "1.1"
            parts.size == 1 -> "${parts[0]}.1"
            else -> (parts.dropLast(1) + (parts.last() + 1)).joinToString(".")
        }
    }

    private fun rewrite(file: File, manifest: JSONObject) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        ZipFile(file).use { src ->
            java.util.zip.ZipOutputStream(tmp.outputStream()).use { z ->
                z.putNextEntry(java.util.zip.ZipEntry("manifest.json"))
                z.write(manifest.toString(2).toByteArray())
                z.closeEntry()
                for (e in src.entries()) {
                    if (e.name == "manifest.json" || e.isDirectory) continue
                    z.putNextEntry(java.util.zip.ZipEntry(e.name))
                    src.getInputStream(e).use { it.copyTo(z) }
                    z.closeEntry()
                }
            }
        }
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    fun delete(context: Context, exp: Expansion) {
        exp.file.delete()
        invalidate()
    }

    private fun load(context: Context, f: File): Expansion {
        ZipFile(f).use { zip ->
            val entry = zip.getEntry("manifest.json") ?: error("manifest.json is missing")
            val manifest = JSONObject(zip.getInputStream(entry).bufferedReader().readText())
            val images = HashMap<String, Bitmap>()
            var typeface: Typeface? = null
            val fontPath = manifest.optJSONObject("theme")?.optString("font").orEmpty()
            for (e in zip.entries()) {
                if (e.isDirectory) continue
                val n = e.name.lowercase()
                if (n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".webp")) {
                    val bmp = zip.getInputStream(e).use { BitmapFactory.decodeStream(it) }
                    if (bmp != null) images[e.name] = bmp
                } else if (fontPath.isNotEmpty() && e.name == fontPath) {
                    val out = File(context.cacheDir, "expfont-${f.nameWithoutExtension}-${e.name.substringAfterLast('/')}")
                    zip.getInputStream(e).use { i -> out.outputStream().use { i.copyTo(it) } }
                    typeface = runCatching { Typeface.createFromFile(out) }.getOrNull()
                }
            }
            val id = manifest.optString("id").ifEmpty { f.nameWithoutExtension }
            return Expansion(
                id = id,
                name = manifest.optString("name").ifEmpty { id },
                game = manifest.optString("game"),
                version = manifest.optString("version"),
                author = manifest.optString("author"),
                file = f,
                manifest = manifest,
                images = images,
                typeface = typeface,
            )
        }
    }
}

/** One byte of the NES CPU bus (`0x0000..0xFFFF`). */
fun interface MemoryReader {
    fun read(addr: Int): Int
}

/**
 * Reads the CPU bus 256 bytes at a time, only the pages an expansion actually
 * uses, each page once per snapshot. Each page is one short bridge call, so the
 * emulator lock is never held for long (reading all 64 KB in one call held it
 * for thousands of side-effect-free peeks and could delay a frame).
 */
class PagedBus(private val fetch: (Int) -> ByteArray?) : MemoryReader {
    private val pages = arrayOfNulls<ByteArray>(256)
    private val tried = BooleanArray(256)

    override fun read(addr: Int): Int {
        val p = (addr ushr 8) and 0xFF
        if (!tried[p]) {
            tried[p] = true
            pages[p] = runCatching { fetch(p shl 8) }.getOrNull()
        }
        val page = pages[p] ?: return 0
        val i = addr and 0xFF
        return if (i < page.size) page[i].toInt() and 0xFF else 0
    }
}

/**
 * Evaluates an expansion's `values` against one snapshot of the NES CPU bus.
 * A value may refer to other values (sum, lookup, select, `{"value": …}`);
 * results are memoized per snapshot and cycles evaluate to 0.
 */
class ValueEvaluator(
    private val specs: JSONObject,
    private val mem: MemoryReader?,
    private val lastLookups: MutableMap<String, Any>,
    private val sessionSeconds: Long,
) {
    private val done = HashMap<String, Any>()
    private val busy = HashSet<String>()

    val gameRunning: Boolean get() = mem != null

    fun get(name: String): Any {
        done[name]?.let { return it }
        if (name == "time") return formatTime(sessionSeconds)
        val spec = specs.optJSONObject(name) ?: return if (specs.has(name)) specs.opt(name) ?: 0L else 0L
        if (!busy.add(name)) return 0L
        val v = runCatching { eval(name, spec) }.getOrElse { 0L }
        busy.remove(name)
        done[name] = v
        return v
    }

    fun number(name: String): Long = toNumber(get(name))

    /** Evaluate every declared value now (on the calling thread), so drawing later never reads memory. */
    fun prefetch() {
        val it = specs.keys()
        while (it.hasNext()) get(it.next())
    }

    /** Replace `{name}` / `{name:03}` (zero-padded) in [template]. */
    fun format(template: String?): String {
        if (template.isNullOrEmpty()) return ""
        return TEMPLATE.replace(template) { m ->
            val key = m.groupValues[1]
            val fmt = m.groupValues[2]
            val v = get(key)
            if (fmt.isNotEmpty() && v !is String) {
                val width = fmt.trimStart('0').toIntOrNull() ?: 0
                val n = toNumber(v)
                if (fmt.startsWith("0")) n.toString().padStart(width, '0') else n.toString().padStart(width, ' ')
            } else {
                display(v)
            }
        }
    }

    private fun u8(addr: Long): Long {
        val m = mem ?: return 0
        return (m.read((addr and 0xFFFFL).toInt()) and 0xFF).toLong()
    }

    private fun readLe(addr: Long, n: Int): Long {
        var v = 0L
        for (i in 0 until n) v = v or (u8(addr + i) shl (8 * i))
        return v
    }

    private fun readBe(addr: Long, n: Int): Long {
        var v = 0L
        for (i in 0 until n) v = (v shl 8) or u8(addr + i)
        return v
    }

    /** Resolve `addr`, `ptr` (+ `offset`) or `chain` to a bus address. */
    private fun address(spec: JSONObject): Long {
        if (spec.has("chain")) {
            val chain = spec.getJSONArray("chain")
            var a = parseAddr(chain.opt(0))
            for (i in 1 until chain.length()) {
                when (val item = chain.opt(i)) {
                    "*" -> a = readLe(a, 2)
                    is JSONObject -> a += toNumber(get(item.optString("value"))) * item.optLong("times", 1)
                    else -> a += parseAddr(item)
                }
            }
            return a and 0xFFFFL
        }
        if (spec.has("ptr")) {
            return (readLe(parseAddr(spec.opt("ptr")), 2) + parseAddr(spec.opt("offset") ?: 0)) and 0xFFFFL
        }
        return parseAddr(spec.opt("addr")) + parseAddr(spec.opt("offset") ?: 0)
    }

    private fun arith(spec: JSONObject, raw: Long): Long {
        var v = raw
        if (spec.has("shift")) v = v shr spec.optInt("shift")
        if (spec.has("mask")) v = v and parseAddr(spec.opt("mask"))
        if (spec.has("times")) v *= spec.optLong("times", 1)
        if (spec.has("div")) spec.optLong("div", 1).let { if (it != 0L) v /= it }
        if (spec.has("add")) v += spec.optLong("add", 0)
        return v
    }

    private fun eval(name: String, spec: JSONObject): Any {
        return when (val type = spec.optString("type", "u8").lowercase()) {
            "u8" -> arith(spec, u8(address(spec)))
            "s8" -> arith(spec, u8(address(spec)).toByte().toLong())
            "u16" -> arith(spec, readLe(address(spec), 2))
            "s16" -> arith(spec, readLe(address(spec), 2).toShort().toLong())
            "u24" -> arith(spec, readLe(address(spec), 3))
            "u32" -> arith(spec, readLe(address(spec), 4))
            "u16be" -> arith(spec, readBe(address(spec), 2))
            "u32be" -> arith(spec, readBe(address(spec), 4))
            "bit" -> arith(spec, (u8(address(spec)) shr spec.optInt("bit")) and 1L)
            "bcd" -> {
                val a = address(spec)
                val n = spec.optInt("bytes", 1)
                val le = spec.optString("order", "be") == "le"
                var v = 0L
                for (i in 0 until n) {
                    val b = u8(if (le) a + (n - 1 - i) else a + i)
                    v = v * 100 + ((b shr 4) and 0xFL).coerceAtMost(9L) * 10 + (b and 0xFL).coerceAtMost(9L)
                }
                arith(spec, v)
            }
            "digits" -> {
                val a = address(spec)
                val n = spec.optInt("count", 1)
                val le = spec.optString("order", "be") == "le"
                var v = 0L
                for (i in 0 until n) {
                    val b = u8(if (le) a + (n - 1 - i) else a + i)
                    v = v * 10 + (if (b <= 9L) b else 0L)
                }
                arith(spec, v)
            }
            "flags" -> {
                val base = address(spec)
                val bits = spec.optJSONArray("bits") ?: JSONArray()
                var on = 0
                for (i in 0 until bits.length()) {
                    val bit = bits.optLong(i)
                    if (((u8(base + (bit shr 3)) shr (bit and 7L).toInt()) and 1L) == 1L) on++
                }
                when (spec.optString("mode", "count")) {
                    "any" -> if (on > 0) 1L else 0L
                    "all" -> if (on == bits.length() && on > 0) 1L else 0L
                    else -> on.toLong()
                }
            }
            "sum" -> {
                val terms = spec.optJSONArray("terms") ?: JSONArray()
                var s = 0L
                for (i in 0 until terms.length()) {
                    when (val t = terms.opt(i)) {
                        is JSONObject -> s += number(t.optString("value")) * t.optLong("times", 1)
                        is Number -> s += t.toLong()
                        else -> s += number(t.toString())
                    }
                }
                arith(spec, s)
            }
            "lookup" -> {
                val key = get(spec.optString("value"))
                val table = spec.optJSONObject("table") ?: JSONObject()
                val k = toNumber(key)
                val hit = table.opt(k.toString()) ?: table.opt("0x%X".format(k)) ?: table.opt("0x%02X".format(k))
                    ?: table.opt(display(key))
                when {
                    hit != null -> {
                        val v = normalize(hit)
                        if (spec.optBoolean("keep_last")) lastLookups[name] = v
                        v
                    }
                    spec.optBoolean("keep_last") && lastLookups.containsKey(name) -> lastLookups.getValue(name)
                    else -> normalize(spec.opt("default") ?: "")
                }
            }
            "select" -> {
                val idx = number(spec.optString("index")).toInt()
                val opts = spec.optJSONArray("options") ?: JSONArray()
                if (idx in 0 until opts.length()) get(opts.optString(idx)) else normalize(spec.opt("default") ?: 0)
            }
            "const" -> normalize(spec.opt("value") ?: 0)
            else -> {
                Log.w("RustyNES-exp", "Unknown value type '$type' for $name")
                0L
            }
        }
    }

    companion object {
        private val TEMPLATE = Regex("\\{([A-Za-z0-9_]+)(?::([0-9]+))?\\}")

        fun parseAddr(v: Any?): Long = when (v) {
            null -> 0
            is Number -> v.toLong()
            else -> {
                val s = v.toString().trim()
                when {
                    s.startsWith("0x", true) -> s.substring(2).toLong(16)
                    s.startsWith("$") -> s.substring(1).toLong(16)
                    s.isEmpty() -> 0
                    else -> s.toLong()
                }
            }
        }

        fun toNumber(v: Any?): Long = when (v) {
            is Number -> v.toLong()
            is Boolean -> if (v) 1 else 0
            is String -> v.toLongOrNull() ?: 0
            else -> 0
        }

        fun normalize(v: Any): Any = when (v) {
            is Int -> v.toLong()
            is Double -> v.toLong()
            is Float -> v.toLong()
            is Number -> v.toLong()
            else -> v
        }

        fun display(v: Any?): String = when (v) {
            null -> ""
            is Number -> v.toLong().toString()
            else -> v.toString()
        }

        fun formatTime(seconds: Long): String {
            val h = seconds / 3600
            val m = (seconds % 3600) / 60
            val s = seconds % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
        }
    }
}

/**
 * A game's picture zoom (1.0-2.0) and position. [x] / [y] run from -1 to 1: the
 * picture moved fully left / up to fully right / down. When the zoomed picture is
 * larger than the screen this picks which part shows; when it is smaller it places
 * the picture in the free space.
 */
data class PictureAdjust(val zoom: Float, val x: Float, val y: Float)
