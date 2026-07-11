package mk.ry.redollars.ui.render

/**
 * Resolves a smiley token like `(bgm67)`, `(musume_12)`, `(blake_05)` to an absolute
 * image URL. Ported from re-dollars-preact/src/utils/smilies.ts getSmileyUrl(); paths
 * are served from lain.bgm.tv.
 */
object Smilies {
    private const val HOST = "https://lain.bgm.tv"
    private val LARGE = Regex("""\((musume_|blake_)(\d+)\)""")
    private val BGM = Regex("""\(bgm(\d+)\)""")

    fun urlFor(rawToken: String): String? {
        LARGE.find(rawToken)?.let { m ->
            val id = m.groupValues[2].toIntOrNull() ?: return null
            val path = when (m.groupValues[1]) {
                "musume_" -> "/img/smiles/musume/musume_${pad2(id)}.gif"
                "blake_" -> "/img/smiles/blake/blake_${pad2(id)}.gif"
                else -> return null
            }
            return HOST + path
        }
        val id = BGM.find(rawToken)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return bgmPath(id)?.let { HOST + it }
    }

    private fun bgmPath(id: Int): String? = when (id) {
        in 24..125 -> "/img/smiles/tv/${pad2(id - 23)}.gif"
        in 1..23 -> "/img/smiles/bgm/${pad2(id)}.${if (id == 11 || id == 23) "gif" else "png"}"
        in 200..238 -> "/img/smiles/tv_vs/bgm_$id.png"
        in 500..529 -> {
            val gif = setOf(500, 501, 505, 515, 516, 517, 518, 519, 521, 522, 523)
            "/img/smiles/tv_500/bgm_$id.${if (id in gif) "gif" else "png"}"
        }
        else -> null
    }

    private fun pad2(n: Int) = n.toString().padStart(2, '0')
}
