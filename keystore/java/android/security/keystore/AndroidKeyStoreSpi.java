/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.security.keystore;

import libcore.util.EmptyArray;

import android.security.Credentials;
import android.security.KeyStore;
import android.security.KeyStoreParameter;
import android.security.keymaster.KeyCharacteristics;
import android.security.keymaster.KeymasterArguments;
import android.security.keymaster.KeymasterDefs;
import android.security.keystore.KeyProperties;
import android.security.keystore.KeyProtection;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.Key;
import java.security.KeyStore.Entry;
import java.security.KeyStore.PrivateKeyEntry;
import java.security.KeyStore.ProtectionParameter;
import java.security.KeyStore.SecretKeyEntry;
import java.security.KeyStoreException;
import java.security.KeyStoreSpi;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import javax.crypto.SecretKey;

/**
 * A java.security.KeyStore interface for the Android KeyStore. An instance of
 * it can be created via the {@link java.security.KeyStore#getInstance(String)
 * KeyStore.getInstance("AndroidKeyStore")} interface. This returns a
 * java.security.KeyStore backed by this "AndroidKeyStore" implementation.
 * <p>
 * This is built on top of Android's keystore daemon. The convention of alias
 * use is:
 * <p>
 * PrivateKeyEntry will have a Credentials.USER_PRIVATE_KEY as the private key,
 * Credentials.USER_CERTIFICATE as the first certificate in the chain (the one
 * that corresponds to the private key), and then a Credentials.CA_CERTIFICATE
 * entry which will have the rest of the chain concatenated in BER format.
 * <p>
 * TrustedCertificateEntry will just have a Credentials.CA_CERTIFICATE entry
 * with a single certificate.
 *
 * @hide
 */
public class AndroidKeyStoreSpi extends KeyStoreSpi {
    public static final String NAME = "AndroidKeyStore";

    private KeyStore mKeyStore;

    @Override
    public Key engineGetKey(String alias, char[] password) throws NoSuchAlgorithmException,
            UnrecoverableKeyException {
        if (isPrivateKeyEntry(alias)) {
            String privateKeyAlias = Credentials.USER_PRIVATE_KEY + alias;
            return AndroidKeyStoreProvider.loadAndroidKeyStorePrivateKeyFromKeystore(
                    mKeyStore, privateKeyAlias);
        } else if (isSecretKeyEntry(alias)) {
            String secretKeyAlias = Credentials.USER_SECRET_KEY + alias;
            return AndroidKeyStoreProvider.loadAndroidKeyStoreSecretKeyFromKeystore(
                    mKeyStore, secretKeyAlias);
        } else {
            // Key not found
            return null;
        }
    }

    @Override
    public Certificate[] engineGetCertificateChain(String alias) {
        if (alias == null) {
            throw new NullPointerException("alias == null");
        }

        final X509Certificate leaf = (X509Certificate) engineGetCertificate(alias);
        if (leaf == null) {
            return null;
        }

        final Certificate[] caList;

        final byte[] caBytes = mKeyStore.get(Credentials.CA_CERTIFICATE + alias);
        if (caBytes != null) {
            final Collection<X509Certificate> caChain = toCertificates(caBytes);

            caList = new Certificate[caChain.size() + 1];

            final Iterator<X509Certificate> it = caChain.iterator();
            int i = 1;
            while (it.hasNext()) {
                caList[i++] = it.next();
            }
        } else {
            caList = new Certificate[1];
        }

        caList[0] = leaf;

        return caList;
    }

    @Override
    public Certificate engineGetCertificate(String alias) {
        if (alias == null) {
            throw new NullPointerException("alias == null");
        }

        byte[] certificate = mKeyStore.get(Credentials.USER_CERTIFICATE + alias);
        if (certificate != null) {
            return wrapIntoKeyStoreCertificate(
                    Credentials.USER_PRIVATE_KEY + alias, toCertificate(certificate));
        }

        certificate = mKeyStore.get(Credentials.CA_CERTIFICATE + alias);
        if (certificate != null) {
            return wrapIntoKeyStoreCertificate(
                    Credentials.USER_PRIVATE_KEY + alias, toCertificate(certificate));
        }

        return null;
    }

    /**
     * Wraps the provided cerificate into {@link KeyStoreX509Certificate} so that the public key
     * returned by the certificate contains information about the alias of the private key in
     * keystore. This is needed so that Android Keystore crypto operations using public keys can
     * find out which key alias to use. These operations cannot work without an alias.
     */
    private static KeyStoreX509Certificate wrapIntoKeyStoreCertificate(
            String privateKeyAlias, X509Certificate certificate) {
        return (certificate != null)
                ? new KeyStoreX509Certificate(privateKeyAlias, certificate) : null;
    }

    private static X509Certificate toCertificate(byte[] bytes) {
        try {
            final CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
            return (X509Certificate) certFactory.generateCertificate(
                    new ByteArrayInputStream(bytes));
        } catch (CertificateException e) {
            Log.w(NAME, "Couldn't parse certificate in keystore", e);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Collection<X509Certificate> toCertificates(byte[] bytes) {
        try {
            final CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
            return (Collection<X509Certificate>) certFactory.generateCertificates(
                            new ByteArrayInputStream(bytes));
        } catch (CertificateException e) {
            Log.w(NAME, "Couldn't parse certificates in keystore", e);
            return new ArrayList<X509Certificate>();
        }
    }

    private Date getModificationDate(String alias) {
        final long epochMillis = mKeyStore.getmtime(alias);
        if (epochMillis == -1L) {
            return null;
        }

        return new Date(epochMillis);
    }

    @Override
    public Date engineGetCreationDate(String alias) {
        if (alias == null) {
            throw new NullPointerException("alias == null");
        }

        Date d = getModificationDate(Credentials.USER_PRIVATE_KEY + alias);
        if (d != null) {
            return d;
        }

        d = getModificationDate(Credentials.USER_SECRET_KEY + alias);
        if (d != null) {
            return d;
        }

        d = getModificationDate(Credentials.USER_CERTIFICATE + alias);
        if (d != null) {
            return d;
        }

        return getModificationDate(Credentials.CA_CERTIFICATE + alias);
    }

    @Override
    public void engineSetKeyEntry(String alias, Key key, char[] password, Certificate[] chain)
            throws KeyStoreException {
        if ((password != null) && (password.length > 0)) {
            throw new KeyStoreException("entries cannot be protected with passwords");
        }

        if (key instanceof PrivateKey) {
            setPrivateKeyEntry(alias, (PrivateKey) key, chain, null);
        } else if (key instanceof SecretKey) {
            setSecretKeyEntry(alias, (SecretKey) key, null);
        } else {
            throw new KeyStoreException("Only PrivateKey and SecretKey are supported");
        }
    }

    private static KeyProtection getLegacyKeyProtectionParameter(PrivateKey key)
            throws KeyStoreException {
        String keyAlgorithm = key.getAlgorithm();
        KeyProtection.Builder specBuilder;
        if (KeyProperties.KEY_ALGORITHM_EC.equalsIgnoreCase(keyAlgorithm)) {
            specBuilder =
                    new KeyProtection.Builder(
                            KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY);
            // Authorized to be used with any digest (including no digest).
            specBuilder.setDigests(KeyProperties.DIGEST_NONE);
        } else if (KeyProperties.KEY_ALGORITHM_RSA.equalsIgnoreCase(keyAlgorithm)) {
            specBuilder =
                    new KeyProtection.Builder(
                            KeyProperties.PURPOSE_ENCRYPT
                            | KeyProperties.PURPOSE_DECRYPT
                            | KeyProperties.PURPOSE_SIGN
                            | KeyProperties.PURPOSE_VERIFY);
            // Authorized to be used with any digest (including no digest).
            specBuilder.setDigests(KeyProperties.DIGEST_NONE);
            // Authorized to be used with any encryption and signature padding scheme (including no
            // padding).
            specBuilder.setEncryptionPaddings(
                    KeyProperties.ENCRYPTION_PADDING_NONE);
            // Disable randomized encryption requirement to support encryption padding NONE
            // above.
            specBuilder.setRandomizedEncryptionRequired(false);
        } else {
            throw new KeyStoreException("Unsupported key algorithm: " + keyAlgorithm);
        }
        specBuilder.setUserAuthenticationRequired(false);

        return specBuilder.build();
    }

    private void setPrivateKeyEntry(String alias, PrivateKey key, Certificate[] chain,
            java.security.KeyStore.ProtectionParameter param) throws KeyStoreException {
        int flags = 0;
        KeyProtection spec;
        if (param == null) {
            spec = getLegacyKeyProtectionParameter(key);
        } else if (param instanceof KeyStoreParameter) {
            spec = getLegacyKeyProtectionParameter(key);
            KeyStoreParameter legacySpec = (KeyStoreParameter) param;
            if (legacySpec.isEncryptionRequired()) {
                flags = KeyStore.FLAG_ENCRYPTED;
            }
        } else if (param instanceof KeyProtection) {
            spec = (KeyProtection) param;
        } else {
            throw new KeyStoreException(
                    "Unsupported protection parameter class:" + param.getClass().getName()
                    + ". Supported: " + KeyProtection.class.getName() + ", "
                    + KeyStoreParameter.class.getName());
        }

        // Make sure the chain exists since this is a PrivateKey
        if ((chain == null) || (chain.length == 0)) {
            throw new KeyStoreException("Must supply at least one Certificate with PrivateKey");
        }

        // Do chain type checking.
        X509Certificate[] x509chain = new X509Certificate[chain.length];
        for (int i = 0; i < chain.length; i++) {
            if (!"X.509".equals(chain[i].getType())) {
                throw new KeyStoreException("Certificates must be in X.509 format: invalid cert #"
                        + i);
            }

            if (!(chain[i] instanceof X509Certificate)) {
                throw new KeyStoreException("Certificates must be in X.509 format: invalid cert #"
                        + i);
            }

            x509chain[i] = (X509Certificate) chain[i];
        }

        final byte[] userCertBytes;
        try {
            userCertBytes = x509chain[0].getEncoded();
        } catch (CertificateEncodingException e) {
            throw new KeyStoreException("Failed to encode certificate #0", e);
        }

        /*
         * If we have a chain, store it in the CA certificate slot for this
         * alias as concatenated DER-encoded certificates. These can be
         * deserialized by {@link CertificateFactory#generateCertificates}.
         */
        final byte[] chainBytes;
        if (chain.length > 1) {
            /*
             * The chain is passed in as {user_cert, ca_cert_1, ca_cert_2, ...}
             * so we only need the certificates starting at index 1.
             */
            final byte[][] certsBytes = new byte[x509chain.length - 1][];
            int totalCertLength = 0;
            for (int i = 0; i < certsBytes.length; i++) {
                try {
                    certsBytes[i] = x509chain[i + 1].getEncoded();
                    totalCertLength += certsBytes[i].length;
                } catch (CertificateEncodingException e) {
                    throw new KeyStoreException("Failed to encode certificate #" + i, e);
                }
            }

            /*
             * Serialize this into one byte array so we can later call
             * CertificateFactory#generateCertificates to recover them.
             */
            chainBytes = new byte[totalCertLength];
            int outputOffset = 0;
            for (int i = 0; i < certsBytes.length; i++) {
                final int certLength = certsBytes[i].length;
                System.arraycopy(certsBytes[i], 0, chainBytes, outputOffset, certLength);
                outputOffset += certLength;
                certsBytes[i] = null;
            }
        } else {
            chainBytes = null;
        }

        final String pkeyAlias;
        if (key instanceof AndroidKeyStorePrivateKey) {
            pkeyAlias = ((AndroidKeyStoreKey) key).getAlias();
        } else {
            pkeyAlias = null;
        }

        byte[] pkcs8EncodedPrivateKeyBytes;
        KeymasterArguments importArgs;
        final boolean shouldReplacePrivateKey;
        if (pkeyAlias != null && pkeyAlias.startsWith(Credentials.USER_PRIVATE_KEY)) {
            final String keySubalias = pkeyAlias.substring(Credentials.USER_PRIVATE_KEY.length());
            if (!alias.equals(keySubalias)) {
                throw new KeyStoreException("Can only replace keys with same alias: " + alias
                        + " != " + keySubalias);
            }
            shouldReplacePrivateKey = false;
            importArgs = null;
            pkcs8EncodedPrivateKeyBytes = null;
        } else {
            shouldReplacePrivateKey = true;
            // Make sure the PrivateKey format is the one we support.
            final String keyFormat = key.getFormat();
            if ((keyFormat == null) || (!"PKCS#8".equals(keyFormat))) {
                throw new KeyStoreException(
                        "Unsupported private key export format: " + keyFormat
                        + ". Only private keys which export their key material in PKCS#8 format are"
                        + " supported.");
            }

            // Make sure we can actually encode the key.
            pkcs8EncodedPrivateKeyBytes = key.getEncoded();
            if (pkcs8EncodedPrivateKeyBytes == null) {
                throw new KeyStoreException("Private key did not export any key material");
            }

            importArgs = new KeymasterArguments();
            try {
                importArgs.addInt(KeymasterDefs.KM_TAG_ALGORITHM,
                        KeyProperties.KeyAlgorithm.toKeymasterAsymmetricKeyAlgorithm(
                                key.getAlgorithm()));
                @KeyProperties.PurposeEnum int purposes = spec.getPurposes();
                importArgs.addInts(KeymasterDefs.KM_TAG_PURPOSE,
                        KeyProperties.Purpose.allToKeymaster(purposes));
                if (spec.isDigestsSpecified()) {
                    importArgs.addInts(KeymasterDefs.KM_TAG_DIGEST,
                            KeyProperties.Digest.allToKeymaster(spec.getDigests()));
                }

                importArgs.addInts(KeymasterDefs.KM_TAG_BLOCK_MODE,
                        KeyProperties.BlockMode.allToKeymaster(spec.getBlockModes()));
                int[] keymasterEncryptionPaddings =
                        KeyProperties.EncryptionPadding.allToKeymaster(
                                spec.getEncryptionPaddings());
                if (((purposes & KeyProperties.PURPOSE_ENCRYPT) != 0)
                        && (spec.isRandomizedEncryptionRequired())) {
                    for (int keymasterPadding : keymasterEncryptionPaddings) {
                        if (!KeymasterUtils
                                .isKeymasterPaddingSchemeIndCpaCompatibleWithAsymmetricCrypto(
                                        keymasterPadding)) {
                            throw new KeyStoreException(
                                    "Randomized encryption (IND-CPA) required but is violated by"
                                    + " encryption padding mode: "
                                    + KeyProperties.EncryptionPadding.fromKeymaster(
                                            keymasterPadding)
                                    + ". See KeyProtection documentation.");
                        }
                    }
                }
                importArgs.addInts(KeymasterDefs.KM_TAG_PADDING, keymasterEncryptionPaddings);
                importArgs.addInts(KeymasterDefs.KM_TAG_PADDING,
                        KeyProperties.SignaturePadding.allToKeymaster(spec.getSignaturePaddings()));
                KeymasterUtils.addUserAuthArgs(importArgs,
                        spec.isUserAuthenticationRequired(),
                        spec.getUserAuthenticationValidityDurationSeconds());
                importArgs.addDateIfNotNull(KeymasterDefs.KM_TAG_ACTIVE_DATETIME,
                        spec.getKeyValidityStart());
                importArgs.addDateIfNotNull(KeymasterDefs.KM_TAG_ORIGINATION_EXPIRE_DATETIME,
                        spec.getKeyValidityForOriginationEnd());
                importArgs.addDateIfNotNull(KeymasterDefs.KM_TAG_USAGE_EXPIRE_DATETIME,
                        spec.getKeyValidityForConsumptionEnd());
            } catch (IllegalArgumentException e) {
                throw new KeyStoreException("Invalid parameter", e);
            }
        }


        boolean success = false;
        try {
            // Store the private key, if necessary
            if (shouldReplacePrivateKey) {
                // Delete the stored private key and any related entries before importing the
                // provided key
                Credentials.deleteAllTypesForAlias(mKeyStore, alias);
                KeyCharacteristics resultingKeyCharacteristics = new KeyCharacteristics();
                int errorCode = mKeyStore.importKey(
                        Credentials.USER_PRIVATE_KEY + alias,
                        importArgs,
                        KeymasterDefs.KM_KEY_FORMAT_PKCS8,
                        pkcs8EncodedPrivateKeyBytes,
                        flags,
                        resultingKeyCharacteristics);
                if (errorCode != KeyStore.NO_ERROR) {
                    throw new KeyStoreException("Failed to store private key",
                            KeyStore.getKeyStoreException(errorCode));
                }
            } else {
                // Keep the stored private key around -- delete all other entry types
                Credentials.deleteCertificateTypesForAlias(mKeyStore, alias);
                Credentials.deleteSecretKeyTypeForAlias(mKeyStore, alias);
            }

            // Store the leaf certificate
            int errorCode = mKeyStore.insert(Credentials.USER_CERTIFICATE + alias, userCertBytes,
                    KeyStore.UID_SELF, flags);
            if (errorCode != KeyStore.NO_ERROR) {
                throw new KeyStoreException("Failed to store certificate #0",
                        KeyStore.getKeyStoreException(errorCode));
            }

            // Store the certificate chain
            errorCode = mKeyStore.insert(Credentials.CA_CERTIFICATE + alias, chainBytes,
                    KeyStore.UID_SELF, flags);
            if (errorCode != KeyStore.NO_ERROR) {
                throw new KeyStoreException("Failed to store certificate chain",
                        KeyStore.getKeyStoreException(errorCode));
            }
            success = true;
        } finally {
            if (!success) {
                if (shouldReplacePrivateKey) {
                    Credentials.deleteAllTypesForAlias(mKeyStore, alias);
                } else {
                    Credentials.deleteCertificateTypesForAlias(mKeyStore, alias);
                    Credentials.deleteSecretKeyTypeForAlias(mKeyStore, alias);
                }
            }
        }
    }

    private void setSecretKeyEntry(String entryAlias, SecretKey key,
            java.security.KeyStore.ProtectionParameter param)
            throws KeyStoreException {
        if ((param != null) && (!(param instanceof KeyProtection))) {
            throw new KeyStoreException(
                    "Unsupported protection parameter class: " + param.getClass().getName()
                    + ". Supported: " + KeyProtection.class.getName());
        }
        KeyProtection params = (KeyProtection) param;

        if (key instanceof AndroidKeyStoreSecretKey) {
            // KeyStore-backed secret key. It cannot be duplicated into another entry and cannot
            // overwrite its own entry.
            String keyAliasInKeystore = ((AndroidKeyStoreSecretKey) key).getAlias();
            if (keyAliasInKeystore == null) {
                throw new KeyStoreException("KeyStore-backed secret key does not have an alias");
            }
            if (!keyAliasInKeystore.startsWith(Credentials.USER_SECRET_KEY)) {
                throw new KeyStoreException("KeyStore-backed secret key has invalid alias: "
                        + keyAliasInKeystore);
            }
            String keyEntryAlias =
                    keyAliasInKeystore.substring(Credentials.USER_SECRET_KEY.length());
            if (!entryAlias.equals(keyEntryAlias)) {
                throw new KeyStoreException("Can only replace KeyStore-backed keys with same"
                        + " alias: " + entryAlias + " != " + keyEntryAlias);
            }
            // This is the entry where this key is already stored. No need to do anything.
            if (params != null) {
                throw new KeyStoreException("Modifying KeyStore-backed key using protection"
                        + " parameters not supported");
            }
            return;
        }

        if (params == null) {
            throw new KeyStoreException(
                    "Protection parameters must be specified when importing a symmetric key");
        }

        // Not a KeyStore-backed secret key -- import its key material into keystore.
        String keyExportFormat = key.getFormat();
        if (keyExportFormat == null) {
            throw new KeyStoreException(
                    "Only secret keys that export their key material are supported");
        } else if (!"RAW".equals(keyExportFormat)) {
            throw new KeyStoreException(
                    "Unsupported secret key material export format: " + keyExportFormat);
        }
        byte[] keyMaterial = key.getEncoded();
        if (keyMaterial == null) {
            throw new KeyStoreException("Key did not export its key material despite supporting"
                    + " RAW format export");
        }

        String keyAlgorithmString = key.getAlgorithm();
        int keymasterAlgorithm;
        int keymasterDigest;
        try {
            keymasterAlgorithm =
                    KeyProperties.KeyAlgorithm.toKeymasterSecretKeyAlgorithm(keyAlgorithmString);
            keymasterDigest = KeyProperties.KeyAlgorithm.toKeymasterDigest(keyAlgorithmString);
        } catch (IllegalArgumentException e) {
            throw new KeyStoreException("Unsupported secret key algorithm: " + keyAlgorithmString);
        }

        KeymasterArguments args = new KeymasterArguments();
        args.addInt(KeymasterDefs.KM_TAG_ALGORITHM, keymasterAlgorithm);

        int[] keymasterDigests;
        if (params.isDigestsSpecified()) {
            // Digest(s) specified in parameters
            keymasterDigests = KeyProperties.Digest.allToKeymaster(params.getDigests());
            if (keymasterDigest != -1) {
                // Digest also specified in the JCA key algorithm name.
                if (!com.android.internal.util.ArrayUtils.contains(
                        keymasterDigests, keymasterDigest)) {
                    throw new KeyStoreException("Key digest mismatch"
                            + ". Key: " + keyAlgorithmString
                            + ", parameter spec: " + Arrays.asList(params.getDigests()));
                }
                // When the key is read back from keystore we reconstruct the JCA key algorithm
                // name from the KM_TAG_ALGORITHM and the first KM_TAG_DIGEST. Thus we need to
                // ensure that the digest reflected in the JCA key algorithm name is the first
                // KM_TAG_DIGEST tag.
                if (keymasterDigests[0] != keymasterDigest) {
                    // The first digest is not the one implied by the JCA key algorithm name.
                    // Swap the implied digest with the first one.
                    for (int i = 0; i < keymasterDigests.length; i++) {
                        if (keymasterDigests[i] == keymasterDigest) {
                            keymasterDigests[i] = keymasterDigests[0];
                            keymasterDigests[0] = keymasterDigest;
                            break;
                        }
                    }
                }
            }
        } else {
            // No digest specified in parameters
            if (keymasterDigest != -1) {
                // Digest specified in the JCA key algorithm name.
                keymasterDigests = new int[] {keymasterDigest};
            } else {
                keymasterDigests = EmptyArray.INT;
            }
        }
        args.addInts(KeymasterDefs.KM_TAG_DIGEST, keymasterDigests);
        if (keymasterAlgorithm == KeymasterDefs.KM_ALGORITHM_HMAC) {
            if (keymasterDigests.length == 0) {
                throw new KeyStoreException("At least one digest algorithm must be specified"
                        + " for key algorithm " + keyAlgorithmString);
            }
        }

        @KeyProperties.PurposeEnum int purposes = params.getPurposes();
        int[] keymasterBlockModes =
                KeyProperties.BlockMode.allToKeymaster(params.getBlockModes());
        if (((purposes & KeyProperties.PURPOSE_ENCRYPT) != 0)
                && (params.isRandomizedEncryptionRequired())) {
            for (int keymasterBlockMode : keymasterBlockModes) {
                if (!KeymasterUtils.isKeymasterBlockModeIndCpaCompatibleWithSymmetricCrypto(
                        keymasterBlockMode)) {
                    throw new KeyStoreException(
                            "Randomized encryption (IND-CPA) required but may be violated by block"
                            + " mode: "
                            + KeyProperties.BlockMode.fromKeymaster(keymasterBlockMode)
                            + ". See KeyProtection documentation.");
                }
            }
        }
        args.addInts(KeymasterDefs.KM_TAG_PURPOSE, KeyProperties.Purpose.allToKeymaster(purposes));
        args.addInts(KeymasterDefs.KM_TAG_BLOCK_MODE, keymasterBlockModes);
        if (params.getSignaturePaddings().length > 0) {
            throw new KeyStoreException("Signature paddings not supported for symmetric keys");
        }
        int[] keymasterPaddings = KeyProperties.EncryptionPadding.allToKeymaster(
                params.getEncryptionPaddings());
        args.addInts(KeymasterDefs.KM_TAG_PADDING, keymasterPaddings);
        KeymasterUtils.addUserAuthArgs(args,
                params.isUserAuthenticationRequired(),
                params.getUserAuthenticationValidityDurationSeconds());
        args.addDateIfNotNull(KeymasterDefs.KM_TAG_ACTIVE_DATETIME, params.getKeyValidityStart());
        args.addDateIfNotNull(KeymasterDefs.KM_TAG_ORIGINATION_EXPIRE_DATETIME,
                params.getKeyValidityForOriginationEnd());
        args.addDateIfNotNull(KeymasterDefs.KM_TAG_USAGE_EXPIRE_DATETIME,
                params.getKeyValidityForConsumptionEnd());

        if (((purposes & KeyProperties.PURPOSE_ENCRYPT) != 0)
                && (!params.isRandomizedEncryptionRequired())) {
            // Permit caller-provided IV when encrypting with this key
            args.addBoolean(KeymasterDefs.KM_TAG_CALLER_NONCE);
        }

        Credentials.deleteAllTypesForAlias(mKeyStore, entryAlias);
        String keyAliasInKeystore = Credentials.USER_SECRET_KEY + entryAlias;
        int errorCode = mKeyStore.importKey(
                keyAliasInKeystore,
                args,
                KeymasterDefs.KM_KEY_FORMAT_RAW,
                keyMaterial,
                0, // flags
                new KeyCharacteristics());
        if (errorCode != KeyStore.NO_ERROR) {
            throw new KeyStoreException("Failed to import secret key. Keystore error code: "
                + errorCode);
        }
    }

    @Override
    public void engineSetKeyEntry(String alias, byte[] userKey, Certificate[] chain)
            throws KeyStoreException {
        throw new KeyStoreException("Operation not supported because key encoding is unknown");
    }

    @Override
    public void engineSetCertificateEntry(String alias, Certificate cert) throws KeyStoreException {
        if (isKeyEntry(alias)) {
            throw new KeyStoreException("Entry exists and is not a trusted certificate");
        }

        // We can't set something to null.
        if (cert == null) {
            throw new NullPointerException("cert == null");
        }

        final byte[] encoded;
        try {
            encoded = cert.getEncoded();
        } catch (CertificateEncodingException e) {
            throw new KeyStoreException(e);
        }

        if (!mKeyStore.put(Credentials.CA_CERTIFICATE + alias, encoded,
                KeyStore.UID_SELF, KeyStore.FLAG_NONE)) {
            throw new KeyStoreException("Couldn't insert certificate; is KeyStore initialized?");
        }
    }

    @Override
    public void engineDeleteEntry(String alias) throws KeyStoreException {
        if (!engineContainsAlias(alias)) {
            return;
        }
        // At least one entry corresponding to this alias exists in keystore

        if (!Credentials.deleteAllTypesForAlias(mKeyStore, alias)) {
            throw new KeyStoreException("Failed to delete entry: " + alias);
        }
    }

    private Set<String> getUniqueAliases() {
        final String[] rawAliases = mKeyStore.list("");
        if (rawAliases == null) {
            return new HashSet<String>();
        }

        final Set<String> aliases = new HashSet<String>(rawAliases.length);
        for (String alias : rawAliases) {
            final int idx = alias.indexOf('_');
            if ((idx == -1) || (alias.length() <= idx)) {
                Log.e(NAME, "invalid alias: " + alias);
                continue;
            }

            aliases.add(new String(alias.substring(idx + 1)));
        }

        return aliases;
    }

    @Override
    public Enumeration<String> engineAliases() {
        return Collections.enumeration(getUniqueAliases());
    }

    @Override
    public boolean engineContainsAlias(String alias) {
        if (alias == null) {
            throw new NullPointerException("alias == null");
        }

        return mKeyStore.contains(Credentials.USER_PRIVATE_KEY + alias)
                || mKeyStore.contains(Credentials.USER_SECRET_KEY + alias)
                || mKeyStore.contains(Credentials.USER_CERTIFICATE + alias)
                || mKeyStore.contains(Credentials.CA_CERTIFICATE + alias);
    }

    @Override
    public int engineSize() {
        return getUniqueAliases().size();
    }

    @Override
    public boolean engineIsKeyEntry(String alias) {
        return isKeyEntry(alias);
    }

    private boolean isKeyEntry(String alias) {
        return isPrivateKeyEntry(alias) || isSecretKeyEntry(alias);
    }

    private boolean isPrivateKeyEntry(String alias) {
        if (alias == null) {
            throw new NullPointerException("alias == null");
        }

        return mKeyStore.contains(Credentials.USER_PRIVATE_KEY + alias);
    }

    private boolean isSecretKeyEntry(String alias) {
        if (alias == null) {
            throw new NullPointerException("alias == null");
        }

        return mKeyStore.contains(Credentials.USER_SECRET_KEY + alias);
    }

    private boolean isCertificateEntry(String alias) {
        if (alias == null) {
            throw new NullPointerException("alias == null");
        }

        return mKeyStore.contains(Credentials.CA_CERTIFICATE + alias);
    }

    @Override
    public boolean engineIsCertificateEntry(String alias) {
        return !isKeyEntry(alias) && isCertificateEntry(alias);
    }

    @Override
    public String engineGetCertificateAlias(Certificate cert) {
        if (cert == null) {
            return null;
        }
        if (!"X.509".equalsIgnoreCase(cert.getType())) {
            // Only X.509 certificates supported
            return null;
        }
        byte[] targetCertBytes;
        try {
            targetCertBytes = cert.getEncoded();
        } catch (CertificateEncodingException e) {
            return null;
        }
        if (targetCertBytes == null) {
            return null;
        }

        final Set<String> nonCaEntries = new HashSet<String>();

        /*
         * First scan the PrivateKeyEntry types. The KeyStoreSpi documentation
         * says to only compare the first certificate in the chain which is
         * equivalent to the USER_CERTIFICATE prefix for the Android keystore
         * convention.
         */
        final String[] certAliases = mKeyStore.list(Credentials.USER_CERTIFICATE);
        if (certAliases != null) {
            for (String alias : certAliases) {
                final byte[] certBytes = mKeyStore.get(Credentials.USER_CERTIFICATE + alias);
                if (certBytes == null) {
                    continue;
                }

                nonCaEntries.add(alias);

                if (Arrays.equals(certBytes, targetCertBytes)) {
                    return alias;
                }
            }
        }

        /*
         * Look at all the TrustedCertificateEntry types. Skip all the
         * PrivateKeyEntry we looked at above.
         */
        final String[] caAliases = mKeyStore.list(Credentials.CA_CERTIFICATE);
        if (certAliases != null) {
            for (String alias : caAliases) {
                if (nonCaEntries.contains(alias)) {
                    continue;
                }

                final byte[] certBytes = mKeyStore.get(Credentials.CA_CERTIFICATE + alias);
                if (certBytes == null) {
                    continue;
                }

                if (Arrays.equals(certBytes, targetCertBytes)) {
                    return alias;
                }
            }
        }

        return null;
    }

    @Override
    public void engineStore(OutputStream stream, char[] password) throws IOException,
            NoSuchAlgorithmException, CertificateException {
        throw new UnsupportedOperationException("Can not serialize AndroidKeyStore to OutputStream");
    }

    @Override
    public void engineLoad(InputStream stream, char[] password) throws IOException,
            NoSuchAlgorithmException, CertificateException {
        if (stream != null) {
            throw new IllegalArgumentException("InputStream not supported");
        }

        if (password != null) {
            throw new IllegalArgumentException("password not supported");
        }

        // Unfortunate name collision.
        mKeyStore = KeyStore.getInstance();
    }

    @Override
    public void engineSetEntry(String alias, Entry entry, ProtectionParameter param)
            throws KeyStoreException {
        if (entry == null) {
            throw new KeyStoreException("entry == null");
        }

        Credentials.deleteAllTypesForAlias(mKeyStore, alias);

        if (entry instanceof java.security.KeyStore.TrustedCertificateEntry) {
            java.security.KeyStore.TrustedCertificateEntry trE =
                    (java.security.KeyStore.TrustedCertificateEntry) entry;
            engineSetCertificateEntry(alias, trE.getTrustedCertificate());
            return;
        }

        if (entry instanceof PrivateKeyEntry) {
            PrivateKeyEntry prE = (PrivateKeyEntry) entry;
            setPrivateKeyEntry(alias, prE.getPrivateKey(), prE.getCertificateChain(), param);
        } else if (entry instanceof SecretKeyEntry) {
            SecretKeyEntry secE = (SecretKeyEntry) entry;
            setSecretKeyEntry(alias, secE.getSecretKey(), param);
        } else {
            throw new KeyStoreException(
                    "Entry must be a PrivateKeyEntry, SecretKeyEntry or TrustedCertificateEntry"
                    + "; was " + entry);
        }
    }

    /**
     * {@link X509Certificate} which returns {@link AndroidKeyStorePublicKey} from
     * {@link #getPublicKey()}. This is so that crypto operations on these public keys contain
     * can find out which keystore private key entry to use. This is needed so that Android Keystore
     * crypto operations using public keys can find out which key alias to use. These operations
     * require an alias.
     */
    static class KeyStoreX509Certificate extends DelegatingX509Certificate {
        private final String mPrivateKeyAlias;
        KeyStoreX509Certificate(String privateKeyAlias, X509Certificate delegate) {
            super(delegate);
            mPrivateKeyAlias = privateKeyAlias;
        }

        @Override
        public PublicKey getPublicKey() {
            PublicKey original = super.getPublicKey();
            return AndroidKeyStoreProvider.getAndroidKeyStorePublicKey(
                    mPrivateKeyAlias, original.getAlgorithm(), original.getEncoded());
        }
    }
}
