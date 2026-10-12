package com.aicode.feature.github.model

data class GitHubRepository(
    val id: Long,
    val name: String,
    val fullName: String,
    val owner: String,
    val description: String?,
    val htmlUrl: String,
    val sshUrl: String,
    val cloneUrl: String,
    val private: Boolean,
    val archived: Boolean,
    val fork: Boolean,
    val language: String?,
    val updatedAt: String,
)

data class GitHubConfiguration(
    val token: String,
    val user: String,
    val proxyEnabled: Boolean,
    val httpProxy: String,
    val socketProxy: String,
)
