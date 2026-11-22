package com.simpmusic.media_ios.player

import com.maxrave.logger.Logger
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionInterruptionNotification
import platform.AVFAudio.AVAudioSessionInterruptionOptionKey
import platform.AVFAudio.AVAudioSessionInterruptionOptionShouldResume
import platform.AVFAudio.AVAudioSessionInterruptionTypeBegan
import platform.AVFAudio.AVAudioSessionInterruptionTypeEnded
import platform.AVFAudio.AVAudioSessionInterruptionTypeKey
import platform.AVFAudio.AVAudioSessionModeDefault
import platform.AVFAudio.AVAudioSessionRouteChangeNotification
import platform.AVFAudio.AVAudioSessionRouteChangeReasonKey
import platform.AVFAudio.AVAudioSessionRouteChangeReasonOldDeviceUnavailable
import platform.AVFAudio.setActive
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
import platform.darwin.NSObjectProtocol

private const val TAG = "AudioSession"

/**
 * The app's AVAudioSession: the Playback category, which is what keeps audio going with the screen
 * locked or another app in front (UIBackgroundModes `audio` in Info.plist is the other half), and
 * the two system events a music player has to answer.
 *
 * - An interruption (a call, Siri, an alarm) pauses; when it ends and the system says playback may
 *   resume, it resumes — but only if it was playing when the interruption began.
 * - Headphones or a Bluetooth speaker going away pauses, as every iOS player does, instead of
 *   carrying on out of the phone's speaker.
 */
internal class AudioSession(
    private val onPause: () -> Unit,
    private val onResume: () -> Unit,
    private val isPlaying: () -> Boolean,
) {
    private val observers = mutableListOf<NSObjectProtocol>()
    private var resumeAfterInterruption = false

    /**
     * Called on every play, not at launch: activating a Playback session stops whatever else the
     * phone was playing, which must not happen merely because SimpMusic was opened.
     */
    @OptIn(ExperimentalForeignApi::class)
    fun activate() {
        val session = AVAudioSession.sharedInstance()
        runCatching {
            session.setCategory(AVAudioSessionCategoryPlayback, mode = AVAudioSessionModeDefault, options = 0u, error = null)
            session.setActive(true, error = null)
        }.onFailure { Logger.e(TAG, "Could not activate the audio session: ${it.message}") }
        if (observers.isNotEmpty()) return
        val center = NSNotificationCenter.defaultCenter
        val queue = NSOperationQueue.mainQueue
        observers +=
            center.addObserverForName(AVAudioSessionInterruptionNotification, session, queue) { note ->
                note?.let(::onInterruption)
            }
        observers +=
            center.addObserverForName(AVAudioSessionRouteChangeNotification, session, queue) { note ->
                note?.let(::onRouteChange)
            }
    }

    fun release() {
        observers.forEach { NSNotificationCenter.defaultCenter.removeObserver(it) }
        observers.clear()
    }

    @OptIn(BetaInteropApi::class)
    private fun onInterruption(note: NSNotification) {
        val type = (note.userInfo?.get(AVAudioSessionInterruptionTypeKey) as? NSNumber)?.unsignedLongValue
        when (type) {
            AVAudioSessionInterruptionTypeBegan -> {
                resumeAfterInterruption = isPlaying()
                Logger.d(TAG, "Interruption began (was playing: $resumeAfterInterruption)")
                onPause()
            }

            AVAudioSessionInterruptionTypeEnded -> {
                val options = (note.userInfo?.get(AVAudioSessionInterruptionOptionKey) as? NSNumber)?.unsignedLongValue ?: 0u
                val shouldResume = options and AVAudioSessionInterruptionOptionShouldResume != 0uL
                Logger.d(TAG, "Interruption ended (should resume: $shouldResume)")
                if (shouldResume && resumeAfterInterruption) onResume()
                resumeAfterInterruption = false
            }
        }
    }

    private fun onRouteChange(note: NSNotification) {
        val reason = (note.userInfo?.get(AVAudioSessionRouteChangeReasonKey) as? NSNumber)?.unsignedLongValue
        if (reason == AVAudioSessionRouteChangeReasonOldDeviceUnavailable) {
            Logger.d(TAG, "Output went away, pausing")
            onPause()
        }
    }
}
