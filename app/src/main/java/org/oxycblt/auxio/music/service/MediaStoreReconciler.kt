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
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import java.io.File

/**
 * Brings MediaStore's audio entries up to date with the files they point to.
 *
 * MediaStore is not always told when a file finishes changing. Files copied over adb (e.g. with
 * adb-sync) can be left as "pending" entries that no app can see until the device reboots, or be
 * indexed mid-copy with the wrong size. Since MediaStore-based loading only sees what MediaStore
 * knows about, this finds such entries and asks the media scanner to rescan them. MediaStore then
 * notifies its observers, which reloads the library.
 */
class MediaStoreReconciler(private val context: Context) {
    /**
     * An entry to rescan. [version] identifies the state of the file on disk, so a file that changed
     * since it was last rescanned can be told apart from one the scanner won't index.
     */
    data class StaleFile(val path: String, val version: String)

    /**
     * Find the entries that are stuck pending, disagree with their file, or whose file is gone.
     * This is cheap: one query plus a stat per entry (~50ms for 5000 songs).
     */
    @Suppress("DEPRECATION") // DATA is still the only way to get a file path for an entry.
    fun findStaleFiles(): List<StaleFile> {
        val start = System.currentTimeMillis()
        val stale = mutableListOf<StaleFile>()
        val projection =
            arrayOf(
                MediaStore.MediaColumns.DATA,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.IS_PENDING,
            )
        try {
            query(projection)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val path = cursor.getString(0) ?: continue
                    // Entries named like this are being written through the MediaStore API by
                    // an app that will finish them itself, leave them alone.
                    if (path.substringAfterLast('/').startsWith(".pending-")) continue
                    val file = File(path)
                    val modifiedMs = file.lastModified()
                    if (modifiedMs == 0L) {
                        stale.add(StaleFile(path, MISSING))
                        continue
                    }
                    // Don't rescan files that are probably still being copied, the scanner would
                    // just record a partial file again.
                    if (start - modifiedMs < SETTLE_TIME_MS) continue
                    val size = file.length()
                    val isPending = cursor.getInt(3) != 0
                    if (
                        isPending ||
                            cursor.getLong(1) != size ||
                            cursor.getLong(2) != modifiedMs / 1000
                    ) {
                        stale.add(StaleFile(path, "$size:$modifiedMs"))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unable to reconcile MediaStore", e)
            return emptyList()
        }
        Log.i(TAG, "Found ${stale.size} stale entries in ${System.currentTimeMillis() - start}ms")
        return stale
    }

    /** Ask the media scanner to rescan [paths]. Returns immediately. */
    fun scan(paths: List<String>) {
        Log.i(TAG, "Rescanning ${paths.size} files")
        MediaScannerConnection.scanFile(context, paths.toTypedArray(), null, null)
    }

    private fun query(projection: Array<String>) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Pending entries are hidden by default, and are exactly the ones we're after.
            val args =
                Bundle().apply {
                    putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
                }
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                args,
                null,
            )
        } else {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                null,
            )
        }

    private companion object {
        const val TAG = "MediaStoreReconciler"
        const val MISSING = "missing"
        const val SETTLE_TIME_MS = 30 * 1000L
    }
}
