package com.nao.md.project

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class NaoMusicCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != NaoMusicPlaybackService.ACTION_PREVIOUS &&
            action != NaoMusicPlaybackService.ACTION_NEXT) return

        // Prefer handling the skip directly on the already-running app
        // instance, in memory. This is what makes Previous/Next actually
        // work from the notification: no UI is brought to the foreground,
        // so watching a video or playing a game is never interrupted.
        if (MainActivity.dispatchTransportCommand(action)) return

        // Fallback only: the app process was fully killed, so there is no
        // in-memory queue to advance. We have to start the Activity briefly
        // so it can resolve the next/previous track's stream.
        val activityIntent = Intent(context, MainActivity::class.java).apply {
            this.action = action
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        context.startActivity(activityIntent)
    }
}
