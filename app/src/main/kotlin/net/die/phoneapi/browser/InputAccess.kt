package net.die.phoneapi.browser

import net.die.phoneapi.model.ApiException
import net.die.phoneapi.model.BrowserInput
import net.die.phoneapi.model.BrowserInputKind
import net.die.phoneapi.model.Scope

/**
 * Whether a token with [scopes] may send [kind] input as touch. A touch key is a hardware key event
 * for whatever window has focus, which need not be the page: HOME, RECENTS, or ENTER on a system
 * dialog act outside the browser. So it needs the control scope as well as browser. Touch taps,
 * swipes, and gestures stay on the page, and text is only characters for the focused field.
 */
internal fun touchAllowed(kind: BrowserInputKind, scopes: Set<Scope>): Boolean =
    kind != BrowserInputKind.KEY || Scope.CONTROL in scopes

/** Refuses [input] of [kind] with 403 `forbidden` when [scopes] may not send it as touch. */
internal fun requireTouchAccess(kind: BrowserInputKind, input: BrowserInput, scopes: Set<Scope>) {
    if (input == BrowserInput.TOUCH && !touchAllowed(kind, scopes)) {
        throw ApiException.forbidden(Scope.CONTROL)
    }
}
