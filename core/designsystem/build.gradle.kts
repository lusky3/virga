plugins {
    id("virga.android.library")
    id("virga.android.compose")
}

android {
    namespace = "app.lusk.virga.core.designsystem"
}

dependencies {
    // Compose (BOM + the shared `compose` bundle, incl. Material 3 and the
    // extended Material icon set) is supplied by the virga.android.compose
    // convention plugin. This module owns the Virga design system: theme,
    // color/spacing/shape/motion tokens, and the shared composables every
    // feature module builds on. It absorbed the former :core:ui module.
}
