package com.github.fnunezkanut.bbcloud.config

enum class CheckedOutProjectRegistration {
    /** The entry was stored watched. */
    Watched,

    /** The entry was stored unwatched because the maximum number of projects is already watched. */
    AddedUnwatchedLimitReached,

    /** The folder is not a Bitbucket checkout of the configured workspace named after the slug; nothing stored. */
    NotResolvable,

    /** No workspace slug or projects directory is configured; nothing stored. */
    NotConfigured
}