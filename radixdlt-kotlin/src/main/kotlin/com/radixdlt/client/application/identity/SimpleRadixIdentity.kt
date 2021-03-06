package com.radixdlt.client.application.identity

import com.radixdlt.client.application.objects.Data
import com.radixdlt.client.application.objects.UnencryptedData
import com.radixdlt.client.core.atoms.Atom
import com.radixdlt.client.core.atoms.UnsignedAtom
import com.radixdlt.client.core.crypto.CryptoException
import com.radixdlt.client.core.crypto.ECKeyPair
import com.radixdlt.client.core.crypto.ECKeyPairGenerator
import com.radixdlt.client.core.crypto.ECPublicKey
import com.radixdlt.client.core.crypto.MacMismatchException
import io.reactivex.Single
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class SimpleRadixIdentity @Throws(IOException::class)
constructor(myKeyFile: File) : RadixIdentity {
    private val myKey: ECKeyPair

    init {
        if (myKeyFile.exists()) {
            myKey = ECKeyPair.fromFile(myKeyFile)
        } else {
            myKey = ECKeyPairGenerator.newInstance().generateKeyPair()
            FileOutputStream(myKeyFile).use { io -> io.write(myKey.getPrivateKey()) }
        }
    }

    @Throws(IOException::class)
    @JvmOverloads constructor(fileName: String = "my.key") : this(File(fileName))

    override fun sign(atom: UnsignedAtom): Single<Atom> {
        return Single.create { emitter ->
            val signature = myKey.sign(atom.hash.toByteArray())
            val signatureId = myKey.getUID()
            emitter.onSuccess(atom.sign(signature, signatureId))
        }
    }

    override fun decrypt(data: Data): Single<UnencryptedData> {
        val encrypted = data.getMetaData()["encrypted"] as Boolean
        if (encrypted) {
            for (protector in data.protectors) {
                // TODO: remove exception catching
                try {
                    val bytes = myKey.decrypt(data.bytes!!, protector)
                    return Single.just(UnencryptedData(bytes, data.getMetaData(), true))
                } catch (e: MacMismatchException) {
                }
            }
            return Single.error(CryptoException("Cannot decrypt"))
        } else {
            return Single.just(UnencryptedData(data.bytes!!, data.getMetaData(), false))
        }
    }

    override fun getPublicKey(): ECPublicKey {
        return myKey.getPublicKey()
    }
}
