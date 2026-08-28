package mk.ry.redollars.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Musume/Blake ("布布") dynamic-emoji size in chat: 小 keeps the classic inline
 *  size (2.6em side), 中 doubles and 大 quadruples the side length. */
enum class BubuScale(val factor: Float) { SMALL(1f), MEDIUM(2f), LARGE(4f) }

/** Chat display options ("界面设置" sheet). Defaults reproduce the classic layout:
 *  own bubbles on the right without avatar/name, others' avatars shown, media
 *  previews auto-loading and the quick-reaction row enabled. Turning off avatars or
 *  media previews is the traffic-saving path (avatars/posters skip loading entirely). */
data class DisplayPrefs(
    val showOwnAvatar: Boolean = false,
    val showOwnName: Boolean = false,
    /** Own messages hug the right edge; off mirrors them to the left, which also
     *  flips the swipe-to-reply direction (drag away from the screen edge). */
    val alignOwnRight: Boolean = true,
    val showOtherAvatars: Boolean = true,
    /** [img] blocks and video poster frames load immediately; off shows tap-to-load
     *  placeholders instead. Stickers/smilies stay inline text-sized media. */
    val autoLoadMediaPreviews: Boolean = true,
    /** Quick-reaction emoji row at the top of the long-press menu. */
    val showQuickReactions: Boolean = true,
    /** Side length multiplier for the musume/blake dynamic emojis. */
    val bubuScale: BubuScale = BubuScale.SMALL,
)

@Singleton
class DisplaySettings @Inject constructor(@ApplicationContext context: Context) {
    private val store = context.getSharedPreferences("display_prefs", Context.MODE_PRIVATE)

    private val _prefs = MutableStateFlow(read())
    val prefs: StateFlow<DisplayPrefs> = _prefs

    fun update(transform: (DisplayPrefs) -> DisplayPrefs) {
        val next = transform(_prefs.value)
        _prefs.value = next
        store.edit()
            .putBoolean(KEY_OWN_AVATAR, next.showOwnAvatar)
            .putBoolean(KEY_OWN_NAME, next.showOwnName)
            .putBoolean(KEY_ALIGN_RIGHT, next.alignOwnRight)
            .putBoolean(KEY_OTHER_AVATARS, next.showOtherAvatars)
            .putBoolean(KEY_AUTO_MEDIA, next.autoLoadMediaPreviews)
            .putBoolean(KEY_QUICK_REACTIONS, next.showQuickReactions)
            .putString(KEY_BUBU_SCALE, next.bubuScale.name)
            .apply()
    }

    private fun read() = DisplayPrefs(
        showOwnAvatar = store.getBoolean(KEY_OWN_AVATAR, false),
        showOwnName = store.getBoolean(KEY_OWN_NAME, false),
        alignOwnRight = store.getBoolean(KEY_ALIGN_RIGHT, true),
        showOtherAvatars = store.getBoolean(KEY_OTHER_AVATARS, true),
        autoLoadMediaPreviews = store.getBoolean(KEY_AUTO_MEDIA, true),
        showQuickReactions = store.getBoolean(KEY_QUICK_REACTIONS, true),
        bubuScale = store.getString(KEY_BUBU_SCALE, null)
            ?.let { runCatching { BubuScale.valueOf(it) }.getOrNull() }
            ?: BubuScale.SMALL,
    )

    private companion object {
        const val KEY_OWN_AVATAR = "show_own_avatar"
        const val KEY_OWN_NAME = "show_own_name"
        const val KEY_ALIGN_RIGHT = "align_own_right"
        const val KEY_OTHER_AVATARS = "show_other_avatars"
        const val KEY_AUTO_MEDIA = "auto_load_media_previews"
        const val KEY_QUICK_REACTIONS = "show_quick_reactions"
        const val KEY_BUBU_SCALE = "bubu_scale"
    }
}
