plugins {
    id("virga.android.library")
    id("virga.android.compose")
}

android {
    namespace = "app.lusk.virga.core.ui"
}

dependencies {
    // Compose (BOM + the shared `compose` bundle, incl. Material 3) is supplied
    // by the virga.android.compose convention plugin. This module hosts small,
    // dependency-free design-system composables shared across feature modules.
}
