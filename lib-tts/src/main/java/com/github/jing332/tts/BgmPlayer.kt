package com.github.jing332.tts

import androidx.annotation.MainThread
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.github.jing332.common.utils.SafUtils
import com.github.jing332.tts.synthesizer.BgmSource
import com.github.jing332.tts.synthesizer.IBgmPlayer
import com.github.jing332.tts.synthesizer.event.NormalEvent
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.pow


class BgmPlayer(val context: SynthesizerContext) : IBgmPlayer {
    companion object {
        const val TAG = "BgmPlayer"
        val logger = KotlinLogging.logger(TAG)
    }

    private var exoPlayer: ExoPlayer? = null
    private val currentPlayList = mutableListOf<BgmSource>()
    private var currentSource: BgmSource? = null

    /**
     * 播放列表解析协程：SAF `content://` 目录要跨进程查询，必须离开主线程。
     * 这里只取消「列表解析」任务，不取消 scope——destroy() 后同一实例仍可能被重新 init()。
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var playlistJob: Job? = null

    @OptIn(UnstableApi::class)
    @MainThread
    override fun init() {
        logger.debug { "bgm init" }

        exoPlayer = exoPlayer ?: ExoPlayer.Builder(context.androidContext)
            .build().apply {
                addListener(object : Player.Listener {
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        super.onMediaItemTransition(mediaItem, reason)
                        mediaItem?.localConfiguration?.tag?.let { source ->
                            if (source is BgmSource) {
                                currentSource = source
                                context.event?.dispatch(NormalEvent.BgmCurrentPlaying(source))

                                if (source.volume != volume) {
                                    updateVolume(source.volume)
                                }
                            }
                        }

                    }

                    override fun onPlayerError(error: PlaybackException) {
                        super.onPlayerError(error)

                        logger.error(error) { "bgm error, skip current media" }
                        removeMediaItem(currentMediaItemIndex)
                        seekToNextMediaItem()
                        prepare()
                    }
                })
                repeatMode = Player.REPEAT_MODE_ALL
                shuffleModeEnabled = context.cfg.bgmShuffleEnabled()
            }
    }

    @MainThread
    override fun stop() {
        logger.debug { "bgm stop" }
        exoPlayer?.pause()
    }

    @MainThread
    override fun destroy() {
        logger.debug { "bgm destroy" }

        playlistJob?.cancel()
        playlistJob = null
        currentPlayList.clear()
        exoPlayer?.release()
        exoPlayer = null
    }

    @MainThread
    override fun play() {
        if (!context.cfg.bgmEnabled()) return

        logger.debug { "bgm play" }
        exoPlayer?.play()
    }

    fun updateVolume(volume: Float) {
        exoPlayer?.volume = volume.pow(1.6f)
    }

    @MainThread
    override fun setPlayList(
        list: List<BgmSource>,
    ) {
        logger.atDebug {
            message = "bgm setPlayList"
            payload = mapOf("list" to list)
        }

        if (list == currentPlayList) return
        currentPlayList.clear()
        currentPlayList.addAll(list)

        exoPlayer?.stop()
        exoPlayer?.clearMediaItems()

        // 条目现在多为 SAF content:// URI（单文件或目录树）：
        // 目录需要跨进程递归查询，放到 IO 线程；查完回主线程一次性喂给 ExoPlayer。
        playlistJob?.cancel()
        playlistJob = scope.launch {
            val mediaItems = withContext(Dispatchers.IO) {
                list.flatMap { source ->
                    // 单个条目（目录）内部是否打乱，跟随 BGM 随机播放设置
                    SafUtils.listAudio(context.androidContext, source.uri)
                        .run { if (context.cfg.bgmShuffleEnabled()) shuffled() else this }
                        .map { audio ->
                            MediaItem.Builder().setTag(source).setUri(audio.uriString).build()
                        }
                }
            }

            // 解析期间可能已 destroy()，此时直接丢弃
            if (exoPlayer == null) return@launch
            mediaItems.forEach { exoPlayer?.addMediaItem(it) }
            exoPlayer?.prepare()
        }
    }

}