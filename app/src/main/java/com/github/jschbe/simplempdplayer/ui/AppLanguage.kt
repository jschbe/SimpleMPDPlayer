package com.github.jschbe.simplempdplayer.ui

/** Empty tag means follow Android; storage belongs exclusively to AppCompat/the platform. */
enum class AppLanguage(val tag: String) {
    SYSTEM(""), ENGLISH("en"), GERMAN("de"), FRENCH("fr"), ITALIAN("it"),
    SPANISH("es"), UKRAINIAN("uk"), RUSSIAN("ru");

    companion object {
        fun fromTags(tags: String): AppLanguage =
            if (tags.isBlank()) SYSTEM else entries.firstOrNull {
                it.tag == tags.substringBefore(',').substringBefore('-')
            } ?: ENGLISH
    }
}
