package mk.ry.redollars.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import mk.ry.redollars.MainActivity
import mk.ry.redollars.data.MessageRepository
import mk.ry.redollars.di.ApplicationScope
import mk.ry.redollars.net.Config
import mk.ry.redollars.ui.render.avatarUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject

/** Mention/reply pushes from the backend tailer (data-only FCM messages; we render
 *  the notification ourselves so blocking and foreground suppression apply). */
@AndroidEntryPoint
class RedollarsMessagingService : FirebaseMessagingService() {

    @Inject lateinit var repo: MessageRepository
    @Inject lateinit var http: OkHttpClient
    @Inject @field:ApplicationScope lateinit var scope: CoroutineScope

    override fun onNewToken(token: String) {
        scope.launch { repo.registerPushToken(token) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val messageId = data["message_id"]?.toLongOrNull() ?: return
        val uid = data["uid"]?.toLongOrNull() ?: 0L

        // The in-app bell already covers an open app; and never surface blocked users.
        if (ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        if (uid in repo.blockedUsers.value) return
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return

        val nickname = data["nickname"].orEmpty().ifBlank { "uid $uid" }
        val title = nickname + if (data["type"] == "reply") " 回复了你" else " 提及了你"
        val preview = stripBBCode(data["content"].orEmpty())
        val notifyId = data["notification_id"]?.toIntOrNull() ?: messageId.toInt()

        ensureChannel()

        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_JUMP_MESSAGE_ID, messageId)
        val tap = PendingIntent.getActivity(
            this, notifyId, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(preview)
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
        fetchAvatar(data["avatar"])?.let { builder.setLargeIcon(it) }

        runCatching {
            NotificationManagerCompat.from(this).notify(notifyId, builder.build())
        }
    }

    private fun ensureChannel() {
        val manager = getSystemService<NotificationManager>() ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "提及与回复", NotificationManager.IMPORTANCE_HIGH),
        )
    }

    /** lain.bgm.tv sits behind Cloudflare: fetch through our OkHttp client (never a
     *  platform loader), on this FCM callback's worker thread. */
    private fun fetchAvatar(avatar: String?): Bitmap? = runCatching {
        val req = Request.Builder()
            .url(avatarUrl(avatar, 'm'))
            .header("User-Agent", Config.BROWSER_UA)
            .build()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) return@runCatching null
            val bytes = res.body?.bytes() ?: return@runCatching null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let(::circleCrop)
        }
    }.getOrNull()

    private fun circleCrop(source: Bitmap): Bitmap {
        val size = minOf(source.width, source.height)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val path = Path().apply { addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CW) }
        canvas.clipPath(path)
        canvas.drawBitmap(
            source,
            (size - source.width) / 2f,
            (size - source.height) / 2f,
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
        )
        return output
    }

    private fun stripBBCode(content: String): String = content
        .replace(Regex("\\[img\\][\\s\\S]*?\\[/img\\]", RegexOption.IGNORE_CASE), "[图片]")
        .replace(Regex("\\[quote(?:=\\d+)?\\][\\s\\S]*?\\[/quote\\]", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\[/?[a-zA-Z][^\\]]*\\]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
        .ifEmpty { "…" }

    companion object {
        const val CHANNEL_ID = "mentions"
        const val EXTRA_JUMP_MESSAGE_ID = "jump_message_id"
    }
}
