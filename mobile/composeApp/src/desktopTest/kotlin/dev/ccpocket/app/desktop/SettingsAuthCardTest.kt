package dev.ccpocket.app.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.ccpocket.app.assertPresent
import dev.ccpocket.app.present
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.settings_credential
import dev.ccpocket.app.resources.settings_preset_deactivate
import dev.ccpocket.app.resources.settings_switch_account
import dev.ccpocket.app.resources.settings_tab_account
import dev.ccpocket.app.str
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.protocol.AuthState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Settings ▸ Account — which authentication card each `claude auth status --json` shape gets (#318).
 *
 * The shapes below are the ones observed against a real CLI (2.1.x): a gateway token and Bedrock both
 * report `loggedIn: true` with NO email and NO apiKeySource, which the pre-#318 judgement
 * (`loggedIn && apiKeySource != null`) read as an OAuth login and answered with a switch/logout that
 * can't work. authMethod is the discriminator; a daemon that predates the field must still behave
 * exactly as before, so the null case is pinned here too. No real keys/tokens/URLs appear — the CLI
 * never puts a secret in these fields anyway (apiKeySource is a variable NAME).
 */
class SettingsAuthCardTest {

    @Test
    fun claudeAiLoginGetsTheOauthCard() {
        val s = AuthState(loggedIn = true, email = "user@example.test", subscriptionType = "max", authMethod = "claude.ai")
        assertEquals(AuthCard.Oauth, authPresentation(s))
    }

    @Test
    fun namedApiKeyGetsTheCredentialCardWithItsEnvVar() {
        val s = AuthState(loggedIn = true, authMethod = "api_key", apiKeySource = "ANTHROPIC_API_KEY")
        assertEquals(AuthCard.Credential, authPresentation(s))
        assertEquals(CredentialFlavor.EnvKey, credentialFlavor(s))
    }

    @Test
    fun gatewayAuthTokenGetsTheCredentialCardNotTheOauthOne() {
        // ANTHROPIC_BASE_URL + ANTHROPIC_AUTH_TOKEN: loggedIn, but no email and no apiKeySource
        val s = AuthState(loggedIn = true, authMethod = "oauth_token")
        assertEquals(AuthCard.Credential, authPresentation(s))
        assertEquals(CredentialFlavor.EnvToken, credentialFlavor(s))
    }

    @Test
    fun thirdPartyProviderGetsTheCredentialCardWithTheNeutralBadge() {
        // Bedrock / Vertex: provider-managed, there is no env var to name
        val s = AuthState(loggedIn = true, authMethod = "third_party")
        assertEquals(AuthCard.Credential, authPresentation(s))
        assertEquals(CredentialFlavor.Managed, credentialFlavor(s))
    }

    @Test
    fun authMethodNoneGetsTheUnconfiguredCard() {
        assertEquals(AuthCard.Unconfigured, authPresentation(AuthState(loggedIn = false, authMethod = "none")))
    }

    @Test
    fun oldDaemonWithoutAuthMethodFallsBackToTheLegacyJudgement() {
        // the pre-#318 three-way split, unchanged: named key -> credential, loggedIn -> oauth, else -> unconfigured
        assertEquals(
            AuthCard.Credential,
            authPresentation(AuthState(loggedIn = true, apiKeySource = "ANTHROPIC_API_KEY")),
        )
        assertEquals(AuthCard.Oauth, authPresentation(AuthState(loggedIn = true, email = "user@example.test")))
        // a gateway on an old daemon is indistinguishable from OAuth — it keeps landing on the OAuth card
        // (with switch/logout greyed out by the null email), which is the documented back-compat line
        assertEquals(AuthCard.Oauth, authPresentation(AuthState(loggedIn = true)))
        assertEquals(AuthCard.Unconfigured, authPresentation(AuthState(loggedIn = false)))
    }

    @Test
    fun unknownFutureAuthMethodFallsBackToTheLegacyJudgement() {
        val s = AuthState(loggedIn = true, authMethod = "something_new", apiKeySource = "ANTHROPIC_API_KEY")
        assertEquals(AuthCard.Credential, authPresentation(s))
        assertEquals(AuthCard.Oauth, authPresentation(AuthState(loggedIn = true, authMethod = "something_new")))
    }

    @Test
    fun aNamedSourceAlwaysWinsTheBadgeEvenOnAGatewayMethod() {
        val s = AuthState(loggedIn = true, authMethod = "oauth_token", apiKeySource = "ANTHROPIC_API_KEY")
        assertEquals(CredentialFlavor.EnvKey, credentialFlavor(s))
    }

    /** The end of the wire: a gateway login renders the credential card, not a dead account switch. */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun gatewayAuthRendersTheCredentialCardInsteadOfTheOauthActions() = runComposeUiTest {
        val m = object : SeedDesktopModel() {
            override val authState = AuthState(loggedIn = true, authMethod = "oauth_token")
        }
        setContent { PocketTheme { SettingsModal(m) {} } }
        onAllNodes(hasText(str(Res.string.settings_tab_account))).onFirst().performClick()
        waitForIdle()
        // the seeded preset owns the card until it's deactivated — then the computer's own auth shows
        onAllNodes(hasText(str(Res.string.settings_preset_deactivate))).onFirst().performClick()
        waitForIdle()

        assertPresent(str(Res.string.settings_credential))
        assertPresent("env · ANTHROPIC_AUTH_TOKEN")
        assertTrue(
            !present(str(Res.string.settings_switch_account)),
            "a gateway token can't be switched or logged out — the OAuth actions must not render",
        )
    }
}
