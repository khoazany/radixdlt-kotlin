package com.radixdlt.client.application

import com.radixdlt.client.application.actions.DataStore
import com.radixdlt.client.application.actions.TokenTransfer
import com.radixdlt.client.application.actions.UniqueProperty
import com.radixdlt.client.application.identity.RadixIdentity
import com.radixdlt.client.application.objects.Data
import com.radixdlt.client.application.objects.UnencryptedData
import com.radixdlt.client.application.translate.ConsumableDataSource
import com.radixdlt.client.application.translate.DataStoreTranslator
import com.radixdlt.client.application.translate.TokenTransferTranslator
import com.radixdlt.client.application.translate.TransactionAtoms
import com.radixdlt.client.application.translate.UniquePropertyTranslator
import com.radixdlt.client.assets.Amount
import com.radixdlt.client.assets.Asset
import com.radixdlt.client.core.RadixUniverse
import com.radixdlt.client.core.address.RadixAddress
import com.radixdlt.client.core.atoms.ApplicationPayloadAtom
import com.radixdlt.client.core.atoms.AtomBuilder
import com.radixdlt.client.core.atoms.Consumable
import com.radixdlt.client.core.atoms.TransactionAtom
import com.radixdlt.client.core.atoms.UnsignedAtom
import com.radixdlt.client.core.crypto.ECPublicKey
import com.radixdlt.client.core.ledger.RadixLedger
import com.radixdlt.client.core.network.AtomSubmissionUpdate
import com.radixdlt.client.core.network.AtomSubmissionUpdate.AtomSubmissionState
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.annotations.Nullable
import io.reactivex.observables.ConnectableObservable
import io.reactivex.rxkotlin.Observables
import java.util.Objects

/**
 * The Radix Dapp API, a high level api which dapps can utilize. The class hides
 * the complexity of Atoms and cryptography and exposes a simple high level interface.
 */
class RadixApplicationAPI private constructor(
    val myIdentity: RadixIdentity,
    private val universe: RadixUniverse,
    private val atomBuilderSupplier: () -> AtomBuilder
) {

    private val dataStoreTranslator: DataStoreTranslator = DataStoreTranslator.instance

    val ledger: RadixLedger = universe.ledger

    private val consumableDataSource = ConsumableDataSource(ledger)
    private val tokenTransferTranslator = TokenTransferTranslator(universe, consumableDataSource)
    private val uniquePropertyTranslator = UniquePropertyTranslator()

    val myAddress: RadixAddress
        get() = ledger.getAddressFromPublicKey(myIdentity.getPublicKey())

    val myPublicKey: ECPublicKey
        get() = myIdentity.getPublicKey()

    class Result internal constructor(private val updates: Observable<AtomSubmissionUpdate>) {
        private val completable: Completable

        init {
            this.completable = updates.filter { it.isComplete }
                .firstOrError()
                .flatMapCompletable { update ->
                    if (update.getState() === AtomSubmissionState.STORED) {
                        return@flatMapCompletable Completable.complete()
                    } else {
                        return@flatMapCompletable Completable.error(RuntimeException(update.message))
                    }
                }
        }

        fun toObservable(): Observable<AtomSubmissionUpdate> {
            return updates
        }

        fun toCompletable(): Completable {
            return completable
        }
    }

    fun getReadableData(address: RadixAddress): Observable<UnencryptedData> {
        Objects.requireNonNull(address)
        return ledger.getAllAtoms(address.getUID(), ApplicationPayloadAtom::class.java)
            .map { dataStoreTranslator.fromAtom(it) }
            .flatMapMaybe { data -> myIdentity.decrypt(data).toMaybe().onErrorComplete() }
    }

    fun storeData(data: Data): Result {
        return this.storeData(data, myAddress)
    }

    fun storeData(data: Data, address: RadixAddress): Result {
        val dataStore = DataStore(data, address)

        val atomBuilder = atomBuilderSupplier()
        val updates: ConnectableObservable<AtomSubmissionUpdate> = dataStoreTranslator.translate(dataStore, atomBuilder)
            .andThen(Single.fromCallable { atomBuilder.buildWithPOWFee(ledger.magic, address.publicKey) })
            .flatMap(myIdentity::sign)
            .flatMapObservable(ledger::submitAtom)
            .replay()

        updates.connect()

        return Result(updates)
    }

    fun storeData(data: Data, address0: RadixAddress, address1: RadixAddress): Result {
        val dataStore = DataStore(data, address0, address1)

        val atomBuilder = atomBuilderSupplier()
        val updates: ConnectableObservable<AtomSubmissionUpdate> = dataStoreTranslator.translate(dataStore, atomBuilder)
            .andThen(Single.fromCallable { atomBuilder.buildWithPOWFee(ledger.magic, address0.publicKey) })
            .flatMap(myIdentity::sign)
            .flatMapObservable(ledger::submitAtom)
            .replay()

        updates.connect()

        return Result(updates)
    }

    fun getMyTokenTransfers(tokenClass: Asset): Observable<TokenTransfer> {
        return getTokenTransfers(myAddress, tokenClass)
    }

    fun getTokenTransfers(address: RadixAddress, tokenClass: Asset): Observable<TokenTransfer> {
        Objects.requireNonNull(address)
        Objects.requireNonNull(tokenClass)
        return Observables.combineLatest<TransactionAtoms, TransactionAtom, Observable<TransactionAtom>>(
            Observable.fromCallable { TransactionAtoms(address, tokenClass.id) },
            ledger.getAllAtoms(address.getUID(), TransactionAtom::class.java)
        ) { transactionAtoms, atom ->
            transactionAtoms.accept(atom).newValidTransactions
        }
            .flatMap { atoms -> atoms.map { tokenTransferTranslator.fromAtom(it) } }
    }

    fun getMyBalance(tokenClass: Asset): Observable<Amount> {
        return getBalance(myAddress, tokenClass)
    }

    fun getBalance(address: RadixAddress, tokenClass: Asset): Observable<Amount> {
        Objects.requireNonNull(address)
        Objects.requireNonNull(tokenClass)
        return this.consumableDataSource.getConsumables(address)
            .map { it.asSequence() }
            .map { sequence ->
                sequence.filter { consumable ->
                    consumable.assetId == tokenClass.id
                }
                    .map(Consumable::quantity)
                    .sum()
            }
            .map { balanceInSubUnits -> Amount.subUnitsOf(balanceInSubUnits, tokenClass) }
            .share()
    }

    /**
     * Sends an amount of a token to an address
     *
     * @param to the address to send tokens to
     * @param amount the amount and token type
     * @return result of the transaction
     */
    fun sendTokens(to: RadixAddress, amount: Amount): Result {
        return transferTokens(myAddress, to, amount)
    }

    /**
     * Sends an amount of a token with a data attachment to an address
     *
     * @param to the address to send tokens to
     * @param amount the amount and token type
     * @param attachment the data attached to the transaction
     * @return result of the transaction
     */
    fun sendTokens(to: RadixAddress, amount: Amount, attachment: Data?): Result {
        return transferTokens(myAddress, to, amount, attachment)
    }

    /**
     * Sends an amount of a token with a data attachment to an address with a unique property
     * meaning that no other transaction can be executed with the same unique bytes
     *
     * @param to the address to send tokens to
     * @param amount the amount and token type
     * @param attachment the data attached to the transaction
     * @param unique the bytes representing the unique id of this transaction
     * @return result of the transaction
     */
    fun sendTokens(to: RadixAddress, amount: Amount, attachment: Data?, unique: ByteArray?): Result {
        return transferTokens(myAddress, to, amount, attachment, unique)
    }

    fun transferTokens(from: RadixAddress, to: RadixAddress, amount: Amount): Result {
        return transferTokens(from, to, amount, null, null)
    }

    fun transferTokens(
        from: RadixAddress,
        to: RadixAddress,
        amount: Amount,
        attachment: Data?
    ): Result {
        return transferTokens(from, to, amount, attachment, null)
    }

    fun transferTokens(
        from: RadixAddress,
        to: RadixAddress,
        amount: Amount,
        attachment: Data?,
        unique: ByteArray? // TODO: make unique immutable
    ): Result {
        Objects.requireNonNull(from)
        Objects.requireNonNull(to)
        Objects.requireNonNull(amount)

        val tokenTransfer = TokenTransfer.create(from, to, amount.getTokenClass(), amount.amountInSubunits, attachment)
        val uniqueProperty: UniqueProperty?
        if (unique != null) {
            // Unique Property must be the from address so that all validation occurs in a single shard.
            // Once multi-shard validation is implemented this constraint can be removed.
            uniqueProperty = UniqueProperty(unique, from)
        } else {
            uniqueProperty = null
        }

        return executeTransaction(tokenTransfer, uniqueProperty)
    }

    // TODO: make this more generic
    private fun executeTransaction(tokenTransfer: TokenTransfer, @Nullable uniqueProperty: UniqueProperty?): Result {
        Objects.requireNonNull(tokenTransfer)

        val atomBuilder = atomBuilderSupplier()

        val updates = uniquePropertyTranslator.translate(uniqueProperty, atomBuilder)
            .andThen(tokenTransferTranslator.translate(tokenTransfer, atomBuilder))
            .andThen(Single.fromCallable<UnsignedAtom> {
                atomBuilder.buildWithPOWFee(
                    ledger.magic,
                    tokenTransfer.from!!.publicKey
                )
            })
            .flatMap(myIdentity::sign)
            .flatMapObservable(ledger::submitAtom)
            .replay()

        updates.connect()

        return Result(updates)
    }

    companion object {

        @JvmStatic
        fun create(identity: RadixIdentity): RadixApplicationAPI {
            Objects.requireNonNull(identity)
            return RadixApplicationAPI(identity, RadixUniverse.getInstance(), ::AtomBuilder)
        }

        @JvmStatic
        fun create(
            identity: RadixIdentity,
            universe: RadixUniverse,
            atomBuilderSupplier: () -> AtomBuilder
        ): RadixApplicationAPI {
            Objects.requireNonNull(identity)
            Objects.requireNonNull(universe)
            Objects.requireNonNull(atomBuilderSupplier)
            return RadixApplicationAPI(identity, universe, atomBuilderSupplier)
        }
    }
}
