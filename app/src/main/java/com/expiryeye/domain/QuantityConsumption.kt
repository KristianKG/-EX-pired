package com.expiryeye.domain

data class QuantityConsumption(val remaining: Double, val itemFinished: Boolean)

fun consumeQuantity(available: Double, amount: Double): QuantityConsumption? {
    if (!available.isFinite() || available <= 0.0 || !amount.isFinite() || amount <= 0.0 || amount > available) return null
    val remaining = available - amount
    val finished = remaining <= maxOf(1e-9, available * 1e-9)
    return QuantityConsumption(if (finished) 0.0 else remaining, finished)
}
