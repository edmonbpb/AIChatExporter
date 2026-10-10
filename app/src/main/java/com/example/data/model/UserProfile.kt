package com.example.data.model

data class UserProfile(
    val id: String,
    val name: String,
    val email: String,
    val avatarUrl: String? = null,
    val isGuest: Boolean = false,
    val isGoogleDriveLinked: Boolean = false,
    val lastSyncTimestamp: Long? = null,
    val role: String = "سازمانی / پژوهشگر"
)
