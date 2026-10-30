// Original work © 2026 zhebuilds. AGPLv3 plus ADDITIONAL_TERMS.md.

package com.zhebuilds.tunepeerly

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Log
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import java.io.File

class MainActivity : TauriActivity() {
  private var multicastLock: WifiManager.MulticastLock? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)
    holdMulticastLock()
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

  override fun onDestroy() {
    multicastLock?.let { if (it.isHeld) it.release() }
    multicastLock = null
    super.onDestroy()
  }

  /**
   * Called from Rust over JNI. Android gives out `content://` URIs that the Rust side cannot read,
   * so the chosen tree is copied into the private music folder and scanned from there.
   */
  @Suppress("unused")
  fun openMusicFolderPicker() {
    runOnUiThread {
      val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
      }
      try {
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_FOLDER)
      } catch (err: Exception) {
        Log.w(TAG, "no folder picker: ${err.message}")
        Toast.makeText(this, "No file manager found", Toast.LENGTH_LONG).show()
      }
    }
  }

  @Deprecated("startActivityForResult keeps the picker readable without extra dependencies")
  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    if (requestCode != REQUEST_FOLDER) return
    val tree = data?.data
    if (resultCode != Activity.RESULT_OK || tree == null) return
    // The Rust scan looks in `app.path().data_dir()/music`, and on Android Tauri resolves
    // `data_dir` through `Context.getDataDir()` (the app data root). `filesDir` is that
    // root plus `files`, so copying next to it would import nothing.
    val root = File(applicationContext.dataDir, "music")
    Thread {
      val copied = try {
        copyTree(tree, root)
      } catch (err: Exception) {
        Log.w(TAG, "import failed: ${err.message}")
        -1
      }
      runOnUiThread {
        val text = if (copied >= 0) "Imported $copied audio file(s)" else "Import failed"
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
      }
    }.start()
  }

  /** Walks the picked document tree and copies every audio file, keeping folder names. */
  private fun copyTree(tree: Uri, root: File): Int {
    val resolver = contentResolver
    var copied = 0
    val pending = ArrayDeque<Pair<Uri, File>>()
    pending.addLast(tree to root)
    while (pending.isNotEmpty()) {
      val (dirUri, dirFile) = pending.removeLast()
      val children = DocumentsContract.buildChildDocumentsUriUsingTree(
        dirUri,
        DocumentsContract.getTreeDocumentId(dirUri),
      )
      resolver.query(
        children,
        arrayOf(
          DocumentsContract.Document.COLUMN_DOCUMENT_ID,
          DocumentsContract.Document.COLUMN_DISPLAY_NAME,
          DocumentsContract.Document.COLUMN_MIME_TYPE,
        ),
        null,
        null,
        null,
      )?.use { cursor ->
        while (cursor.moveToNext()) {
          val id = cursor.getString(0) ?: continue
          val name = safeName(cursor.getString(1) ?: continue)
          val mime = cursor.getString(2)
          val childUri = DocumentsContract.buildDocumentUriUsingTree(dirUri, id)
          if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
            pending.addLast(childUri to File(dirFile, name))
          } else if (isAudio(name)) {
            val target = File(dirFile, name)
            target.parentFile?.mkdirs()
            val stream = resolver.openInputStream(childUri)
            if (stream != null) {
              stream.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
              copied += 1
            }
          }
        }
      }
    }
    return copied
  }

  private fun isAudio(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS

  private fun safeName(name: String): String =
    name.replace('/', '_').replace('\u0000', '_').ifEmpty { "unnamed" }

  private companion object {
    const val TAG = "TunePeerly"
    const val REQUEST_FOLDER = 0x7A11
    val AUDIO_EXTENSIONS = setOf(
      "mp3", "flac", "m4a", "m4b", "aac", "ogg", "oga", "opus", "wav", "wave",
      "wma", "aiff", "aif", "alac", "ape", "wv", "mp4", "caf", "dsf", "dff", "tta", "spx",
    )
  }
}
