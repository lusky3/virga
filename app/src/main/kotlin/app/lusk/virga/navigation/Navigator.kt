package app.lusk.virga.navigation

import androidx.navigation3.runtime.NavKey

/**
 * Handles navigation events by updating [NavigationState]. A top-level route
 * switches the active tab; any other route is pushed onto the current tab's
 * back stack. Adapted from the official Navigation 2 → 3 migration guide.
 *
 * ia-06 (tab reselect): tapping the already-active tab clears its back stack
 * down to the root entry instead of no-op-ing or duplicating the root.
 */
class Navigator(val state: NavigationState) {
    fun navigate(route: NavKey) {
        if (route in state.backStacks.keys) {
            if (route == state.topLevelRoute) {
                // ia-06: reselect — pop back to this tab's root.
                val stack = state.backStacks[route] ?: return
                while (stack.size > 1) stack.removeLastOrNull()
            } else {
                state.topLevelRoute = route
            }
        } else {
            // Child route — push onto the current tab's stack.
            state.backStacks[state.topLevelRoute]?.add(route)
        }
    }

    /**
     * Cross-tab deep navigation: switch to [tab] and place [children] on its back
     * stack as a canonical chain (tab root, then the children in order). Used so a
     * destination that conceptually lives under one tab — e.g. What's-new under
     * Settings ▸ About — is reached in that single canonical place instead of being
     * duplicated onto whichever tab the user launched it from.
     *
     * The tab is reset to its root before the chain is rebuilt, so the result is
     * always the requested order regardless of what was previously on that tab's
     * stack (dedup-by-membership could otherwise append a missing earlier child
     * after a later one and invert the chain).
     */
    fun navigateInto(tab: NavKey, vararg children: NavKey) {
        val stack = state.backStacks[tab] ?: return
        while (stack.size > 1) stack.removeLastOrNull()
        children.distinct().forEach { child -> stack.add(child) }
        state.topLevelRoute = tab
    }

    /**
     * Handle a back event.
     *
     * - If the active tab has a child on its stack, pop it.
     * - Else, at a non-home tab root, switch to the home (start) tab.
     * - Else (already at the home tab root) do nothing and return `false` so the
     *   caller can run the "press back again to exit" flow.
     *
     * @return `true` if the event was consumed (popped or switched home);
     *   `false` if we are at the home tab root and the app should exit.
     */
    fun goBack(): Boolean {
        val currentStack = state.backStacks[state.topLevelRoute]
            ?: error("Stack for ${state.topLevelRoute} not found")
        return when {
            // A child is on the current tab's stack — pop it.
            currentStack.size > 1 -> {
                currentStack.removeLastOrNull()
                true
            }
            // At a non-home tab root — return to the home tab.
            state.topLevelRoute != state.startRoute -> {
                state.topLevelRoute = state.startRoute
                true
            }
            // At the home tab root — nothing to pop; let the caller handle exit.
            else -> false
        }
    }
}
