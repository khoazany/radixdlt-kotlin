package com.radixdlt.client.core.ledger

import com.radixdlt.client.assets.Asset
import com.radixdlt.client.core.atoms.Atom
import com.radixdlt.client.core.atoms.AtomValidationException
import com.radixdlt.client.core.atoms.AtomValidator
import com.radixdlt.client.core.atoms.Consumer
import com.radixdlt.client.core.atoms.Particle
import com.radixdlt.client.core.crypto.ECSignature

class RadixAtomValidator private constructor() : AtomValidator {

    /**
     * Checks the owners of each AbstractConsumable particle and makes sure the
     * atom contains signatures for each owner
     *
     * @param atom atom to validate
     * @throws AtomValidationException if atom has missing/bad signatures for a particle
     */
    @Throws(AtomValidationException::class)
    fun validateSignatures(atom: Atom) {
        val hash = atom.hash

        val exception = atom.particles!!.asSequence()
            .filter(Particle::isAbstractConsumable)
            .map(Particle::asAbstractConsumable)
            .map { particle ->
                if (particle.ownersPublicKeys.isEmpty()) {
                    return@map AtomValidationException("No owners in particle")
                }

                if (particle.assetId == Asset.POW.id) {
                    return@map null
                }

                if (particle is Consumer) {
                    val consumerException: AtomValidationException? =
                        particle.ownersPublicKeys.asSequence().map keyMap@{ owner ->
                            val signature: ECSignature = atom.getSignature(owner.getUID())
                                ?: return@keyMap AtomValidationException("Missing signature")

                            if (!hash.verifySelf(owner, signature)) {
                                return@keyMap AtomValidationException("Bad signature")
                            }

                            return@keyMap null
                        }.filter {
                            it != null
                        }.firstOrNull() // In java it findsAny() from Optional

                    if (consumerException != null) {
                        return@map consumerException
                    }
                }

                null
            }.filter { it != null }.firstOrNull() // In java it findsAny() from Optional

        if (exception != null) {
            throw exception
        }
    }

    @Throws(AtomValidationException::class)
    override fun validate(atom: Atom) {
        // TODO: check with universe genesis timestamp
        if (atom.timestamp == 0L) {
            throw AtomValidationException("Null or Zero Timestamp")
        }

        validateSignatures(atom)
    }

    companion object {
        private val VALIDATOR = RadixAtomValidator()

        @JvmStatic
        fun getInstance(): RadixAtomValidator {
            return VALIDATOR
        }
    }
}
