package com.automatelinux.wakeUp.data.model

import kotlinx.serialization.Serializable

// Sample shared model — @Serializable so kotlinx-serialization works on Android
// and iOS. Replace with your app's real models.
@Serializable
data class Sample(val id: Int, val name: String)
