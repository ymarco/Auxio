/*
 * Copyright (c) 2024 Auxio Project
 * IndexingHolder.kt is part of Auxio.
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
import android.os.PowerManager
import coil3.ImageLoader
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.oxycblt.auxio.BuildConfig
import org.oxycblt.auxio.ForegroundListener
import org.oxycblt.auxio.ForegroundServiceNotification
import org.oxycblt.auxio.music.IndexingState
import org.oxycblt.auxio.music.MusicRepository
import org.oxycblt.auxio.music.MusicSettings
import org.oxycblt.auxio.music.locations.LocationMode
import org.oxycblt.auxio.playback.state.PlaybackStateManager
import org.oxycblt.auxio.util.getSystemServiceCompat
import org.oxycblt.musikr.MusicParent
import org.oxycblt.musikr.fs.mediastore.MediaStore
import org.oxycblt.musikr.fs.saf.SAF
import timber.log.Timber as L

class IndexingHolder
private constructor(
    private val workerContext: Context,
    private val foregroundListener: ForegroundListener,
    private val playbackManager: PlaybackStateManager,
    private val musicRepository: MusicRepository,
    private val musicSettings: MusicSettings,
    private val imageLoader: ImageLoader,
    private val reconciler: MediaStoreReconciler,
) :
    MusicRepository.IndexingWorker,
    MusicRepository.IndexingListener,
    MusicRepository.UpdateListener,
    MusicSettings.Listener {
    class Factory
    @Inject
    constructor(
        private val playbackManager: PlaybackStateManager,
        private val musicRepository: MusicRepository,
        private val musicSettings: MusicSettings,
        private val imageLoader: ImageLoader,
        private val reconciler: MediaStoreReconciler,
    ) {
        fun create(context: Context, listener: ForegroundListener) =
            IndexingHolder(
                context,
                listener,
                playbackManager,
                musicRepository,
                musicSettings,
                imageLoader,
                reconciler,
            )
    }

    private val indexJob = Job()
    private val indexScope = CoroutineScope(indexJob + Dispatchers.IO)
    private var currentIndexJob: Job? = null
    private val indexingNotification = IndexingNotification(workerContext)
    private val observingNotification = ObservingNotification(workerContext)
    private val wakeLock =
        workerContext
            .getSystemServiceCompat(PowerManager::class)
            .newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                BuildConfig.APPLICATION_ID + ":IndexingComponent",
            )
    private var trackingJob: Job? = null

    fun attach() {
        musicSettings.registerListener(this)
        musicRepository.addUpdateListener(this)
        musicRepository.addIndexingListener(this)
        musicRepository.registerWorker(this)
        startTracking()
    }

    fun release() {
        stopTracking()
        musicRepository.unregisterWorker(this)
        musicRepository.removeIndexingListener(this)
        musicRepository.removeUpdateListener(this)
        musicSettings.unregisterListener(this)
    }

    private val usesMediaStore
        get() = musicSettings.locationMode == LocationMode.MEDIA_STORE

    fun start() {
        when (musicRepository.indexingState) {
            is IndexingState.Completed -> checkForExternalChanges()
            // Either nothing was loaded yet, or a load is in progress. If it isn't ours, it was
            // cancelled when a previous instance of the service was released, and will never
            // finish, so restart it.
            else -> if (currentIndexJob?.isActive != true) requestIndex(true)
        }
    }

    /**
     * Called whenever the service is started or bound, e.g. when the app comes back to the
     * foreground. Unless automatic reloading keeps it in
     * the foreground, the service (and with it the MediaStore observer) stops while the app is in
     * the background, so changes made then (e.g. syncing new music) would only show up after the
     * app is restarted. Reload if MediaStore changed since the last load, and otherwise check that
     * MediaStore isn't missing anything.
     */
    private fun checkForExternalChanges() {
        if (!usesMediaStore) return
        indexScope.launch {
            if (reconciler.changedSinceLoaded()) {
                L.d("MediaStore changed since last load, reloading")
                requestIndex(true)
            } else {
                reconciler.reconcile()
            }
        }
    }

    fun createNotification(post: (ForegroundServiceNotification?) -> Unit) {
        val state = musicRepository.indexingState
        if (state is IndexingState.Indexing) {
            // There are a few reasons why we stay in the foreground with automatic rescanning:
            // 1. Newer versions of Android have become more and more restrictive regarding
            // how a foreground service starts. Thus, it's best to go foreground now so that
            // we can go foreground later.
            // 2. If a non-foreground service is killed, the app will probably still be alive,
            // and thus the music library will not be updated at all.
            val changed = indexingNotification.updateIndexingState(state.progress)
            if (changed) {
                post(indexingNotification)
            }
        } else if (musicSettings.shouldBeObserving) {
            // Not observing and done loading, exit foreground.
            L.d("Exiting foreground")
            post(observingNotification)
        } else {
            post(null)
        }
    }

    @Synchronized
    override fun requestIndex(withCache: Boolean) {
        L.d("Starting new indexing job (previous=${currentIndexJob?.hashCode()})")
        // Cancel the previous music loading job.
        currentIndexJob?.cancel()
        // Start a new music loading job on a co-routine.
        currentIndexJob = indexScope.launch {
            val mediaStore = usesMediaStore
            val fingerprint = if (mediaStore) reconciler.audioFingerprint() else null
            musicRepository.index(this@IndexingHolder, withCache)
            // Cancelled loads never get here. Loads can also stall while the app is frozen in the
            // background, so only a completed load means MediaStore's state at its start is shown.
            val state = musicRepository.indexingState
            if (mediaStore && state is IndexingState.Completed && state.error == null) {
                reconciler.markLoaded(fingerprint)
                // Fix up any MediaStore entries that are stuck or outdated (e.g. files synced with
                // adb-sync). If that changes MediaStore, the tracking observer reloads the library.
                reconciler.reconcile()
            }
        }
    }

    override fun onIndexingStateChanged() {
        foregroundListener.updateForeground(ForegroundListener.Change.INDEXER)
        val state = musicRepository.indexingState
        if (state is IndexingState.Indexing) {
            wakeLock.acquireSafe()
        } else {
            wakeLock.releaseSafe()
        }
    }

    override fun onMusicChanges(changes: MusicRepository.Changes) {
        val library = musicRepository.library ?: return
        L.d("Music changed, updating shared objects")
        // Wipe possibly-invalidated outdated covers
        imageLoader.memoryCache?.clear()
        // Clear invalid models from PlaybackStateManager. This is not connected
        // to a listener as it is bad practice for a shared object to attach to
        // the listener system of another.
        playbackManager.toSavedState()?.let { savedState ->
            playbackManager.applySavedState(
                savedState.copy(
                    parent =
                        savedState.parent?.let { musicRepository.find(it.uid) as? MusicParent? },
                    heap = savedState.heap.map { song -> song?.let { library.findSong(it.uid) } },
                ),
                true,
            )
        }
    }

    private fun startTracking() {
        stopTracking()
        val fs =
            when (musicSettings.locationMode) {
                LocationMode.MEDIA_STORE ->
                    MediaStore.from(workerContext, musicSettings.mediaStoreQuery)
                LocationMode.SAF -> SAF.from(workerContext, musicSettings.safQuery)
            }
        trackingJob = indexScope.launch { fs.track().collect { requestIndex(true) } }
    }

    private fun stopTracking() {
        trackingJob?.cancel()
        trackingJob = null
    }

    override fun onMusicLocationsChanged() {
        super.onMusicLocationsChanged()
        startTracking()
        musicRepository.requestIndex(true)
    }

    override fun onIndexingSettingChanged() {
        super.onIndexingSettingChanged()
        musicRepository.requestIndex(true)
    }

    override fun onObservingChanged() {
        super.onObservingChanged()
        // Make sure we don't override the service state with the observing
        // notification if we were actively loading when the automatic rescanning
        // setting changed. In such a case, the state will still be updated when
        // the music loading process ends.
        if (musicRepository.indexingState == null) {
            L.d("Not loading, updating idle session")
            foregroundListener.updateForeground(ForegroundListener.Change.INDEXER)
        }
    }

    /** Utility to safely acquire a [PowerManager.WakeLock] without crashes/inefficiency. */
    private fun PowerManager.WakeLock.acquireSafe() {
        // Avoid unnecessary acquire calls.
        if (!wakeLock.isHeld) {
            L.d("Acquiring wake lock")
            // Time out after a minute, which is the average music loading time for a medium-sized
            // library. If this runs out, we will re-request the lock, and if music loading is
            // shorter than the timeout, it will be released early.
            acquire(WAKELOCK_TIMEOUT_MS)
        }
    }

    /** Utility to safely release a [PowerManager.WakeLock] without crashes/inefficiency. */
    private fun PowerManager.WakeLock.releaseSafe() {
        // Avoid unnecessary release calls.
        if (wakeLock.isHeld) {
            L.d("Releasing wake lock")
            release()
        }
    }

    companion object {
        const val WAKELOCK_TIMEOUT_MS = 60 * 1000L
    }
}
