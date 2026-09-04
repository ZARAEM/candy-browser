package dev.sk2andy.materialbrowser.sync.firefox

import dev.sk2andy.firefoxsync.FirefoxAccountConfig

/**
 * Production Mozilla account configuration. Candy has no registered OAuth client yet, so test
 * builds use Firefox for Android's public client id with the web-channel redirect, as Fenix forks
 * do. Replace [CLIENT_ID] with Candy's own id before a public release.
 */
object FirefoxSyncDefaults {
    const val CLIENT_ID = "a2270f727f45f648"
    const val ENTRYPOINT = "candy-browser"

    val accountConfig: FirefoxAccountConfig = FirefoxAccountConfig(
        clientId = CLIENT_ID,
        redirectUri = FirefoxAccountConfig.WEB_CHANNEL_REDIRECT_URI,
    )

    /** Hosts the login WebView may navigate to; everything else is blocked. */
    val loginHosts: Set<String> = setOf(
        "accounts.firefox.com",
        "accounts-static.cdn.mozilla.net",
        "accounts.stage.mozaws.net",
        "accounts-static-cdn.stage.mozaws.net",
    )

    /** Origins whose web-channel messages the native bridge accepts. */
    val webChannelOrigins: Set<String> = setOf(
        "https://accounts.firefox.com",
        "https://accounts.stage.mozaws.net",
    )
}
