package dev.ccpocket.app.media

import androidx.compose.runtime.Composable

/** Desktop has no image picker — cc-pocket's image upload is a phone feature. */
@Composable
actual fun rememberImageAttacher(onPicked: (List<ByteArray>) -> Unit): () -> Unit = {}
