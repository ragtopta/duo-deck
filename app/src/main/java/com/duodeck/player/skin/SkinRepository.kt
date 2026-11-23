package com.duodeck.player.skin

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** Saves, loads, imports and shares skins. Custom skins live as JSON files in app storage. */
object SkinRepository {

    private lateinit var appContext: Context
    private val skinDir by lazy { File(appContext.filesDir, "skins").apply { mkdirs() } }
    private val imageDir by lazy { File(appContext.filesDir, "skin_images").apply { mkdirs() } }

    private val _skins = MutableStateFlow<List<Skin>>(BuiltInSkins.all)
    val skins: StateFlow<List<Skin>> = _skins

    private val _current = MutableStateFlow(BuiltInSkins.NIGHT_SHIFT)
    val current: StateFlow<Skin> = _current

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        reload()
        val id = prefs().getString("current_skin", null)
        _current.value = _skins.value.firstOrNull { it.id == id } ?: BuiltInSkins.NIGHT_SHIFT
    }

    private fun prefs() = appContext.getSharedPreferences("skins", Context.MODE_PRIVATE)

    private fun reload() {
        val custom = skinDir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { f ->
                try { Skin.fromJson(JSONObject(f.readText()), id = f.nameWithoutExtension) } catch (_: Exception) { null }
            }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()
        _skins.value = BuiltInSkins.all + custom
    }

    fun apply(skin: Skin) {
        _current.value = skin
        prefs().edit().putString("current_skin", skin.id).apply()
    }

    /** Saves a skin. Built-in skins are never overwritten: a copy is made instead. Returns the saved skin. */
    fun save(skin: Skin): Skin {
        val toSave = if (skin.builtIn) skin.copy(id = UUID.randomUUID().toString(), builtIn = false) else skin
        File(skinDir, "${toSave.id}.json").writeText(toSave.toJson().toString(2))
        reload()
        if (_current.value.id == toSave.id) _current.value = toSave
        return toSave
    }

    fun delete(skin: Skin) {
        if (skin.builtIn) return
        File(skinDir, "${skin.id}.json").delete()
        reload()
        // only remove the picture if no other skin still uses it
        skin.bgImage?.let { path ->
            val stillUsed = _skins.value.any { it.bgImage == path } || _current.value.let { it.id != skin.id && it.bgImage == path }
            if (!stillUsed && path.startsWith(imageDir.absolutePath)) File(path).delete()
        }
        if (_current.value.id == skin.id) apply(BuiltInSkins.NIGHT_SHIFT)
    }

    /** Copies (and shrinks) a picked image into app storage so the skin keeps working. */
    fun importImage(uri: Uri): String? = try {
        val bmp = decodeScaled(uri, 1400) ?: return null
        val out = File(imageDir, "${UUID.randomUUID()}.jpg")
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        out.absolutePath
    } catch (_: Exception) { null }

    private fun decodeScaled(uri: Uri, maxSide: Int): Bitmap? {
        val cr = appContext.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > maxSide * 2 || bounds.outHeight / sample > maxSide * 2) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    /** Builds a share sheet intent with the skin as a single .json file (image embedded). */
    fun shareIntent(skin: Skin): Intent {
        val img = skin.bgImage?.let { File(it) }?.takeIf { it.exists() }
            ?.let { Base64.encodeToString(it.readBytes(), Base64.NO_WRAP) }
        val json = skin.copy(bgImage = null).toJson(embeddedImageBase64 = img).toString(2)
        val dir = File(appContext.cacheDir, "shared").apply { mkdirs() }
        val safeName = skin.name.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifBlank { "skin" }
        val file = File(dir, "$safeName.duodeck.json")
        file.writeText(json)
        val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Duo-Deck skin: ${skin.name}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Share skin")
    }

    /** Reads a shared skin file. Returns the saved skin or null if the file isn't a skin. */
    fun importSkin(uri: Uri): Skin? = try {
        val text = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: null
        val o = JSONObject(text ?: "")
        if (!o.has("colors")) null else {
            var skin = Skin.fromJson(o).copy(bgImage = null)
            val data = o.optString("bgImageData", "")
            if (data.isNotBlank()) {
                val bytes = Base64.decode(data, Base64.DEFAULT)
                val f = File(imageDir, "${UUID.randomUUID()}.jpg")
                f.writeBytes(bytes)
                skin = skin.copy(bgImage = f.absolutePath)
            }
            save(skin)
        }
    } catch (_: Exception) { null }
}
