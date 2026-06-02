package org.vechain.indexer.b3tr.action

enum class RoundRange(val pathValue: String, val roundCount: Int?) {
    THREE_M("3M", 13),
    SIX_M("6M", 26),
    ONE_Y("1Y", 52),
    ALL("ALL", null);

    companion object {
        fun fromPathValue(value: String): RoundRange =
            entries.find { it.pathValue.equals(value, ignoreCase = true) }
                ?: throw IllegalArgumentException(
                    "Invalid round range: $value. Allowed values: ${entries.map { it.pathValue }}"
                )
    }
}
