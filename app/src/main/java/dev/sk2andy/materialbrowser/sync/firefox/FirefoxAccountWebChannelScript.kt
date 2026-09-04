package dev.sk2andy.materialbrowser.sync.firefox

import dev.sk2andy.firefoxsync.FirefoxAccountOAuth
import org.json.JSONArray
import org.json.JSONObject

/**
 * Document-start script for the Mozilla account login WebView. The login page speaks the Firefox
 * WebChannel DOM protocol: it dispatches `WebChannelMessageToChrome` events and expects
 * `WebChannelMessageToContent` replies. Status and account-link requests are answered locally, as
 * Fenix does; every message is also forwarded to the native bridge so the OAuth code can be
 * captured and diagnostics can show the last command.
 */
object FirefoxAccountWebChannelScript {
    const val BRIDGE_NAME = "candyFxaBridge"
    const val TOKEN_KEY = "token"
    val DEFAULT_ENGINES: List<String> = listOf("tabs")

    private val tokenPattern = Regex("[A-Za-z0-9_-]{32,80}")

    fun javascript(
        bridgeToken: String,
        clientId: String,
        engines: List<String> = DEFAULT_ENGINES,
    ): String {
        require(tokenPattern.matches(bridgeToken)) { "Invalid bridge token" }
        require(clientId.matches(Regex("[0-9a-f]{16}"))) { "Invalid client id" }
        require(engines.isNotEmpty() && engines.all { it.matches(Regex("[a-z]{1,32}")) }) { "Invalid engines" }
        val enginesJson = JSONArray(engines).toString()
        return """
            (() => {
              if (globalThis.__candyFxaWebChannelInstalled) return;
              const bridge = globalThis.$BRIDGE_NAME;
              if (!bridge || typeof bridge.postMessage !== 'function') return;
              globalThis.__candyFxaWebChannelInstalled = true;
              const token = '$bridgeToken';
              const channelId = '${FirefoxAccountOAuth.WEB_CHANNEL_ID}';
              const clientId = '$clientId';
              const engines = $enginesJson;
              const nativePost = bridge.postMessage.bind(bridge);
              const stringify = JSON.stringify.bind(JSON);
              const parse = JSON.parse.bind(JSON);
              const dispatch = globalThis.dispatchEvent.bind(globalThis);
              const reply = (messageId, command, data) => {
                const detail = { id: channelId, message: { messageId, command, data } };
                dispatch(new CustomEvent('WebChannelMessageToContent', { detail }));
              };
              const forward = (id, command, messageId, data) => {
                try {
                  nativePost(stringify({ $TOKEN_KEY: token, id, message: { command, messageId, data } }));
                } catch (error) {}
              };
              globalThis.addEventListener('WebChannelMessageToChrome', (event) => {
                let detail = event.detail;
                try {
                  if (typeof detail === 'string') detail = parse(detail);
                } catch (error) {
                  return;
                }
                if (!detail || detail.id !== channelId || !detail.message) return;
                const message = detail.message;
                const command = String(message.command || '');
                const messageId = message.messageId;
                const data = message.data === undefined ? null : message.data;
                if (command === '${FirefoxAccountOAuth.WEB_CHANNEL_STATUS_COMMAND}') {
                  reply(messageId, command, {
                    capabilities: { engines, choose_what_to_sync: false, multiService: false, pairing: false },
                    signedInUser: null,
                    clientId,
                  });
                } else if (command === '${FirefoxAccountOAuth.WEB_CHANNEL_CAN_LINK_COMMAND}') {
                  reply(messageId, command, { ok: true });
                }
                forward(detail.id, command, messageId, data);
              }, true);
            })();
        """.trimIndent()
    }

    /** Splits a native bridge envelope into its token and the plain web-channel message. */
    fun unwrapEnvelope(raw: String?, expectedToken: String): JSONObject? {
        val envelope = runCatching { JSONObject(raw ?: return null) }.getOrNull() ?: return null
        if (envelope.optString(TOKEN_KEY) != expectedToken) return null
        envelope.remove(TOKEN_KEY)
        return envelope
    }
}
