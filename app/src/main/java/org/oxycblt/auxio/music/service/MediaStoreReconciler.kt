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
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Brings MediaStore up to date with the audio files actually on disk.
 *
 * MediaStore is not always told about file changes. Files copied over adb (e.g. with adb-sync) can
 * be left as "pending" entries that no app can see until the device reboots, and deleted files can
 * keep stale entries. Since MediaStore-based loading only sees what MediaStore knows about, this
 * compares the folders that contain music against MediaStore, and asks the system to scan anything
 * that differs.
 *
 * Listing folders is very slow for apps (~50ms per folder, since it goes through the media
 * provider), so only the first run lists every folder. It saves the modification time of each
 * folder, and later runs only list the folders whose modification time changed, i.e. that had files
 * added or removed.
 */
class MediaStoreReconciler(private val context: Context) {
    private val snapshotFile = File(context.filesDir, "mediastore_reconciler_dirs.tsv")

    /**
     * A file whose MediaStore entry is missing or outdated, or a MediaStore entry whose file no
     * longer exists. [version] identifies the state of the file on disk, so that the same file can
     * be told apart from a file that changed since (e.g. finished copying).
     */
    data class StaleFile(val path: String, val version: String)

    /** Find the files where MediaStore and the disk disagree. */
    suspend fun findStaleFiles(): List<StaleFile> =
        withContext(Dispatchers.IO) {
            val start = System.currentTimeMillis()
            val known = queryKnownFiles()
            val knownByDir = known.keys.groupBy { it.substringBeforeLast('/') }
            val roots = findRoots(known.keys)
            val snapshot = loadSnapshot()
            val run = Run(known, knownByDir)
            if (snapshot == null) {
                for (root in roots) {
                    run.walk(root)
                }
            } else {
                val dirs = HashSet<String>(snapshot.keys)
                for (root in roots) {
                    dirs.add(root.path)
                }
                // Include the folders of known files and their ancestors, so that folders MediaStore
                // learned about outside of this reconciler are tracked too.
                for (dir in knownByDir.keys) {
                    var current = dir
                    while (roots.any { current.startsWith(it.path) } && dirs.add(current)) {
                        current = current.substringBeforeLast('/')
                    }
                }
                for (dir in dirs) {
                    run.checkIfChanged(dir, snapshot, dirs)
                }
            }
            // Don't remember folders with stale files as up to date, so that they are checked
            // again next time in case scanning them doesn't go through.
            for (file in run.stale) {
                run.dirModifiedMs.remove(file.path.substringBeforeLast('/'))
            }
            saveSnapshot(run.dirModifiedMs)
            Log.i(
                TAG,
                "Reconciled ${known.size} MediaStore entries in ${run.dirModifiedMs.size} folders " +
                    "(listed ${run.listedDirs}, full=${snapshot == null}) in " +
                    "${System.currentTimeMillis() - start}ms, ${run.stale.size} stale",
            )
            run.stale
        }

    private class Run(
        private val known: Map<String, KnownFile>,
        private val knownByDir: Map<String, List<String>>,
    ) {
        val stale = mutableListOf<StaleFile>()
        val dirModifiedMs = HashMap<String, Long>()
        var listedDirs = 0

        /** List [dir] and everything under it. */
        fun walk(dir: File) {
            val children = list(dir) ?: return
            for (child in children) {
                if (child.isDirectory) walk(child)
            }
        }

        /** List [dir] only if it changed since [snapshot]. New sub-folders are walked fully. */
        fun checkIfChanged(dir: String, snapshot: Map<String, Long>, tracked: Set<String>) {
            val file = File(dir)
            val modifiedMs = file.lastModified()
            if (modifiedMs == 0L) {
                // The folder is gone, so are any files MediaStore thinks are in it.
                knownByDir[dir]?.mapTo(stale) { StaleFile(it, MISSING) }
                return
            }
            if (snapshot[dir] == modifiedMs) {
                dirModifiedMs[dir] = modifiedMs
                return
            }
            val children = list(file) ?: return
            for (child in children) {
                if (child.isDirectory && child.path !in tracked) walk(child)
            }
        }

        /**
         * List [dir], recording its modification time and comparing its audio files against
         * MediaStore. Returns the non-hidden children, or null if it can't be listed.
         */
        private fun list(dir: File): List<File>? {
            val modifiedMs = dir.lastModified()
            val children = dir.listFiles()?.filter { !it.name.startsWith(".") } ?: return null
            listedDirs++
            dirModifiedMs[dir.path] = modifiedMs
            val present = HashSet<String>()
            for (child in children) {
                if (child.extension.lowercase() !in AUDIO_EXTENSIONS || child.isDirectory) continue
                present.add(child.path)
                val entry = known[child.path]
                val size = child.length()
                val modifiedSecs = child.lastModified() / 1000
                if (entry == null || entry.size != size || entry.modifiedSecs != modifiedSecs) {
                    stale.add(StaleFile(child.path, "$size:$modifiedSecs"))
                }
            }
            knownByDir[dir.path]?.forEach { if (it !in present) stale.add(StaleFile(it, MISSING)) }
            return children
        }
    }

    private fun loadSnapshot(): Map<String, Long>? =
        try {
            if (!snapshotFile.exists()) {
                null
            } else {
                snapshotFile.useLines { lines ->
                    lines
                        .mapNotNull { line ->
                            val tab = line.indexOf('\t')
                            if (tab < 0) return@mapNotNull null
                            val modifiedMs = line.substring(0, tab).toLongOrNull()
                            modifiedMs?.let { line.substring(tab + 1) to it }
                        }
                        .toMap()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unable to read folder snapshot", e)
            null
        }

    private fun saveSnapshot(dirModifiedMs: Map<String, Long>) {
        try {
            val tmp = File(snapshotFile.path + ".tmp")
            tmp.bufferedWriter().use { writer ->
                for ((dir, modifiedMs) in dirModifiedMs) {
                    writer.write(modifiedMs.toString())
                    writer.write('\t'.code)
                    writer.write(dir)
                    writer.write('\n'.code)
                }
            }
            tmp.renameTo(snapshotFile)
        } catch (e: Exception) {
            Log.e(TAG, "Unable to save folder snapshot", e)
        }
    }

    /**
     * Ask the system to scan [paths], waiting until it is done (or a timeout passes).
     *
     * @return The number of paths that were scanned.
     */
    suspend fun scan(paths: List<String>): Int {
        if (paths.isEmpty()) return 0
        val scanned = AtomicInteger(0)
        withTimeoutOrNull(SCAN_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                MediaScannerConnection.scanFile(context, paths.toTypedArray(), null) { _, _ ->
                    if (scanned.incrementAndGet() == paths.size && continuation.isActive) {
                        continuation.resume(Unit)
                    }
                }
            }
        }
        Log.i(TAG, "Scanned ${scanned.get()}/${paths.size} paths")
        return scanned.get()
    }

    private data class KnownFile(val size: Long, val modifiedSecs: Long)

    @Suppress("DEPRECATION") // DATA is still the only way to get a file path for an entry.
    private fun queryKnownFiles(): Map<String, KnownFile> {
        val known = HashMap<String, KnownFile>()
        context.contentResolver
            .query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(
                    MediaStore.Audio.Media.DATA,
                    MediaStore.Audio.Media.SIZE,
                    MediaStore.Audio.Media.DATE_MODIFIED,
                ),
                null,
                null,
                null,
            )
            ?.use { cursor ->
                while (cursor.moveToNext()) {
                    val path = cursor.getString(0) ?: continue
                    known[path] = KnownFile(cursor.getLong(1), cursor.getLong(2))
                }
            }
        return known
    }

    /**
     * The top-level folders of each storage volume that contain music (e.g. /storage/emulated/0/Music),
     * plus the standard music folder. Walking only these avoids walking all of storage.
     */
    private fun findRoots(knownPaths: Collection<String>): Set<File> {
        val roots = LinkedHashSet<String>()
        @Suppress("DEPRECATION")
        roots.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC).path)
        for (path in knownPaths) {
            ROOT_REGEX.find(path)?.let { roots.add(it.value) }
        }
        return roots.map(::File).filterTo(LinkedHashSet()) { it.isDirectory }
    }

    private companion object {
        const val TAG = "MediaStoreReconciler"
        const val MISSING = "missing"

        /** Matches a volume root (e.g. /storage/emulated/0 or /storage/1234-ABCD) plus one folder. */
        val ROOT_REGEX = Regex("^/storage/(?:emulated/\\d+|[^/]+)/[^/]+(?=/)")
        val AUDIO_EXTENSIONS =
            setOf(
                "mp3",
                "m4a",
                "m4b",
                "mp4",
                "aac",
                "flac",
                "ogg",
                "oga",
                "opus",
                "wav",
                "wma",
                "aif",
                "aiff",
                "mka",
                "alac",
                "ape",
                "wv",
            )
        const val SCAN_TIMEOUT_MS = 5 * 60 * 1000L
    }
}
