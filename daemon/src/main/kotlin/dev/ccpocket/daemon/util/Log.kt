package dev.ccpocket.daemon.util

import org.slf4j.Logger
import org.slf4j.LoggerFactory

/** slf4j logs go to stderr (slf4j-simple default) — never to a claude pipe. */
internal fun logger(name: String): Logger = LoggerFactory.getLogger("cc-pocket.$name")
