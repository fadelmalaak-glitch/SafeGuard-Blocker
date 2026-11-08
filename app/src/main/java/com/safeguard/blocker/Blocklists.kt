package com.safeguard.blocker

/**
 * Patterns used by the DNS engine and the accessibility guard to recognise adult
 * content. DNS filtering is primarily enforced upstream by the filtering resolvers
 * (CleanBrowsing Adult + Cloudflare for Families); these patterns are a fast local
 * backstop that also catches on-screen URLs/queries the resolver might miss
 * (e.g. browsers using their own DNS-over-HTTPS).
 */
object Blocklists {

    /** Substrings matched against hostnames (DNS) and on-screen text (accessibility). */
    val adultPatterns: List<String> = listOf(
        "pornhub", "xvideos", "xnxx", "xhamster", "redtube", "youporn",
        "spankbang", "eporner", "tube8", "brazzers", "onlyfans", "chaturbate",
        "stripchat", "livejasmin", "bongacams", "cam4", "camsoda", "myfreecams",
        "rule34", "nhentai", "hentai", "hanime", "hqporner", "beeg", "txxx",
        "tnaflix", "motherless", "daftsex", "sxyprn", "porntrex", "porngo",
        "fuq.com", "youjizz", "drtuber", "porn", "xxx", "sexvideo", "adultvideo",
        "nsfw", "escort", "camgirl",
        // Arabic terms
        "سكس", "اباحي", "إباحي", "افلام سكس", "مواقع اباحية", "بورن", "عاري"
    )

    /** Hostnames matched more loosely (any adult pattern appearing in the host). */
    fun isBlockedHost(host: String): Boolean {
        val h = host.lowercase()
        return adultPatterns.any { h.contains(it) }
    }
}
