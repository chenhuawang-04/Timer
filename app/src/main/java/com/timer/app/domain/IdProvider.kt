package com.timer.app.domain

import java.util.UUID

interface IdProvider {
    fun newId(): String
}

class UuidIdProvider : IdProvider {
    override fun newId(): String = UUID.randomUUID().toString()
}
