package com.journeybills.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.PriorityQueue

data class NetBalance(val participantId: Int?, val balance: BigDecimal)

data class Settlement(val fromId: Int?, val toId: Int?, val amount: BigDecimal)

object DebtSimplification {
    fun calculateSettlements(balances: List<NetBalance>): List<Settlement> {
        val debtors = PriorityQueue<NetBalance>(compareBy { it.balance })
        val creditors = PriorityQueue<NetBalance>(compareByDescending { it.balance })

        for (b in balances) {
            if (b.balance < BigDecimal.ZERO) debtors.add(b)
            else if (b.balance > BigDecimal.ZERO) creditors.add(b)
        }

        val settlements = mutableListOf<Settlement>()

        while (debtors.isNotEmpty() && creditors.isNotEmpty()) {
            val debtor = debtors.poll()!!
            val creditor = creditors.poll()!!

            val amount = debtor.balance.abs().min(creditor.balance)
            val roundedAmount = amount.setScale(2, RoundingMode.HALF_UP)
            
            if (roundedAmount > BigDecimal.ZERO) {
                settlements.add(Settlement(debtor.participantId, creditor.participantId, roundedAmount))
            }

            val remainingDebtorBalance = debtor.balance.add(amount)
            val remainingCreditorBalance = creditor.balance.subtract(amount)

            if (remainingDebtorBalance.setScale(2, RoundingMode.HALF_UP) < BigDecimal.ZERO) {
                debtors.add(NetBalance(debtor.participantId, remainingDebtorBalance))
            }
            if (remainingCreditorBalance.setScale(2, RoundingMode.HALF_UP) > BigDecimal.ZERO) {
                creditors.add(NetBalance(creditor.participantId, remainingCreditorBalance))
            }
        }
        return settlements
    }
}
