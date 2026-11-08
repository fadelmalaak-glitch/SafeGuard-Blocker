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

    /**
     * Encrypted-DNS (DoH) endpoints browsers use to bypass the system resolver.
     * Answering NXDOMAIN for these forces browsers back onto our filtered DNS.
     * "use-application-dns.net" is Firefox's canary: NXDOMAIN disables its default DoH.
     */
    private val dohHosts = listOf(
        "use-application-dns.net",
        "dns.google", "dns.google.com",
        "cloudflare-dns.com", "one.one.one.one", "1dot1dot1dot1.cloudflare-dns.com",
        "dns.quad9.net", "dns9.quad9.net", "dns10.quad9.net", "dns11.quad9.net",
        "doh.opendns.com", "dns.opendns.com",
        "dns.adguard.com", "dns.adguard-dns.com", "unfiltered.adguard-dns.com",
        "dns.nextdns.io", "doh.cleanbrowsing.org",
        "doh.mullvad.net", "dns.mullvad.net",
        "chrome.cloudflare-dns.com", "mozilla.cloudflare-dns.com",
        "doh.dns.sb", "dns.sb", "dns.alidns.com", "doh.pub"
    )

    fun isDohHost(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        return dohHosts.any { h == it || h.endsWith(".$it") }
    }

    /** Hostnames matched more loosely (any adult pattern appearing in the host). */
    fun isBlockedHost(host: String): Boolean {
        val h = host.lowercase()
        return adultPatterns.any { h.contains(it) }
    }
}
