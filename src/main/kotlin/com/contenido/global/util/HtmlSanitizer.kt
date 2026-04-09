package com.contenido.global.util

import org.jsoup.Jsoup
import org.jsoup.safety.Safelist

object HtmlSanitizer {
    fun sanitize(input: String): String =
        Jsoup.clean(input, Safelist.none())
}
