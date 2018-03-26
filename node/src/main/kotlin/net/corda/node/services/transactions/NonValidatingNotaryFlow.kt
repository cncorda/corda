package net.corda.node.services.transactions

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.ComponentGroupEnum
import net.corda.core.flows.*
import net.corda.core.internal.validateRequest
import net.corda.core.node.services.TrustedAuthorityNotaryService
import net.corda.core.transactions.ContractUpgradeFilteredTransaction
import net.corda.core.transactions.CoreTransaction
import net.corda.core.transactions.FilteredTransaction
import net.corda.core.transactions.NotaryChangeWireTransaction
import net.corda.core.utilities.unwrap

class NonValidatingNotaryFlow(otherSideSession: FlowSession, service: TrustedAuthorityNotaryService) : NotaryFlow.Service(otherSideSession, service) {
    /**
     * The received transaction is not checked for contract-validity, as that would require fully
     * resolving it into a [TransactionForVerification], for which the caller would have to reveal the whole transaction
     * history chain.
     * As a result, the Notary _will commit invalid transactions_ as well, but as it also records the identity of
     * the caller, it is possible to raise a dispute and verify the validity of the transaction and subsequently
     * undo the commit of the input states (the exact mechanism still needs to be worked out).
     */
    @Suspendable
    override fun receiveAndVerifyTx(): TransactionParts {
        return otherSideSession.receive<NotarisationPayload>().unwrap { payload ->
            val transaction = payload.coreTransaction
            val request = NotarisationRequest(transaction.inputs, transaction.id)
            validateRequest(request, payload.requestSignature)
            extractParts(transaction)
        }
    }

    private fun extractParts(tx: CoreTransaction): TransactionParts {
        return when (tx) {
            is FilteredTransaction -> {
                tx.apply {
                    verify()
                    checkAllComponentsVisible(ComponentGroupEnum.INPUTS_GROUP)
                    checkAllComponentsVisible(ComponentGroupEnum.TIMEWINDOW_GROUP)
                    checkAllComponentsVisible(ComponentGroupEnum.REFERENCES_GROUP)
                }
                val notary = tx.notary
                TransactionParts(tx.id, tx.inputs, tx.timeWindow, notary, tx.references)
            }
            is ContractUpgradeFilteredTransaction -> TransactionParts(tx.id, tx.inputs, null, tx.notary, tx.references)
            is NotaryChangeWireTransaction -> TransactionParts(tx.id, tx.inputs, null, tx.notary, tx.references)
            else -> {
                throw IllegalArgumentException("Received unexpected transaction type: ${tx::class.java.simpleName}," +
                        "expected either ${FilteredTransaction::class.java.simpleName} or ${NotaryChangeWireTransaction::class.java.simpleName}")
            }
        }
    }
}