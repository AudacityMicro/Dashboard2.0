package de.mm20.launcher2.updates

/** Dashboard's supported release tags, independent of upstream Kvaesitso versions. */
internal data class ReleaseVersion(val major: Long, val minor: Long, val patch: Long, val preview: Long?) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion): Int {
        listOf(major to other.major, minor to other.minor, patch to other.patch).forEach { (a, b) ->
            if (a != b) return a.compareTo(b)
        }
        if (preview == null) return if (other.preview == null) 0 else 1
        if (other.preview == null) return -1
        return preview.compareTo(other.preview)
    }
    companion object {
        fun parse(tag: String): ReleaseVersion? {
            val match = Regex("v?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-preview\\.(0|[1-9][0-9]*))?").matchEntire(tag) ?: return null
            val major = match.groupValues[1].toLongOrNull() ?: return null
            val minor = match.groupValues[2].toLongOrNull() ?: return null
            val patch = match.groupValues[3].toLongOrNull() ?: return null
            val preview = match.groupValues[4].let { if (it.isEmpty()) null else it.toLongOrNull() ?: return null }
            return ReleaseVersion(major, minor, patch, preview)
        }
    }
}
