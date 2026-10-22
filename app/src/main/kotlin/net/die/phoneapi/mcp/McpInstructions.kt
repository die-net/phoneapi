package net.die.phoneapi.mcp

import net.die.phoneapi.model.Scope

/**
 * The overview a client gets at `initialize`: that the phone is shared, which calls wait for the
 * screen, how to keep parallel work apart, and how to work in few calls. Lines about tools [scopes]
 * leave out are left out.
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
            "with session_start, and have it pass that id as session on every call it makes. Never " +
            "pass session on your own calls."
    )
    add("")
    add(
        "Every call is a round trip that re-reads your whole context, so the number of calls " +
            "costs more than the size of each result. To keep calls few:"
    )
    add(
        "- One step is two calls: the action, then one wait_for on what should appear, with " +
            "snapshot true to see the result, or screenshot true for maps, Street View, and " +
            "other screens whose tree is empty. Do not take a ui_snapshot before and after."
    )
    add(
        "- Act by selector when you know the label: tap and type_text take text, id, or desc " +
            "selectors, so they need no ui_snapshot first. Use ui_snapshot when you do not know " +
            "what is on screen, and ui_find to look for one thing."
    )
    add("- Actions and intents wake the screen themselves; device_wake is rarely needed.")
    if (Scope.BROWSER in scopes) {
        add(
            "- For a web page, use browser_open, which returns the target, rather than " +
                "open_intent. To read or search a page once, browser_read or browser_find with " +
                "url and close true does it in one call. browser_find takes several queries at " +
                "once. browser_tap waits for its selector and scrolls to it, and wait_for with " +
                "browser.element checks the result, so browser_eval is rarely needed."
        )
    }
}
    .joinToString("\n")
