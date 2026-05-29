package app.lusk.virga.navigation

import androidx.navigation3.runtime.NavKey

/**
 * Handles navigation events by updating [NavigationState]. A top-level route
 * switches the active tab; any other route is pushed onto the current tab's
 * back stack. Adapted from the official Navigation 2 → 3 migration guide.
 */
class Navigator(val state: NavigationState) {
    fun navigate(route: NavKey) {
        if (route in state.backStacks.keys) {
            // Top-level route — switch the active tab.
            state.topLevelRoute = route
        } else {
            // Child route — push onto the current tab's stack.
            state.backStacks[state.topLevelRoute]?.add(route)
        }
    }

    fun goBack() {
        val currentStack = state.backStacks[state.topLevelRoute]
            ?: error("Stack for ${state.topLevelRoute} not found")
        val currentRoute = currentStack.last()

        // At the base of a non-start tab, fall back to the start tab; otherwise pop.
        if (currentRoute == state.topLevelRoute) {
            state.topLevelRoute = state.startRoute
        } else {
            currentStack.removeLastOrNull()
        }
    }
}
