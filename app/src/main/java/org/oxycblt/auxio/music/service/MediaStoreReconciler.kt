/*
 * Copyright (c) 2026 Auxio Project
 * MediaStoreReconciler.kt is part of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.oxycblt.auxio.music.service

import android.content.Context
import android.database.Cursor
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber as L

/**
 * Keeps MediaStore's audio entries in line with the files they point to, and tells whether they
 * changed since the library was last loaded.
 *
 * MediaStore is not always told when a file finishes changing. Files copied over adb (e.g. with
 * adb-sync) can be left as "pending" entries that no app can see until the device reboots, or be
 * indexed mid-copy with the wrong size. Since MediaStore-based loading only sees what MediaStore
 * knows about, [reconcile] finds such entries and asks the media scanner to rescan them. MediaStore
 * then notifies its observers, which reloads the library.
 *
 * This is a singleton since the service that uses it is recreated whenever the app returns to the
 * foreground, and its state has to outlive that.
 */
@Singleton
class MediaStoreReconciler @Inject constructor(@ApplicationContext private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Each version of a file is only rescanned once per process, so an entry the scanner won't
    // fix can't cause repeated rescans, while a file that changed since is still rescanned. (Such
    // an entry does cost one extra reload per process start, which isn't worth persisting this.)
    private val requestedScans = HashSet<String>()
    private var retryJob: Job? = null
    @Volatile private var loadedFingerprint: String? = null

    /**
     * Record that the library was loaded from MediaStore as it was at [fingerprint], taken with
     * [audioFingerprint] right before the load started.
     */
    fun markLoaded(fingerprint: String?) {
        loadedFingerprint = fingerprint
    }

    /** Whether MediaStore's audio entries changed since the last [markLoaded]. */
    fun changedSinceLoaded(): Boolean {
        val fingerprint = audioFingerprint() ?: return false
        return fingerprint != loadedFingerprint
    }

    /**
     * Rescan entries that are stuck pending, disagree with their file, or whose file is gone. Files
     * that were modified very recently are probably still being copied, so they are checked again
     * once they had time to settle.
     */
    fun reconcile() {
        scope.launch { reconcileNow() }
    }

    @Synchronized
    private fun reconcileNow() {
        val start = System.currentTimeMillis()
        val toScan = mutableListOf<String>()
        var settling = false
        try {
            queryAudio(scanProjection, includePending = true)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val path = cursor.getString(0) ?: continue
                    // Entries named like this are being written through the MediaStore API by an
                    // app that will finish them itself, leave them alone.
                    if (path.substringAfterLast('/').startsWith(".pending-")) continue
                    val file = File(path)
                    val modifiedMs = file.lastModified()
                    val version =
                        when {
                            modifiedMs == 0L -> "missing"
                            start - modifiedMs in 0 until SETTLE_TIME_MS -> {
                                settling = true
                                continue
                            }
                            cursor.isStale(file, modifiedMs) -> "${file.length()}:$modifiedMs"
                            else -> continue
                        }
                    if (requestedScans.add("$path:$version")) {
                        toScan.add(path)
                    }
                }
            }
        } catch (e: Exception) {
            L.e("Unable to reconcile MediaStore")
            L.e(e.stackTraceToString())
            return
        }
        L.d("Reconciled MediaStore in ${System.currentTimeMillis() - start}ms")
        if (toScan.isNotEmpty()) {
            L.d("Rescanning ${toScan.size} files")
            MediaScannerConnection.scanFile(context, toScan.toTypedArray(), null, null)
        }
        if (settling) {
            retryJob?.cancel()
            retryJob = scope.launch {
                delay(SETTLE_TIME_MS)
                reconcileNow()
            }
        }
    }

    private fun Cursor.isStale(file: File, modifiedMs: Long) =
        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && getInt(3) != 0) ||
            getLong(1) != file.length() ||
            getLong(2) != modifiedMs / 1000

    /**
     * A value that changes whenever an audio entry is added, changed or removed, or null if it
     * can't be computed on this version of Android.
     */
    fun audioFingerprint(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            queryAudio(arrayOf(MediaStore.MediaColumns.GENERATION_MODIFIED), false)?.use { cursor ->
                var maxGeneration = 0L
                while (cursor.moveToNext()) {
                    maxGeneration = maxOf(maxGeneration, cursor.getLong(0))
                }
                "${cursor.count}:$maxGeneration"
            }
        } catch (e: Exception) {
            L.e("Unable to fingerprint MediaStore")
            L.e(e.stackTraceToString())
            null
        }
    }

    private fun queryAudio(projection: Array<String>, includePending: Boolean): Cursor? {
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        return if (includePending && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val args = Bundle()
            args.putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            context.contentResolver.query(uri, projection, args, null)
        } else {
            context.contentResolver.query(uri, projection, null, null, null)
        }
    }

    private companion object {
        const val SETTLE_TIME_MS = 30 * 1000L

        // Pending entries can only be queried on R+ (see queryAudio).
        @Suppress("DEPRECATION") // DATA is still the only way to get a file path for an entry.
        val scanProjection =
            listOfNotNull(
                    MediaStore.MediaColumns.DATA,
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.DATE_MODIFIED,
                    MediaStore.MediaColumns.IS_PENDING.takeIf {
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    },
                )
                .toTypedArray()
    }
}
