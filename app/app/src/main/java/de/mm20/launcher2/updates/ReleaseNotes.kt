package de.mm20.launcher2.updates

/** A publisher-written sentence, never generated from credentials or a network service. */
internal object ReleaseNotes {
    const val UNAVAILABLE = "Release notes unavailable."
    const val MAX_LENGTH = 160

    fun summary(body: String): String {
        var fenced = false
        val lines = body.replace(Regex("<!--[\\s\\S]*?-->"), "").lineSequence().filter { line ->
            if (line.trimStart().startsWith("```") || line.trimStart().startsWith("~~~")) {
                fenced = !fenced; false
            } else !fenced
        }.toList()
        val explicit = Regex("^(?:what['’]s new|summary):\\s*(.+)$", RegexOption.IGNORE_CASE)
        val source = lines.firstNotNullOfOrNull { explicit.matchEntire(it.trim())?.groupValues?.get(1) }
            ?: lines.dropWhile { it.isBlank() || it.trimStart().startsWith('#') }
                .takeWhile { it.isNotBlank() && !it.trimStart().startsWith('#') }.joinToString(" ")
        val plain = source
            .replace(Regex("!?\\[([^]]*)]\\([^)]*\\)"), "$1")
            .replace(Regex("<[^>]*>"), "")
            .replace(Regex("(^|\\s)[-*+]\\s+"), "$1")
            .replace(Regex("[*_`~]"), "")
            .map { if (it.isISOControl()) ' ' else it }.joinToString("")
            .replace(Regex("\\s+"), " ").trim()
        if (plain.isEmpty()) return UNAVAILABLE
        if (plain.length <= MAX_LENGTH) return plain
        var short = plain.take(MAX_LENGTH - 1).trimEnd()
        if (short.lastOrNull()?.isHighSurrogate() == true) short = short.dropLast(1)
        return "$short…"
    }
}
