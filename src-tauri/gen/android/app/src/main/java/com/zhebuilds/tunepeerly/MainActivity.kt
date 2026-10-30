// Original work © 2026 zhebuilds. AGPLv3 plus ADDITIONAL_TERMS.md.

package com.zhebuilds.tunepeerly

import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Log
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.Keep
import java.io.File
import java.util.concurrent.Executors

class MainActivity : TauriActivity() {
  private var multicastLock: WifiManager.MulticastLock? = null
  private val importer = Executors.newSingleThreadExecutor()

  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)
    holdMulticastLock()
  }

  override fun onDestroy() {
    importer.shutdownNow()
    multicastLock?.let { if (it.isHeld) it.release() }
    multicastLock = null
    super.onDestroy()
  }

  /**
   * Android only hands multicast packets to apps that hold a MulticastLock, so nearby-device
   * discovery (UDP multicast on the LAN) stays silent without it. Held for the activity lifetime.
   */
  private fun holdMulticastLock() {
    try {
      val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
      if (wifi == null) {
        Log.w(TAG, "no WifiManager, discovery may not work")
        return
      }
      multicastLock = wifi.createMulticastLock("tunepeerly-discovery").also {
        it.setReferenceCounted(false)
        it.acquire()
      }
      Log.i(TAG, "multicast lock held")
    } catch (err: Exception) {
      Log.w(TAG, "multicast lock failed: ${err.message}")
    }
  }

  /**
   * Called from Rust over JNI, so release builds must keep this exact name. Android hands out
   * `content://` URIs that the Rust side cannot read, so the chosen tree is copied into the private
   * music folder and scanned from there once the copy is done.
   */
  @Keep
  @Suppress("unused")
  fun openMusicFolderPicker() {
    runOnUiThread {
      try {
        picker.launch(null)
      } catch (err: Exception) {
        Log.w(TAG, "no folder picker: ${err.message}")
        toast(getString(R.string.import_no_picker))
      }
    }
  }

  private val picker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
    if (tree != null) startImport(tree) else cancelImport()
  }

  private fun startImport(tree: Uri) {
    val music = musicDir()
    val marker = File(music, MARKER)
    val partial = File(music, "$MARKER.part")
    marker.delete()
    partial.delete()
    importer.execute {
      val copied = try {
        copyTree(tree, music)
      } catch (err: Exception) {
        Log.w(TAG, "import failed: ${err.message}")
        -1
      }
      runOnUiThread {
        if (copied < 0) {
          toast(getString(R.string.import_failed))
        } else if (copied == 0) {
          toast(getString(R.string.import_nothing_new))
        }
      }
      // Rust waits for this file: it means the folder is complete and safe to scan.
      writeMarker(marker, partial, copied)
    }
  }

  /**
   * Rust waits up to ten minutes for the marker, so a cancelled picker has to answer right away;
   * otherwise the user gets no feedback until that timeout runs out.
   */
  private fun cancelImport() {
    val music = musicDir()
    music.mkdirs()
    writeMarker(File(music, MARKER), File(music, "$MARKER.part"), IMPORT_CANCELLED)
  }

  /**
   * Tauri's `data_dir()` resolves to [`Context.getDataDir()`], while [`filesDir`] adds one more
   * `/files` level, so both sides have to agree on one of them: Rust scans `data_dir()/music`.
   */
  private fun musicDir(): File = File(applicationContext.dataDir, "music")

  /**
   * Written under a temporary name and renamed into place, so Rust never reads a half-written
   * number; a negative value says that there is nothing to scan and why.
   */
  private fun writeMarker(marker: File, partial: File, value: Int) {
    try {
      partial.parentFile?.mkdirs()
      partial.writeText(value.toString())
      if (partial.renameTo(marker)) return
      partial.delete()
      marker.writeText(value.toString())
    } catch (err: Exception) {
      Log.w(TAG, "could not write the import marker: ${err.message}")
    }
  }

  /**
   * Walks the picked document tree and copies every audio file, keeping folder names. Files that
   * are already there with the same size are skipped, so picking the same folder twice is cheap.
   */
  private fun copyTree(tree: Uri, root: File): Int {
    val resolver = contentResolver
    var copied = 0
    val rootId = DocumentsContract.getTreeDocumentId(tree)
    val visited = mutableSetOf(rootId)
    val pending = ArrayDeque<Pair<String, File>>()
    pending.addLast(rootId to root)
    while (pending.isNotEmpty()) {
      val (dirId, dirFile) = pending.removeLast()
      // Children must be listed with the id of *this* directory. The tree document id always
      // points at the picked root, so using it here would walk the root over and over again
      // instead of descending into subfolders.
      val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, dirId)
      resolver.query(
        children,
        arrayOf(
          DocumentsContract.Document.COLUMN_DOCUMENT_ID,
          DocumentsContract.Document.COLUMN_DISPLAY_NAME,
          DocumentsContract.Document.COLUMN_MIME_TYPE,
          DocumentsContract.Document.COLUMN_SIZE,
        ),
        null,
        null,
        null,
      )?.use { cursor ->
        while (cursor.moveToNext()) {
          val id = cursor.getString(0) ?: continue
          val name = safeName(cursor.getString(1) ?: continue)
          val mime = cursor.getString(2)
          val size = if (cursor.isNull(3)) -1L else cursor.getLong(3)
          val childUri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
          if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
            // A provider that reports a folder as its own child would otherwise copy until the
            // device runs out of space.
            if (visited.add(id)) pending.addLast(id to File(dirFile, name))
            continue
          }
          if (!isAudio(name)) continue
          val target = File(dirFile, name)
          if (size >= 0 && target.isFile && target.length() == size) continue
          target.parentFile?.mkdirs()
          // Copy under a temporary name first, so an interrupted copy is never mistaken for a track.
          val partial = File(target.parentFile, "$name.part")
          val stream = resolver.openInputStream(childUri) ?: continue
          stream.use { input -> partial.outputStream().use { output -> input.copyTo(output) } }
          if (!partial.renameTo(target)) {
            partial.delete()
            continue
          }
          copied += 1
        }
      }
    }
    return copied
  }

  private fun isAudio(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS

  private fun safeName(name: String): String =
    name.replace('/', '_').replace('\u0000', '_').ifEmpty { "unnamed" }

  private fun toast(text: String) {
    Toast.makeText(this, text, Toast.LENGTH_LONG).show()
  }

  private companion object {
    const val TAG = "TunePeerly"
    const val MARKER = ".import-done"
    /** Marker value meaning that the user closed the picker without choosing a folder. */
    const val IMPORT_CANCELLED = -2
    val AUDIO_EXTENSIONS = setOf(
      "mp3", "flac", "m4a", "m4b", "aac", "ogg", "oga", "opus", "wav", "wave",
      "wma", "aiff", "aif", "alac", "ape", "wv", "mp4", "caf", "dsf", "dff", "tta", "spx",
    )
  }
}
