package com.calmapps.calmmusic

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import android.os.Bundle
import android.util.Log
import com.calmapps.calmmusic.data.CalmMusicDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Media3-based playback service.
 */
class PlaybackService : MediaLibraryService() {
    private var mediaSession: MediaLibrarySession? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val TAG = "PlaybackService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "calmmusic_playback_channel"
        
        // Android Auto Media Tree IDs
        private const val MEDIA_ROOT_ID = "[rootID]"
        private const val MEDIA_PLAYLISTS_ID = "[playlistsID]"
        private const val MEDIA_LIBRARY_ID = "[libraryID]"
        private const val MEDIA_SEARCH_ID = "[searchID]"
        private const val MEDIA_PLAYLIST_PREFIX = "[pl]"

        private var errorCallback: ((PlaybackException) -> Unit)? = null

        private const val NEWPIPE_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

        private const val BYPASS_COOKIES = "SOCS=CAI; VISITOR_INFO1_LIVE=i7Sm6Qgj0lE; CONSENT=YES+cb.20210328-17-p0.en+FX+475"

        fun registerErrorCallback(callback: ((PlaybackException) -> Unit)?) {
            errorCallback = callback
        }
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                30_000,
                120_000,
                500,
                1000
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val mediaSourceFactory = DefaultMediaSourceFactory(createDataSourceFactory())

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .build()

        val audioAttributes = androidx.media3.common.AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        player.setAudioAttributes(audioAttributes, true)

        player.setHandleAudioBecomingNoisy(true)

        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                errorCallback?.invoke(error)
                super.onPlayerError(error)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                super.onIsPlayingChanged(isPlaying)
                (application as? CalmMusic)?.playbackStateManager?.updatePlaybackStatus(isPlaying)
            }

            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                super.onMediaItemTransition(mediaItem, reason)
                val meta = mediaItem?.mediaMetadata
                if (meta != null) {
                    val uri = mediaItem.localConfiguration?.uri
                    val inferredSourceType = when (uri?.scheme) {
                        "content", "file" -> "LOCAL_FILE"
                        else -> "YOUTUBE"
                    }

                    (application as? CalmMusic)?.playbackStateManager?.updateState(
                        songId = mediaItem.mediaId,
                        title = meta.title?.toString() ?: "Unknown Title",
                        artist = meta.artist?.toString() ?: "Unknown Artist",
                        isPlaying = player.isPlaying,
                        sourceType = inferredSourceType,
                    )
                }
            }
        })

        val sessionActivityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            sessionActivityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        mediaSession = MediaLibrarySession.Builder(this, player, LibrarySessionCallback())
            .setSessionActivity(sessionActivityPendingIntent)
            .build()

        val notificationProvider = DefaultMediaNotificationProvider.Builder(this)
            .setChannelId(CHANNEL_ID)
            .setNotificationId(NOTIFICATION_ID)
            .build()

        setMediaNotificationProvider(notificationProvider)
    }

    @OptIn(UnstableApi::class)
    private fun createDataSourceFactory(): DataSource.Factory {
        val app = application as CalmMusic

        val okHttpClient = OkHttpClient.Builder()
            .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        val upstreamFactory = OkHttpDataSource.Factory(okHttpClient)
            .setUserAgent(NEWPIPE_USER_AGENT)
            .setDefaultRequestProperties(mapOf(
                "Cookie" to BYPASS_COOKIES,
                "Referer" to "https://www.youtube.com/"
            ))

        val resolvingFactory = ResolvingDataSource.Factory(upstreamFactory) { dataSpec ->
            val uri = dataSpec.uri
            val scheme = uri.scheme
            if (scheme == "content" || scheme == "file") {
                return@Factory dataSpec
            }

            val videoId = dataSpec.key
                ?: uri.getQueryParameter("v")
                ?: uri.lastPathSegment
                ?: return@Factory dataSpec

            val precache = app.youTubePrecacheManager
            val now = System.currentTimeMillis()
            val cached = precache.getCachedWithLabel(videoId, now)

            if (cached != null) {
                val (cachedUrl, cachedLabel) = cached
                app.playbackStateManager.updateStreamResolverLabel(cachedLabel)
                return@Factory dataSpec.withUri(cachedUrl.toUri())
            }

            val (resolvedUrl, resolverLabel) = runBlocking(Dispatchers.IO) {
                try {
                    app.youTubeInnertubeClient.getBestAudioUrl(videoId) to "Innertube/Piped"
                } catch (_: Exception) {
                    app.youTubeStreamResolver.getBestAudioUrl(videoId) to "NewPipe"
                }
            }
            precache.putUrl(videoId, resolvedUrl, resolverLabel, now)
            app.playbackStateManager.updateStreamResolverLabel(resolverLabel)

            dataSpec.withUri(resolvedUrl.toUri())
        }

        val networkAndCacheStack = CacheDataSource.Factory()
            .setCache(app.mediaCache)
            .setUpstreamDataSourceFactory(resolvingFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        return DefaultDataSource.Factory(this, networkAndCacheStack)
    }

    private fun createNotificationChannel() {
        val name = "CalmMusic playback"
        val descriptionText = "Music playback controls"
        val importance = NotificationManager.IMPORTANCE_LOW
        val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
            description = descriptionText
        }
        val notificationManager: NotificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        mediaSession?.player?.stop()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        mediaSession

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
    
    // ==================== MediaLibrarySession.Callback ====================

    private inner class LibrarySessionCallback : MediaLibrarySession.Callback {
        private var searchResults: List<MediaItem> = emptyList()
        private val playableItemCache = mutableMapOf<String, MediaItem>()

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val connectionResult = super.onConnect(session, controller)
            
            // Remove the timeline command to disable the Android Auto Queue button natively
            val playerCommands = connectionResult.availablePlayerCommands.buildUpon()
                .remove(Player.COMMAND_GET_TIMELINE)
                .remove(Player.COMMAND_CHANGE_MEDIA_ITEMS)
                .build()
                
            return MediaSession.ConnectionResult.accept(
                connectionResult.availableSessionCommands,
                playerCommands
            )
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: MediaLibraryService.LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val rootItem = MediaItem.Builder()
                .setMediaId(MEDIA_ROOT_ID)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(getString(R.string.app_name))
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                        .build()
                )
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(rootItem, params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: MediaLibraryService.LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()

            serviceScope.launch {
                try {
                    val children = when {
                        parentId == MEDIA_ROOT_ID -> getRootChildren()
                        parentId == MEDIA_PLAYLISTS_ID -> getPlaylistItems()
                        parentId == MEDIA_LIBRARY_ID -> getLibraryItems()
                        parentId.startsWith(MEDIA_PLAYLIST_PREFIX) -> {
                            val playlistId = parentId.removePrefix(MEDIA_PLAYLIST_PREFIX)
                            getPlaylistTracks(playlistId)
                        }
                        else -> ImmutableList.of()
                    }
                    future.set(LibraryResult.ofItemList(children, params))
                } catch (e: Exception) {
                    Log.e(TAG, "Error getting children for $parentId", e)
                    future.set(LibraryResult.ofItemList(ImmutableList.of(), params))
                }
            }
            return future
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            val future = SettableFuture.create<MutableList<MediaItem>>()
            serviceScope.launch {
                try {
                    val resolved = mediaItems.map { item ->
                        if (item.localConfiguration != null) {
                            item
                        } else {
                            resolveMediaItem(item.mediaId) ?: item
                        }
                    }.toMutableList()
                    future.set(resolved)
                } catch (e: Exception) {
                    Log.e(TAG, "Error resolving media items", e)
                    future.set(mediaItems)
                }
            }
            return future
        }

        private fun getRootChildren(): ImmutableList<MediaItem> {
            val playlists = MediaItem.Builder()
                .setMediaId(MEDIA_PLAYLISTS_ID)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(getString(R.string.auto_playlists))
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS)
                        .build()
                )
                .build()

            val library = MediaItem.Builder()
                .setMediaId(MEDIA_LIBRARY_ID)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(getString(R.string.auto_library))
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                        .build()
                )
                .build()

            return ImmutableList.of(playlists, library)
        }

        private suspend fun getPlaylistItems(): ImmutableList<MediaItem> {
            try {
                val database = CalmMusicDatabase.getDatabase(this@PlaybackService)
                val playlists = database.playlistDao().getAllPlaylists()
                
                val items = playlists.map { playlist ->
                    MediaItem.Builder()
                        .setMediaId(MEDIA_PLAYLIST_PREFIX + playlist.id)
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(playlist.name)
                                .setIsBrowsable(true)
                                .setIsPlayable(false)
                                .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                                .build()
                        )
                        .build()
                }
                return ImmutableList.copyOf(items)
            } catch (e: Exception) {
                Log.e(TAG, "Error loading playlists", e)
                return ImmutableList.of()
            }
        }

        private suspend fun getPlaylistTracks(playlistId: String): ImmutableList<MediaItem> {
            try {
                val database = CalmMusicDatabase.getDatabase(this@PlaybackService)
                val songs = database.playlistDao().getSongsForPlaylist(playlistId)
                
                val items = songs.map { song ->
                    val item = MediaItem.Builder()
                        .setMediaId(song.id)
                        .setUri(song.audioUri.toUri())
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(song.title)
                                .setArtist(song.artist)
                                .setIsBrowsable(false)
                                .setIsPlayable(true)
                                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                                .setExtras(Bundle().apply {
                                    putString("songId", song.id)
                                    putString("sourceType", song.sourceType)
                                })
                                .build()
                        )
                        .build()
                        
                    playableItemCache[song.id] = item
                    item
                }
                return ImmutableList.copyOf(items)
            } catch (e: Exception) {
                Log.e(TAG, "Error loading playlist tracks", e)
                return ImmutableList.of()
            }
        }

        private suspend fun getLibraryItems(): ImmutableList<MediaItem> {
            try {
                val database = CalmMusicDatabase.getDatabase(this@PlaybackService)
                val songs = database.songDao().getAllSongs()
                
                val items = songs.map { song ->
                    val item = MediaItem.Builder()
                        .setMediaId(song.id)
                        .setUri(song.audioUri.toUri())
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(song.title)
                                .setArtist(song.artist)
                                .setIsBrowsable(false)
                                .setIsPlayable(true)
                                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                                .setExtras(Bundle().apply {
                                    putString("songId", song.id)
                                    putString("sourceType", song.sourceType)
                                })
                                .build()
                        )
                        .build()
                        
                    playableItemCache[song.id] = item
                    item
                }
                return ImmutableList.copyOf(items)
            } catch (e: Exception) {
                Log.e(TAG, "Error loading library items", e)
                return ImmutableList.of()
            }
        }

        private suspend fun resolveMediaItem(mediaId: String): MediaItem? {
            // Check memory cache first to resolve immediately
            playableItemCache[mediaId]?.let { return it }

            // Check database fallback
            try {
                val database = CalmMusicDatabase.getDatabase(this@PlaybackService)
                val songs = database.songDao().getAllSongs()
                val song = songs.find { it.id == mediaId }
                
                if (song != null) {
                    val item = MediaItem.Builder()
                        .setMediaId(song.id)
                        .setUri(song.audioUri.toUri())
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(song.title)
                                .setArtist(song.artist)
                                .setIsBrowsable(false)
                                .setIsPlayable(true)
                                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                                .build()
                        )
                        .build()
                    playableItemCache[song.id] = item
                    return item
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error resolving media item for $mediaId", e)
            }
            return null
        }
    }
}