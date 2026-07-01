package mk.ry.redollars.spike.ui.render

/** Ported from re-dollars-preact/src/utils/format.ts getAvatarUrl(). */
fun avatarUrl(avatar: String?, size: Char = 'l'): String {
    val a = avatar?.trim().orEmpty()
    if (a.isEmpty()) return "https://lain.bgm.tv/pic/user/$size/icon.jpg"
    if (a.contains("//")) {
        val withProto = if (a.startsWith("http")) a else "https:$a"
        return withProto.replace(Regex("/pic/user/[sml]/"), "/pic/user/$size/")
    }
    return "https://lain.bgm.tv/pic/user/$size/${a.trimStart('/')}"
}
