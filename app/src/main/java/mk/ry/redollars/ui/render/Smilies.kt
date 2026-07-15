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

    /** A named run of codes within a large group (ported from smilies.ts sections). */
    data class Section(val name: String, val codes: List<String>)

    /** A pickable smiley group. Standard groups (TV/BGM/VS/500) are flat runs of `bgm`
     *  codes; large groups (Musume/Blake) are animated stickers organized into
     *  [sections] and shown with bigger tiles. BMO needs the site's renderer, so it's
     *  omitted. */
    data class Group(
        val name: String,
        val codes: List<String>,
        val iconCode: String,
        val isLarge: Boolean = false,
        val sections: List<Section> = emptyList(),
    )

    /** Reaction-safe groups (ported from smileyRangesWithoutFavorites: TV/BGM/VS/500).
     *  Large stickers are excluded — Bangumi reactions accept `bgm` codes only. */
    val pickerGroups: List<Group> = listOf(
        Group("TV", (24..125).map { "(bgm$it)" }, "(bgm24)"),
        Group("BGM", (1..23).map { "(bgm$it)" }, "(bgm1)"),
        Group("VS", (200..238).map { "(bgm$it)" }, "(bgm200)"),
        Group("500", (500..529).map { "(bgm$it)" }, "(bgm500)"),
    )

    // Sparse sticker sections, ported verbatim from smilies.ts (musumeSmileySections /
    // blakeSmileySections). Blake reuses Musume's runs and adds a 得分反馈 group (97/98).
    private val musumeSections: List<List<Any>> = listOf(
        section("情绪反应", ids(6, 42, 100, 106, 108, 118)),
        section("动作道具", ids(43, 76, 101, 102, 103, 99, 107, 112, 109, 110, 111, 113, 114, 115, 116, 117)),
        section("日常状态", ids(77, 93, 104, 105, 94, 95, 96)),
        section("提示反馈", ids(1, 5)),
    )
    private val blakeSections: List<List<Any>> = listOf(
        musumeSections[0],
        musumeSections[1],
        section("得分反馈", listOf(97, 98)),
        musumeSections[2],
        musumeSections[3],
    )

    /** Groups offered in the composer's smiley panel: the reaction-safe groups plus the
     *  large Musume/Blake sticker sets, which insert `(musume_XX)`/`(blake_XX)` codes. */
    val composerGroups: List<Group> = pickerGroups + listOf(
        largeGroup("Musume", "musume_", musumeSections),
        largeGroup("Blake", "blake_", blakeSections),
    )

    private fun largeGroup(name: String, prefix: String, sections: List<List<Any>>): Group {
        // Each section is [title, id, id, …]; ported from getGroupedSmileyCodes().
        val builtSections = sections.map { section ->
            val title = section[0] as String
            val codes = section.drop(1).map { code(prefix, it as Int) }
            Section(title, codes)
        }
        return Group(
            name = name,
            codes = builtSections.flatMap { it.codes },
            iconCode = code(prefix, 3),
            isLarge = true,
            sections = builtSections,
        )
    }

    private fun code(prefix: String, id: Int) = "($prefix${pad2(id)})"

    /** Inclusive id range plus trailing extras, mirroring smilies.ts `ids()`. */
    private fun ids(start: Int, end: Int, vararg extra: Int): List<Int> =
        (start..end).toList() + extra.toList()

    private fun section(title: String, ids: List<Int>): List<Any> = listOf<Any>(title) + ids
}
