package net.die.phoneapi.mcp

import net.die.phoneapi.model.Scope

/**
 * The overview a client gets at `initialize`: that the phone is shared, which calls wait for the
 * screen, and how to keep parallel work apart. Lines about tools [scopes] leave out are left out.
 */
internal fun mcpInstructions(scopes: Set<Scope>): String = buildList {
    add(
        "PhoneAPI drives one Android phone that other agents, scripts, and people may be using " +
            "at the same time."
    )
    add(
        "- Only one of them uses the screen at a time. Calls that act on or show the screen " +
            "(taps, typing, keys, app launches, ui_snapshot, ui_find, screenshot, touch input on " +
            "browser tabs, wait_for on the UI) take it, waiting up to 60 s if someone else has " +
            "it, then fail with screen_busy. A result can start with # note: lines saying what " +
            "changed meanwhile; check the screen before acting."
    )
    if (Scope.BROWSER in scopes) {
        add(
            "- Reading browser tabs, and cdp or cdp-mouse input on your own tabs, never need the " +
                "screen, so prefer them for browser work. Tabs you open are yours; others cannot " +
                "see or use them."
        )
    }
    add(
        "- When you are done with the screen for a while, call screen_release. Holding it idle " +
            "for 30 s while someone waits also lets it go."
    )
    add(
        "- If you run subagents or parallel tasks that use the phone, give each its own session " +
            "with session_start, and have it pass that id as session on every call it makes."
    )
}
    .joinToString("\n")
