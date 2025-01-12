package app.attestation.auditor;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.KeyguardManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.os.Build;
import android.os.UserManager;
import android.provider.Settings;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyInfo;
import android.security.keystore.KeyProperties;
import android.util.Log;
import android.view.accessibility.AccessibilityManager;

import androidx.biometric.BiometricManager;
import androidx.preference.PreferenceManager;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import com.google.common.io.BaseEncoding;
import com.google.common.io.ByteStreams;
import com.google.common.primitives.Bytes;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PrivateKey;
import java.security.ProviderException;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.SignatureException;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;

import javax.security.auth.x500.X500Principal;

import app.attestation.auditor.attestation.Attestation;
import app.attestation.auditor.attestation.AttestationApplicationId;
import app.attestation.auditor.attestation.AttestationPackageInfo;
import app.attestation.auditor.attestation.AuthorizationList;
import app.attestation.auditor.attestation.RootOfTrust;

import static android.security.keystore.KeyProperties.DIGEST_SHA256;

import static androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK;
import static androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS;

class AttestationProtocol {
    private static final String TAG = "AttestationProtocol";

    // Developer previews set osVersion to 0 as a placeholder value.
    private static final int DEVELOPER_PREVIEW_OS_VERSION = 0;

    // Settings.Global.ADD_USERS_WHEN_LOCKED is a private API
    private static final String ADD_USERS_WHEN_LOCKED = "add_users_when_locked";

    private static final int CLOCK_SKEW_MS = 5 * 60 * 1000;
    private static final int EXPIRE_OFFSET_MS = 5 * 60 * 1000 + CLOCK_SKEW_MS;

    private static final String KEYSTORE_ALIAS_FRESH = "fresh_attestation_key";
    private static final String KEYSTORE_ALIAS_PERSISTENT_PREFIX = "persistent_attestation_key_";
    private static final String KEYSTORE_ALIAS_ATTEST_PREFIX = "attest_key_";

    // Global preferences
    private static final String KEY_CHALLENGE_INDEX = "challenge_index";

    // Per-Auditee preferences
    private static final String PREFERENCES_DEVICE_PREFIX = "device-";
    private static final String KEY_PINNED_CERTIFICATE = "pinned_certificate_";
    private static final String KEY_PINNED_CERTIFICATE_LENGTH = "pinned_certificate_length";
    private static final String KEY_PINNED_VERIFIED_BOOT_KEY = "pinned_verified_boot_key";
    private static final String KEY_PINNED_OS_VERSION = "pinned_os_version";
    private static final String KEY_PINNED_OS_PATCH_LEVEL = "pinned_os_patch_level";
    private static final String KEY_PINNED_VENDOR_PATCH_LEVEL = "pinned_vendor_patch_level";
    private static final String KEY_PINNED_BOOT_PATCH_LEVEL = "pinned_boot_patch_level";
    private static final String KEY_PINNED_APP_VERSION = "pinned_app_version";
    private static final String KEY_PINNED_APP_VARIANT = "pinned_app_variant";
    private static final String KEY_PINNED_SECURITY_LEVEL = "pinned_security_level";
    private static final String KEY_VERIFIED_TIME_FIRST = "verified_time_first";
    private static final String KEY_VERIFIED_TIME_LAST = "verified_time_last";

    private static final int CHALLENGE_LENGTH = 32;
    static final String EC_CURVE = "secp256r1";
    private static final String SIGNATURE_ALGORITHM = "SHA256WithECDSA";
    static final String KEY_DIGEST = DIGEST_SHA256;
    private static final HashFunction FINGERPRINT_HASH_FUNCTION = Hashing.sha256();
    private static final int FINGERPRINT_LENGTH = FINGERPRINT_HASH_FUNCTION.bits() / 8;

    private static final boolean PREFER_STRONGBOX = true;
    private static final boolean USE_ATTEST_KEY = true;
    private static final boolean ALLOW_ATTEST_KEY_DOWNGRADE = true;

    // Challenge message:
    //
    // byte maxVersion = PROTOCOL_VERSION
    // byte[] challenge index (length: CHALLENGE_LENGTH)
    // byte[] challenge (length: CHALLENGE_LENGTH)
    //
    // The challenge index is randomly generated by Auditor and used for all future
    // challenge
    // messages from that Auditor. It's used on the Auditee as an index to choose
    // the correct
    // persistent key to satisfy the Auditor, rather than only supporting pairing
    // with one. In
    // theory, the Auditor could authenticate to the Auditee, but this app already
    // provides a
    // better way to do that by doing the same process in reverse for a supported
    // device.
    //
    // The challenge is randomly generated by the Auditor and serves the security
    // function of
    // enforcing that the results are fresh. It's returned inside the attestation
    // certificate
    // which has a signature from the device's provisioned key (not usable by the
    // OS) and the
    // outer signature from the hardware-backed key generated for the initial
    // pairing.
    //
    // Attestation message:
    //
    // For backwards compatibility the Auditor device sends its maximum supported
    // version, and
    // the Auditee uses the highest version it supports.
    //
    // Compression is done with raw DEFLATE (no zlib wrapper) with a preset
    // dictionary generated from
    // sample certificates.
    //
    // signed message {
    // byte version = min(maxVersion, PROTOCOL_VERSION)
    // short compressedChainLength
    // byte[] compressedChain { [short encodedCertificateLength, byte[]
    // encodedCertificate] }
    // byte[] fingerprint (length: FINGERPRINT_LENGTH)
    // int osEnforcedFlags
    // }
    // byte[] signature (rest of message)
    //
    // Protocol version changes:
    //
    // n/a
    //
    // For each audit, the Auditee generates a fresh hardware-backed key with key
    // attestation
    // using the provided challenge. It reports back the certificate chain to be
    // verified by the
    // Auditor. The public key certificate of the generated key is signed by a key
    // provisioned on
    // the device (not usable by the OS) chaining up to an intermediate and the
    // Google root. The
    // certificate contains the key attestation metadata including the important
    // fields with the
    // lock state, verified boot state, the verified boot public key fingerprint and
    // the OS
    // version / patch level:
    //
    // https://developer.android.com/training/articles/security-key-attestation.html#certificate_schema
    //
    // The Auditee keeps the first hardware-backed key generated for a challenge
    // index and uses it
    // to sign all future attestations. The fingerprint of the persistent key is
    // included in the
    // attestation message for the Auditor to find the corresponding pinning data.
    // Other keys are
    // never actually used, only generated for fresh key attestation data.
    //
    // The OS can use the persistent generated hardware-backed key for signing but
    // cannot obtain
    // the private key. The key isn't be usable if verified boot fails or the OS is
    // downgraded and
    // the keys are protected against replay attacks via the Replay Protected Memory
    // Block.
    // Devices launching with Android P or later can provide a StrongBox Keymaster
    // to support
    // storing the keys in a dedicated hardware security module to substantially
    // reduce the attack
    // surface for obtaining the keys. StrongBox is paired with the TEE and the TEE
    // corroborates
    // the validity of the keys and attestation. The Pixel 3 and 3 XL are the first
    // devices with a
    // StrongBox implementation via the Titan M security chip.
    //
    // https://android-developers.googleblog.com/2018/10/building-titan-better-security-through.html
    //
    // The attestation message also includes osEnforcedFlags with data obtained at
    // the OS level,
    // which is vulnerable to tampering by an attacker with control over the OS.
    // However, the OS
    // did get verified by verified boot so without a verified boot bypass they
    // would need to keep
    // exploiting it after booting. The bootloader / TEE verified OS version / OS
    // patch level are
    // a useful mitigation as they reveal that the OS isn't upgraded even if an
    // attacker has root.
    //
    // The Auditor saves the initial certificate chain, using the initial
    // certificate to verify
    // the outer signature and the rest of the chain for pinning the expected chain.
    // It enforces
    // downgrade protection for the OS version/patch (bootloader/TEE enforced) and
    // app version (OS
    // enforced) by keeping them updated.
    private static final byte PROTOCOL_VERSION = 4;
    private static final byte PROTOCOL_VERSION_MINIMUM = 4;
    // can become longer in the future, but this is the minimum length
    static final byte CHALLENGE_MESSAGE_LENGTH = 1 + CHALLENGE_LENGTH * 2;
    private static final int MAX_ENCODED_CHAIN_LENGTH = 5000;
    private static final int MAX_MESSAGE_SIZE = 2953;

    private static final int OS_ENFORCED_FLAGS_NONE = 0;
    private static final int OS_ENFORCED_FLAGS_USER_PROFILE_SECURE = 1;
    private static final int OS_ENFORCED_FLAGS_ACCESSIBILITY = 1 << 1;
    private static final int OS_ENFORCED_FLAGS_DEVICE_ADMIN = 1 << 2;
    private static final int OS_ENFORCED_FLAGS_ADB_ENABLED = 1 << 3;
    private static final int OS_ENFORCED_FLAGS_ADD_USERS_WHEN_LOCKED = 1 << 4;
    private static final int OS_ENFORCED_FLAGS_ENROLLED_BIOMETRICS = 1 << 5;
    private static final int OS_ENFORCED_FLAGS_DENY_NEW_USB = 1 << 6;
    private static final int OS_ENFORCED_FLAGS_DEVICE_ADMIN_NON_SYSTEM = 1 << 7;
    private static final int OS_ENFORCED_FLAGS_OEM_UNLOCK_ALLOWED = 1 << 8;
    private static final int OS_ENFORCED_FLAGS_SYSTEM_USER = 1 << 9;
    private static final int OS_ENFORCED_FLAGS_ALL = OS_ENFORCED_FLAGS_USER_PROFILE_SECURE |
            OS_ENFORCED_FLAGS_ACCESSIBILITY |
            OS_ENFORCED_FLAGS_DEVICE_ADMIN |
            OS_ENFORCED_FLAGS_ADB_ENABLED |
            OS_ENFORCED_FLAGS_ADD_USERS_WHEN_LOCKED |
            OS_ENFORCED_FLAGS_ENROLLED_BIOMETRICS |
            OS_ENFORCED_FLAGS_DENY_NEW_USB |
            OS_ENFORCED_FLAGS_DEVICE_ADMIN_NON_SYSTEM |
            OS_ENFORCED_FLAGS_OEM_UNLOCK_ALLOWED |
            OS_ENFORCED_FLAGS_SYSTEM_USER;

    private static final String AUDITOR_APP_PACKAGE_NAME_RELEASE = "app.attestation.auditor";
    private static final String AUDITOR_APP_PACKAGE_NAME_PLAY = "app.attestation.auditor.play";
    private static final String AUDITOR_APP_PACKAGE_NAME_DEBUG = "app.attestation.auditor.debug";
    private static final String AUDITOR_APP_SIGNATURE_DIGEST_RELEASE = "990E04F0864B19F14F84E0E432F7A393F297AB105A22C1E1B10B442A4A62C42C";
    private static final String AUDITOR_APP_SIGNATURE_DIGEST_PLAY = "075335BD7B54C965222B5284D2A1FDEF1198AE45EC7B09A4934287A0E3A243C7";
    private static final String AUDITOR_APP_SIGNATURE_DIGEST_DEBUG = "17727D8B61D55A864936B1A7B4A2554A15151F32EBCF44CDAA6E6C3258231890";
    private static final byte AUDITOR_APP_VARIANT_RELEASE = 0;
    private static final byte AUDITOR_APP_VARIANT_PLAY = 1;
    private static final byte AUDITOR_APP_VARIANT_DEBUG = 2;
    private static final int AUDITOR_APP_MINIMUM_VERSION = 47;
    private static final int OS_VERSION_MINIMUM = 100000;
    private static final int OS_PATCH_LEVEL_MINIMUM = 201909;
    private static final int VENDOR_PATCH_LEVEL_MINIMUM = 20190905;
    private static final int BOOT_PATCH_LEVEL_MINIMUM = 20190905;

    // Split displayed fingerprint into groups of 4 characters
    private static final int FINGERPRINT_SPLIT_INTERVAL = 4;

    static class DeviceInfoParser implements XmlMapElemParser<String, DeviceInfo> {

        private static ImmutableMap<String, DeviceInfo> getDeviceMap(Context context, int resMap)
                throws IOException, XmlPullParserException {
            return ImmutableMapParser.getImmutableMapResource(context, resMap, "fingerprint", "deviceInfo",
                    new DeviceInfoParser());
        }

        @Override
        public String parseKey(Context context, XmlResourceParser parser) {
            if (parser.getName().equals("fingerprint")) {
                try {
                    int eventType = parser.getEventType();
                    while (eventType != XmlPullParser.TEXT) {
                        eventType = parser.next();
                    }

                    var key = parser.getText();
                    Log.d("AttestationProtocol", "key: " + key);
                    return key;
                } catch (IOException | XmlPullParserException e) {
                    throw new IllegalArgumentException("Invalid data in xml resource map: ", e);
                }
            } else {
                throw new IllegalStateException("Trying to parse a key from an invalid node: " + parser.getName());
            }
        }

        @Override
        public DeviceInfo parseValue(Context context, XmlResourceParser parser) {
            int name = 0, osName = 0, attestationVersion = 0, keymasterVersion = 0;
            boolean rollbackResistant = false, perUserEncryption = false, enforceStrongBox = false;

            if (parser.getName().equals("deviceInfo")) {
                try {
                    int eventType;
                    String tagName = "deviceInfo";

                    do {
                        eventType = parser.next();
                        if (eventType == XmlPullParser.TEXT) {
                            Log.d("AttestationProtocol", "found tag text: " + parser.getText());
                            switch (tagName) {
                                case "name":
                                    name = context.getResources().getIdentifier(parser.getText(), "string",
                                            context.getPackageName());
                                    break;
                                case "attestationVersion":
                                    attestationVersion = Integer.parseInt(parser.getText());
                                    break;
                                case "keymasterVersion":
                                    keymasterVersion = Integer.parseInt(parser.getText());
                                    break;
                                case "rollbackResistant":
                                    rollbackResistant = Boolean.parseBoolean(parser.getText());
                                    break;
                                case "perUserEncryption":
                                    perUserEncryption = Boolean.parseBoolean(parser.getText());
                                    break;
                                case "enforceStrongBox":
                                    enforceStrongBox = Boolean.parseBoolean(parser.getText());
                                    break;
                                case "osName":
                                    Log.d("AttestationProtocol", "osName: " + parser.getText());
                                    osName = context.getResources().getIdentifier(parser.getText(), "string",
                                            context.getPackageName());
                                    break;
                            }
                        } else if (eventType == XmlPullParser.START_TAG || eventType == XmlPullParser.END_TAG) {
                            tagName = parser.getName();
                        }
                    } while (eventType != XmlPullParser.END_TAG || !tagName.equals("deviceInfo"));
                    Log.d("AttestationProtocol", "finished parsing DeviceInfo");
                } catch (IOException | XmlPullParserException e) {
                    throw new IllegalArgumentException("Invalid data in xml resource map: ", e);
                }
            } else {
                throw new IllegalStateException("Trying to parse DeviceInfo from an invalid node: " + parser.getName());
            }

            var deviceInfo = new DeviceInfo(name, attestationVersion, keymasterVersion, rollbackResistant,
                    perUserEncryption,
                    enforceStrongBox, osName);
            Log.d("AttestationProtocol",
                    "DeviceInfo { name: " + deviceInfo.name + ", attestationVersion: " + attestationVersion
                            + ", keymasterVersion: " + keymasterVersion + ", rollbackResistant: " + rollbackResistant
                            + ", perUserEncryption: " + perUserEncryption + ", enforceStrongBox: " + enforceStrongBox
                            + ", osName: " + osName + " }");
            return deviceInfo;
        }

    }

    static class DeviceInfo {
        final int name;
        final int attestationVersion;
        final int keymasterVersion;
        final boolean rollbackResistant;
        final boolean perUserEncryption;
        // enforce using StrongBox for new pairings
        final boolean enforceStrongBox;
        final int osName;

        DeviceInfo(final int name, final int attestationVersion, final int keymasterVersion,
                final boolean rollbackResistant, final boolean perUserEncryption,
                final boolean enforceStrongBox, final int osName) {
            this.name = name;
            this.attestationVersion = attestationVersion;
            this.keymasterVersion = keymasterVersion;
            this.rollbackResistant = rollbackResistant;
            this.perUserEncryption = perUserEncryption;
            this.enforceStrongBox = enforceStrongBox;
            this.osName = osName;
        }
    }

    private static final boolean isStrongBoxSupported = ImmutableSet.of(
            "Pixel 3",
            "Pixel 3 XL",
            "Pixel 3a",
            "Pixel 3a XL",
            "Pixel 4",
            "Pixel 4 XL",
            "Pixel 4a",
            "Pixel 4a (5G)",
            "Pixel 5",
            "Pixel 5a",
            "Pixel 6",
            "Pixel 6 Pro",
            "Pixel 6a",
            "Pixel 7",
            "Pixel 7 Pro",
            "SM-N970U",
            "SM-N975U").contains(Build.MODEL);

    // Pixel 6, Pixel 6 Pro and Pixel 6a forgot to declare the attest key feature
    // when it shipped in Android 12
    private static final boolean alwaysHasAttestKey = ImmutableSet.of(
            "Pixel 6",
            "Pixel 6 Pro",
            "Pixel 6a").contains(Build.MODEL);

    private static final ImmutableSet<Integer> extraPatchLevelMissing = ImmutableSet.of(
            R.string.device_sm_a705fn,
            R.string.device_sm_g970f,
            R.string.device_sm_g975f,
            R.string.device_sm_n970f,
            R.string.device_sm_n970u,
            R.string.device_sm_n975u,
            R.string.device_sm_t510);

    private static byte[] getChallengeIndex(final Context context) {
        final SharedPreferences global = PreferenceManager.getDefaultSharedPreferences(context);
        final String challengeIndexSerialized = global.getString(KEY_CHALLENGE_INDEX, null);
        if (challengeIndexSerialized != null) {
            return BaseEncoding.base64().decode(challengeIndexSerialized);
        } else {
            final byte[] challengeIndex = getChallenge();
            global.edit()
                    .putString(KEY_CHALLENGE_INDEX, BaseEncoding.base64().encode(challengeIndex))
                    .apply();
            return challengeIndex;
        }
    }

    private static byte[] getChallenge() {
        final SecureRandom random = new SecureRandom();
        final byte[] challenge = new byte[CHALLENGE_LENGTH];
        random.nextBytes(challenge);
        return challenge;
    }

    static byte[] getChallengeMessage(final Context context) {
        return Bytes.concat(new byte[] { PROTOCOL_VERSION }, getChallengeIndex(context), getChallenge());
    }

    private static byte[] getFingerprint(final Certificate certificate)
            throws CertificateEncodingException {
        return FINGERPRINT_HASH_FUNCTION.hashBytes(certificate.getEncoded()).asBytes();
    }

    private static class Verified {
        final int device;
        final String verifiedBootKey;
        final byte[] verifiedBootHash;
        final int osName;
        final int osVersion;
        final int osPatchLevel;
        final int vendorPatchLevel;
        final int bootPatchLevel;
        final int appVersion;
        final byte appVariant;
        final int securityLevel;
        final boolean attestKey;
        final boolean perUserEncryption;
        final boolean enforceStrongBox;

        Verified(final int device, final String verifiedBootKey, final byte[] verifiedBootHash,
                final int osName, final int osVersion, final int osPatchLevel,
                final int vendorPatchLevel, final int bootPatchLevel, final int appVersion, final byte appVariant,
                final int securityLevel, final boolean attestKey, final boolean perUserEncryption,
                final boolean enforceStrongBox) {
            this.device = device;
            this.verifiedBootKey = verifiedBootKey;
            this.verifiedBootHash = verifiedBootHash;
            this.osName = osName;
            this.osVersion = osVersion;
            this.osPatchLevel = osPatchLevel;
            this.vendorPatchLevel = vendorPatchLevel;
            this.bootPatchLevel = bootPatchLevel;
            this.appVersion = appVersion;
            this.appVariant = appVariant;
            this.securityLevel = securityLevel;
            this.attestKey = attestKey;
            this.perUserEncryption = perUserEncryption;
            this.enforceStrongBox = enforceStrongBox;
        }
    }

    private static X509Certificate generateCertificate(final InputStream in)
            throws CertificateException {
        return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
    }

    private static X509Certificate generateCertificate(final Resources resources, final int id)
            throws CertificateException, IOException {
        try (final InputStream stream = resources.openRawResource(id)) {
            return generateCertificate(stream);
        }
    }

    private static Verified verifyStateless(Context context,
            final Certificate[] certificates,
            final byte[] challenge, final boolean hasPersistentKey, final Certificate root0,
            final Certificate root1, final Certificate root2) throws GeneralSecurityException {

        ImmutableMap<String, DeviceInfo> fingerprintsGraphene, fingerprintsGrapheneStrongBox, fingerprintsStock,
                fingerprintsStockStrongBox, fingerprintsCustomOs, fingerprintsCustomOsStrongBox;
        try {
            fingerprintsGraphene = DeviceInfoParser.getDeviceMap(context,
                    R.xml.fingerprints_graphene);
        } catch (IOException | XmlPullParserException e) {
            throw new GeneralSecurityException("failed to parse GrapheneOS fingerprints");
        }

        try {
            fingerprintsGrapheneStrongBox = DeviceInfoParser.getDeviceMap(context,
                    R.xml.fingerprints_graphene_strongbox);
        } catch (IOException | XmlPullParserException e) {
            throw new GeneralSecurityException("failed to parse GrapheneOS StrongBox fingerprints");
        }

        try {
            fingerprintsStock = DeviceInfoParser.getDeviceMap(context,
                    R.xml.fingerprints_stock);
        } catch (IOException | XmlPullParserException e) {
            throw new GeneralSecurityException("failed to parse Stock fingerprints");
        }

        try {
            fingerprintsStockStrongBox = DeviceInfoParser.getDeviceMap(context,
                    R.xml.fingerprints_stock_strongbox);
        } catch (IOException | XmlPullParserException e) {
            throw new GeneralSecurityException("failed to parse Stock StrongBox fingerprints");
        }

        Resources res = context.getResources();

        int custom_os_fingerprints_res = res.getIdentifier("os_custom_fingerprints_res", "string",
                context.getPackageName());
        if (custom_os_fingerprints_res == 0) {
            fingerprintsCustomOs = ImmutableMap.<String, DeviceInfo>builder().build();
        } else {
            try {
                fingerprintsCustomOs = DeviceInfoParser.getDeviceMap(context, custom_os_fingerprints_res);
            } catch (IOException | XmlPullParserException e) {
                Log.e("AttestationProtocol", "failed to parse provided fingerprints for custom OS", e);
                fingerprintsCustomOs = ImmutableMap.<String, DeviceInfo>builder().build();
            }
        }

        int custom_os_fingerprints_strongbox_res = res.getIdentifier("os_custom_fingerprints_strongbox_res", "string",
                context.getPackageName());
        if (custom_os_fingerprints_res == 0) {
            fingerprintsCustomOsStrongBox = ImmutableMap.<String, DeviceInfo>builder().build();
        } else {
            try {
                fingerprintsCustomOsStrongBox = DeviceInfoParser.getDeviceMap(context,
                        custom_os_fingerprints_strongbox_res);
            } catch (IOException | XmlPullParserException e) {
                Log.e("AttestationProtocol", "failed to parse provided StrongBox fingerprints for custom OS", e);
                fingerprintsCustomOsStrongBox = ImmutableMap.<String, DeviceInfo>builder().build();
            }
        }

        verifyCertificateSignatures(certificates, hasPersistentKey);

        // check that the root certificate is a valid key attestation root
        if (!Arrays.equals(root0.getEncoded(), certificates[certificates.length - 1].getEncoded()) &&
                !Arrays.equals(root1.getEncoded(), certificates[certificates.length - 1].getEncoded()) &&
                !Arrays.equals(root2.getEncoded(), certificates[certificates.length - 1].getEncoded())) {
            throw new GeneralSecurityException("root certificate is not a valid key attestation root");
        }

        final Attestation attestation = new Attestation((X509Certificate) certificates[0]);

        final int attestationSecurityLevel = attestation.getAttestationSecurityLevel();

        // enforce hardware-based attestation
        if (attestationSecurityLevel != Attestation.KM_SECURITY_LEVEL_TRUSTED_ENVIRONMENT &&
                attestationSecurityLevel != Attestation.KM_SECURITY_LEVEL_STRONG_BOX) {
            throw new GeneralSecurityException("attestation security level is not valid");
        }
        if (attestation.getKeymasterSecurityLevel() != attestationSecurityLevel) {
            throw new GeneralSecurityException("keymaster security level does not match attestation security level");
        }

        // prevent replay attacks
        if (!Arrays.equals(attestation.getAttestationChallenge(), challenge)) {
            throw new GeneralSecurityException("challenge mismatch");
        }

        // enforce communicating with the Auditor app via OS level security
        final AuthorizationList softwareEnforced = attestation.getSoftwareEnforced();
        final AttestationApplicationId attestationApplicationId = softwareEnforced.getAttestationApplicationId();
        final List<AttestationPackageInfo> infos = attestationApplicationId.getAttestationPackageInfos();
        if (infos.size() != 1) {
            throw new GeneralSecurityException("invalid number of attestation packages");
        }
        final AttestationPackageInfo info = infos.get(0);
        final List<byte[]> signatureDigests = attestationApplicationId.getSignatureDigests();
        if (signatureDigests.size() != 1) {
            throw new GeneralSecurityException("invalid number of Auditor app signatures");
        }
        final String signatureDigest = BaseEncoding.base16().encode(signatureDigests.get(0));
        final byte appVariant;
        if (AUDITOR_APP_PACKAGE_NAME_RELEASE.equals(info.getPackageName())) {
            if (!AUDITOR_APP_SIGNATURE_DIGEST_RELEASE.equals(signatureDigest)) {
                throw new GeneralSecurityException("invalid Auditor app signing key");
            }
            appVariant = AUDITOR_APP_VARIANT_RELEASE;
        } else if (AUDITOR_APP_PACKAGE_NAME_PLAY.equals(info.getPackageName())) {
            if (!AUDITOR_APP_SIGNATURE_DIGEST_PLAY.equals(signatureDigest)) {
                throw new GeneralSecurityException("invalid Auditor app signing key");
            }
            appVariant = AUDITOR_APP_VARIANT_PLAY;
        } else if (AUDITOR_APP_PACKAGE_NAME_DEBUG.equals(info.getPackageName())) {
            if (!BuildConfig.DEBUG) {
                throw new GeneralSecurityException(
                        "Auditor debug builds are only trusted by other Auditor debug builds");
            }
            if (!AUDITOR_APP_SIGNATURE_DIGEST_DEBUG.equals(signatureDigest)) {
                throw new GeneralSecurityException("invalid Auditor app signing key");
            }
            appVariant = AUDITOR_APP_VARIANT_DEBUG;
        } else {
            throw new GeneralSecurityException("invalid Auditor app package name: " + info.getPackageName());
        }
        final int appVersion = Math.toIntExact(info.getVersion()); // int for compatibility
        if (appVersion < AUDITOR_APP_MINIMUM_VERSION) {
            throw new GeneralSecurityException("Auditor app is too old: " + appVersion);
        }

        final AuthorizationList teeEnforced = attestation.getTeeEnforced();

        // verified boot security checks
        final RootOfTrust rootOfTrust = teeEnforced.getRootOfTrust();
        if (rootOfTrust == null) {
            throw new GeneralSecurityException("missing root of trust");
        }
        if (!rootOfTrust.isDeviceLocked()) {
            throw new GeneralSecurityException("device is not locked");
        }
        final int verifiedBootState = rootOfTrust.getVerifiedBootState();
        final String verifiedBootKey = BaseEncoding.base16().encode(rootOfTrust.getVerifiedBootKey());
        final DeviceInfo device;
        if (verifiedBootState == RootOfTrust.KM_VERIFIED_BOOT_SELF_SIGNED) {
            if (attestationSecurityLevel == Attestation.KM_SECURITY_LEVEL_STRONG_BOX) {
                if (fingerprintsGrapheneStrongBox.containsKey(verifiedBootKey)) {
                    device = fingerprintsGrapheneStrongBox.get(verifiedBootKey);
                } else {
                    device = fingerprintsCustomOsStrongBox.get(verifiedBootKey);
                }
            } else {
                if (fingerprintsGraphene.containsKey(verifiedBootKey)) {
                    device = fingerprintsGraphene.get(verifiedBootKey);
                } else {
                    device = fingerprintsCustomOs.get(verifiedBootKey);
                }
            }
        } else if (verifiedBootState == RootOfTrust.KM_VERIFIED_BOOT_VERIFIED) {
            if (attestationSecurityLevel == Attestation.KM_SECURITY_LEVEL_STRONG_BOX) {
                device = fingerprintsStockStrongBox.get(verifiedBootKey);
            } else {
                device = fingerprintsStock.get(verifiedBootKey);
            }
        } else {
            throw new GeneralSecurityException("verified boot state is not verified or self signed");
        }

        if (device == null) {
            throw new GeneralSecurityException("invalid verified boot key fingerprint: " + verifiedBootKey);
        }

        // OS version sanity checks
        final int osVersion = teeEnforced.getOsVersion();
        if (osVersion == DEVELOPER_PREVIEW_OS_VERSION) {
            if (!BuildConfig.DEBUG) {
                throw new GeneralSecurityException("OS version is not a production release");
            }
        } else if (osVersion < OS_VERSION_MINIMUM) {
            throw new GeneralSecurityException("OS version too old: " + osVersion);
        }
        final int osPatchLevel = teeEnforced.getOsPatchLevel();
        if (osPatchLevel < OS_PATCH_LEVEL_MINIMUM) {
            throw new GeneralSecurityException("OS patch level too old: " + osPatchLevel);
        }
        final int vendorPatchLevel;
        if (teeEnforced.getVendorPatchLevel() == null) {
            vendorPatchLevel = 0;
        } else {
            vendorPatchLevel = teeEnforced.getVendorPatchLevel();
            if (vendorPatchLevel < VENDOR_PATCH_LEVEL_MINIMUM && !extraPatchLevelMissing.contains(device.name)) {
                throw new GeneralSecurityException("Vendor patch level too old: " + vendorPatchLevel);
            }
        }
        final int bootPatchLevel;
        if (teeEnforced.getBootPatchLevel() == null) {
            bootPatchLevel = 0;
        } else {
            bootPatchLevel = teeEnforced.getBootPatchLevel();
            if (bootPatchLevel < BOOT_PATCH_LEVEL_MINIMUM && !extraPatchLevelMissing.contains(device.name)) {
                throw new GeneralSecurityException("Boot patch level too old: " + bootPatchLevel);
            }
        }

        // key sanity checks
        if (!teeEnforced.getPurposes().equals(
                ImmutableSet.of(AuthorizationList.KM_PURPOSE_SIGN, AuthorizationList.KM_PURPOSE_VERIFY))) {
            throw new GeneralSecurityException("key has invalid purposes");
        }
        if (teeEnforced.getOrigin() != AuthorizationList.KM_ORIGIN_GENERATED) {
            throw new GeneralSecurityException("key not origin generated");
        }
        if (teeEnforced.isAllApplications()) {
            throw new GeneralSecurityException("expected key only usable by Auditor app");
        }
        if (device.rollbackResistant && !teeEnforced.isRollbackResistant()) {
            throw new GeneralSecurityException("expected rollback resistant key");
        }

        // version sanity checks
        final int attestationVersion = attestation.getAttestationVersion();
        Log.d(TAG, "attestationVersion: " + attestationVersion);
        if (attestationVersion < device.attestationVersion) {
            throw new GeneralSecurityException(
                    "attestation version " + attestationVersion + " below " + device.attestationVersion);
        }
        final int keymasterVersion = attestation.getKeymasterVersion();
        Log.d(TAG, "keymasterVersion: " + keymasterVersion);
        if (keymasterVersion < device.keymasterVersion) {
            throw new GeneralSecurityException(
                    "keymaster version " + keymasterVersion + " below " + device.keymasterVersion);
        }

        final byte[] verifiedBootHash = rootOfTrust.getVerifiedBootHash();
        if (attestationVersion >= 3 && verifiedBootHash == null) {
            throw new GeneralSecurityException("verifiedBootHash expected for attestation version >= 3");
        }

        boolean attestKey = false;
        try {
            final Attestation attestation1 = new Attestation((X509Certificate) certificates[1]);

            if (attestation1.getAttestationSecurityLevel() != attestation.getAttestationSecurityLevel()) {
                throw new GeneralSecurityException("attest key attestation security level does not match");
            }

            if (attestation1.getKeymasterSecurityLevel() != attestation.getKeymasterSecurityLevel()) {
                throw new GeneralSecurityException("attest key keymaster security level does not match");
            }

            final AuthorizationList teeEnforced1 = attestation1.getTeeEnforced();

            // verified boot security checks
            final RootOfTrust rootOfTrust1 = teeEnforced1.getRootOfTrust();
            if (rootOfTrust1 == null) {
                throw new GeneralSecurityException("attest key missing root of trust");
            }
            if (rootOfTrust1.isDeviceLocked() != rootOfTrust.isDeviceLocked()) {
                throw new GeneralSecurityException("attest key lock state does not match");
            }
            if (rootOfTrust1.getVerifiedBootState() != rootOfTrust.getVerifiedBootState()) {
                throw new GeneralSecurityException("attest key verified boot state does not match");
            }
            if (!Arrays.equals(rootOfTrust1.getVerifiedBootKey(), rootOfTrust.getVerifiedBootKey())) {
                throw new GeneralSecurityException("attest key verified boot key does not match");
            }

            // key sanity checks
            if (!teeEnforced1.getPurposes().equals(ImmutableSet.of(AuthorizationList.KM_PURPOSE_ATTEST_KEY))) {
                throw new GeneralSecurityException("attest key has invalid purposes");
            }
            if (teeEnforced1.getOrigin() != AuthorizationList.KM_ORIGIN_GENERATED) {
                throw new GeneralSecurityException("attest key not origin generated");
            }
            if (teeEnforced1.isAllApplications()) {
                throw new GeneralSecurityException("expected attest key only usable by Auditor app");
            }
            if (device.rollbackResistant && !teeEnforced1.isRollbackResistant()) {
                throw new GeneralSecurityException("expected rollback resistant attest key");
            }

            if (!hasPersistentKey) {
                if (!Arrays.equals(attestation1.getAttestationChallenge(), attestation.getAttestationChallenge())) {
                    throw new GeneralSecurityException("attest key challenge does not match");
                }

                if (!attestation1.getSoftwareEnforced().getAttestationApplicationId()
                        .equals(attestationApplicationId)) {
                    throw new GeneralSecurityException("attest key application does not match");
                }

                // version sanity checks
                if (attestation1.getAttestationVersion() != attestation.getAttestationVersion()) {
                    throw new GeneralSecurityException("attest key attestation version does not match");
                }
                if (attestation1.getKeymasterVersion() != attestation.getKeymasterVersion()) {
                    throw new GeneralSecurityException("attest key keymaster version does not match");
                }

                // OS version sanity checks
                if (!teeEnforced1.getOsVersion().equals(teeEnforced.getOsVersion())) {
                    throw new GeneralSecurityException("attest key OS version does not match");
                }
                if (!teeEnforced1.getOsPatchLevel().equals(teeEnforced.getOsPatchLevel())) {
                    throw new GeneralSecurityException("attest key OS patch level does not match");
                }
                if (!teeEnforced1.getVendorPatchLevel().equals(teeEnforced.getVendorPatchLevel())) {
                    throw new GeneralSecurityException("attest key vendor patch level does not match");
                }
                if (!teeEnforced1.getBootPatchLevel().equals(teeEnforced.getBootPatchLevel())) {
                    throw new GeneralSecurityException("attest key boot patch level does not match");
                }

                if (!Arrays.equals(rootOfTrust1.getVerifiedBootHash(), rootOfTrust.getVerifiedBootHash())) {
                    throw new GeneralSecurityException("attest key verified boot hash does not match");
                }
            }

            attestKey = true;
        } catch (final Attestation.KeyDescriptionMissingException e) {
        }

        for (int i = 2; i < certificates.length; i++) {
            try {
                new Attestation((X509Certificate) certificates[i]);
            } catch (final Attestation.KeyDescriptionMissingException e) {
                continue;
            }
            throw new GeneralSecurityException("only initial key and attest key should have attestation extension");
        }

        return new Verified(device.name, verifiedBootKey, verifiedBootHash, device.osName,
                osVersion, osPatchLevel, vendorPatchLevel, bootPatchLevel, appVersion, appVariant,
                attestationSecurityLevel, attestKey, device.perUserEncryption,
                device.enforceStrongBox);
    }

    // Only checks expiry beyond the initial certificate for the initial pairing
    // since the
    // certificates are short lived when remote provisioning is in use and we
    // prevent rotation by
    // using the attest key feature to provide permanent pairing-specific
    // certificate chains in
    // order to pin them.
    private static void verifyCertificateSignatures(final Certificate[] certChain, final boolean hasPersistentKey)
            throws GeneralSecurityException {
        for (int i = 1; i < certChain.length; ++i) {
            try {
                if (i == 1 || !hasPersistentKey) {
                    ((X509Certificate) certChain[i - 1]).checkValidity();
                }
                certChain[i - 1].verify(certChain[i].getPublicKey());
            } catch (InvalidKeyException | CertificateException | NoSuchAlgorithmException
                    | NoSuchProviderException | SignatureException e) {
                throw new GeneralSecurityException("Failed to verify certificate "
                        + certChain[i - 1] + " with public key " + certChain[i].getPublicKey(), e);
            }
        }

        // Last cert is self-signed.
        final int i = certChain.length - 1;
        try {
            if (i == 0 || !hasPersistentKey) {
                ((X509Certificate) certChain[i]).checkValidity();
            }
            certChain[i].verify(certChain[i].getPublicKey());
        } catch (CertificateException e) {
            throw new GeneralSecurityException(
                    "Root cert " + certChain[i] + " is not correctly self-signed", e);
        }
    }

    private static String formatPatchLevel(final int patchLevel) {
        final String s = Integer.toString(patchLevel);
        return s.substring(0, 4) + "-" + s.substring(4, 6) +
                (s.length() >= 8 ? "-" + s.substring(6, 8) : "");
    }

    private static void appendVerifiedInformation(final Context context,
            final StringBuilder builder, final Verified verified, final String fingerprint,
            final boolean attestKeyMigration) {
        final StringBuilder splitFingerprint = new StringBuilder();
        for (int i = 0; i < fingerprint.length(); i += FINGERPRINT_SPLIT_INTERVAL) {
            splitFingerprint.append(fingerprint.substring(i,
                    Math.min(fingerprint.length(), i + FINGERPRINT_SPLIT_INTERVAL)));
            if (i + FINGERPRINT_SPLIT_INTERVAL < fingerprint.length()) {
                splitFingerprint.append("-");
            }
        }
        builder.append(context.getString(R.string.identity, splitFingerprint.toString()));

        final String securityLevel;
        if (verified.securityLevel == Attestation.KM_SECURITY_LEVEL_STRONG_BOX) {
            if (verified.attestKey && !attestKeyMigration) {
                securityLevel = context.getString(R.string.security_level_strongbox_attest_key);
            } else {
                securityLevel = context.getString(R.string.security_level_strongbox);
            }
        } else {
            if (verified.attestKey && !attestKeyMigration) {
                securityLevel = context.getString(R.string.security_level_tee_attest_key);
            } else {
                securityLevel = context.getString(R.string.security_level_tee);
            }
        }
        builder.append(context.getString(R.string.security_level, securityLevel));

        builder.append(context.getString(R.string.device, context.getString(verified.device)));
        builder.append(context.getString(R.string.os, context.getString(verified.osName)));

        if (verified.osVersion == DEVELOPER_PREVIEW_OS_VERSION) {
            builder.append(context.getString(R.string.os_version,
                    context.getString(R.string.os_version_developer_preview)));
        } else {
            final String osVersion = String.format(Locale.US, "%06d", verified.osVersion);
            builder.append(context.getString(R.string.os_version,
                    Integer.parseInt(osVersion.substring(0, 2)) + "." +
                            Integer.parseInt(osVersion.substring(2, 4)) + "." +
                            Integer.parseInt(osVersion.substring(4, 6))));
        }

        builder.append(context.getString(R.string.os_patch_level, formatPatchLevel(verified.osPatchLevel)));

        if (verified.vendorPatchLevel != 0) {
            builder.append(context.getString(R.string.vendor_patch_level, formatPatchLevel(verified.vendorPatchLevel)));
        }

        if (verified.bootPatchLevel != 0) {
            builder.append(context.getString(R.string.boot_patch_level, formatPatchLevel(verified.bootPatchLevel)));
        }

        builder.append(context.getString(R.string.verified_boot_key_hash,
                verified.verifiedBootKey));

        if (verified.verifiedBootHash != null) {
            builder.append(context.getString(R.string.verified_boot_hash,
                    BaseEncoding.base16().encode(verified.verifiedBootHash)));
        }
    }

    private static void verifySignature(final PublicKey key, final ByteBuffer message,
            final byte[] signature) throws GeneralSecurityException {
        final Signature sig = Signature.getInstance(SIGNATURE_ALGORITHM);
        sig.initVerify(key);
        sig.update(message);
        if (!sig.verify(signature)) {
            throw new GeneralSecurityException("signature verification failed");
        }
    }

    static class VerificationResult {
        final boolean strong;
        final String teeEnforced;
        final String osEnforced;
        final String history;

        VerificationResult(final boolean strong, final String teeEnforced,
                final String osEnforced, final String history) {
            this.strong = strong;
            this.teeEnforced = teeEnforced;
            this.osEnforced = osEnforced;
            this.history = history;
        }
    }

    private static String toYesNoString(final Context context, final boolean value) {
        return value ? context.getString(R.string.yes) : context.getString(R.string.no);
    }

    private static VerificationResult verify(final Context context, final byte[] fingerprint,
            final byte[] challenge, final ByteBuffer signedMessage, final byte[] signature,
            final Certificate[] attestationCertificates, final boolean userProfileSecure,
            final boolean accessibility, final boolean deviceAdmin,
            final boolean deviceAdminNonSystem, final boolean adbEnabled,
            final boolean addUsersWhenLocked, final boolean enrolledBiometrics,
            final boolean denyNewUsb, final boolean oemUnlockAllowed, final boolean systemUser)
            throws GeneralSecurityException, IOException {
        final String fingerprintHex = BaseEncoding.base16().encode(fingerprint);
        final byte[] currentFingerprint = getFingerprint(attestationCertificates[0]);
        final boolean hasPersistentKey = !Arrays.equals(currentFingerprint, fingerprint);

        final SharedPreferences preferences = context.getSharedPreferences(PREFERENCES_DEVICE_PREFIX + fingerprintHex,
                Context.MODE_PRIVATE);
        if (hasPersistentKey && !preferences.contains(KEY_PINNED_CERTIFICATE_LENGTH)) {
            throw new GeneralSecurityException(
                    "Pairing data for this Auditee is missing. Cannot perform paired attestation.\n" +
                            "\nEither the initial pairing was incomplete or the device is compromised.\n" +
                            "\nIf the initial pairing was simply not completed, clear the pairing data on either the Auditee or the Auditor via the menu and try again.\n");
        }

        final Verified verified = verifyStateless(context, attestationCertificates, challenge, hasPersistentKey,
                generateCertificate(context.getResources(), R.raw.google_root_0),
                generateCertificate(context.getResources(), R.raw.google_root_1),
                generateCertificate(context.getResources(), R.raw.google_root_2));

        final StringBuilder teeEnforced = new StringBuilder();
        final StringBuilder history = new StringBuilder();

        boolean attestKeyMigration = false;
        if (hasPersistentKey) {
            final int chainOffset;
            final int pinOffset;
            if (attestationCertificates.length != preferences.getInt(KEY_PINNED_CERTIFICATE_LENGTH, 0)) {
                if (attestationCertificates.length == 5 && preferences.getInt(KEY_PINNED_CERTIFICATE_LENGTH, 0) == 4) {
                    // backwards compatible use of attest key without the security benefits for
                    // forward compatibility with remote provisioning
                    chainOffset = 1;
                    pinOffset = 0;
                    attestKeyMigration = true;
                } else if (ALLOW_ATTEST_KEY_DOWNGRADE && attestationCertificates.length == 4
                        && preferences.getInt(KEY_PINNED_CERTIFICATE_LENGTH, 0) == 5) {
                    // temporarily work around attest key breakage by allowing not using it
                    chainOffset = 0;
                    pinOffset = 1;
                } else {
                    throw new GeneralSecurityException("certificate chain length mismatch");
                }
            } else {
                chainOffset = 0;
                pinOffset = 0;
            }
            for (int i = 1 + chainOffset; i < attestationCertificates.length; i++) {
                final byte[] b = BaseEncoding.base64()
                        .decode(preferences.getString(KEY_PINNED_CERTIFICATE + (i - chainOffset + pinOffset), ""));
                if (!Arrays.equals(attestationCertificates[i].getEncoded(), b)) {
                    throw new GeneralSecurityException("certificate chain mismatch");
                }
            }

            final byte[] persistentCertificateEncoded = BaseEncoding.base64()
                    .decode(preferences.getString(KEY_PINNED_CERTIFICATE + "0", ""));
            final Certificate persistentCertificate = generateCertificate(
                    new ByteArrayInputStream(persistentCertificateEncoded));
            if (!Arrays.equals(fingerprint, getFingerprint(persistentCertificate))) {
                throw new GeneralSecurityException("corrupt Auditor pinning data");
            }
            verifySignature(persistentCertificate.getPublicKey(), signedMessage, signature);

            final String pinnedVerifiedBootKey = preferences.getString(KEY_PINNED_VERIFIED_BOOT_KEY, null);
            if (!verified.verifiedBootKey.equals(pinnedVerifiedBootKey)) {
                throw new GeneralSecurityException("pinned verified boot key mismatch");
            }
            if (verified.osVersion != DEVELOPER_PREVIEW_OS_VERSION &&
                    verified.osVersion < preferences.getInt(KEY_PINNED_OS_VERSION, Integer.MAX_VALUE)) {
                throw new GeneralSecurityException("OS version downgrade detected");
            }
            if (verified.osPatchLevel < preferences.getInt(KEY_PINNED_OS_PATCH_LEVEL, Integer.MAX_VALUE)) {
                throw new GeneralSecurityException("OS patch level downgrade detected");
            }
            if (verified.vendorPatchLevel < preferences.getInt(KEY_PINNED_VENDOR_PATCH_LEVEL, 0)) {
                throw new GeneralSecurityException("Vendor patch level downgrade detected");
            }
            if (verified.bootPatchLevel < preferences.getInt(KEY_PINNED_BOOT_PATCH_LEVEL, 0)) {
                throw new GeneralSecurityException("Boot patch level downgrade detected");
            }
            final int pinnedAppVersion = preferences.getInt(KEY_PINNED_APP_VERSION, Integer.MAX_VALUE);
            if (verified.appVersion < pinnedAppVersion) {
                throw new GeneralSecurityException("App version downgraded");
            }
            final int pinnedAppVariant = preferences.getInt(KEY_PINNED_APP_VARIANT, 0);
            if (verified.appVariant < pinnedAppVariant) {
                throw new GeneralSecurityException("App version downgraded");
            }
            if (verified.securityLevel != preferences.getInt(KEY_PINNED_SECURITY_LEVEL,
                    Attestation.KM_SECURITY_LEVEL_TRUSTED_ENVIRONMENT)) {
                throw new GeneralSecurityException("Security level mismatch");
            }

            history.append(context.getString(R.string.first_verified,
                    new Date(preferences.getLong(KEY_VERIFIED_TIME_FIRST, 0))));
            history.append(context.getString(R.string.last_verified,
                    new Date(preferences.getLong(KEY_VERIFIED_TIME_LAST, 0))));

            final SharedPreferences.Editor editor = preferences.edit();
            editor.putInt(KEY_PINNED_OS_VERSION, verified.osVersion);
            editor.putInt(KEY_PINNED_OS_PATCH_LEVEL, verified.osPatchLevel);
            if (verified.vendorPatchLevel != 0) {
                editor.putInt(KEY_PINNED_VENDOR_PATCH_LEVEL, verified.vendorPatchLevel);
            }
            if (verified.bootPatchLevel != 0) {
                editor.putInt(KEY_PINNED_BOOT_PATCH_LEVEL, verified.bootPatchLevel);
            }
            editor.putInt(KEY_PINNED_APP_VERSION, verified.appVersion);
            editor.putInt(KEY_PINNED_APP_VARIANT, verified.appVariant);
            editor.putInt(KEY_PINNED_SECURITY_LEVEL, verified.securityLevel); // new field
            editor.putLong(KEY_VERIFIED_TIME_LAST, new Date().getTime());
            editor.apply();
        } else {
            verifySignature(attestationCertificates[0].getPublicKey(), signedMessage, signature);

            if (PREFER_STRONGBOX && verified.enforceStrongBox
                    && verified.securityLevel != Attestation.KM_SECURITY_LEVEL_STRONG_BOX) {
                throw new GeneralSecurityException(
                        "non-StrongBox security level for initial pairing with StrongBox device");
            }

            final SharedPreferences.Editor editor = preferences.edit();

            editor.putInt(KEY_PINNED_CERTIFICATE_LENGTH, attestationCertificates.length);
            for (int i = 0; i < attestationCertificates.length; i++) {
                final String encoded = BaseEncoding.base64().encode(
                        attestationCertificates[i].getEncoded());
                editor.putString(KEY_PINNED_CERTIFICATE + i, encoded);
            }

            editor.putString(KEY_PINNED_VERIFIED_BOOT_KEY, verified.verifiedBootKey);
            editor.putInt(KEY_PINNED_OS_VERSION, verified.osVersion);
            editor.putInt(KEY_PINNED_OS_PATCH_LEVEL, verified.osPatchLevel);
            if (verified.vendorPatchLevel != 0) {
                editor.putInt(KEY_PINNED_VENDOR_PATCH_LEVEL, verified.vendorPatchLevel);
            }
            if (verified.bootPatchLevel != 0) {
                editor.putInt(KEY_PINNED_BOOT_PATCH_LEVEL, verified.bootPatchLevel);
            }
            editor.putInt(KEY_PINNED_APP_VERSION, verified.appVersion);
            editor.putInt(KEY_PINNED_APP_VARIANT, verified.appVariant);
            editor.putInt(KEY_PINNED_SECURITY_LEVEL, verified.securityLevel);

            final long now = new Date().getTime();
            editor.putLong(KEY_VERIFIED_TIME_FIRST, now);
            editor.putLong(KEY_VERIFIED_TIME_LAST, now);

            editor.apply();
        }

        appendVerifiedInformation(context, teeEnforced, verified, fingerprintHex, attestKeyMigration);

        final StringBuilder osEnforced = new StringBuilder();
        osEnforced.append(context.getString(R.string.auditor_app_version, verified.appVersion));

        final String appVariant;
        if (verified.appVariant == AUDITOR_APP_VARIANT_RELEASE) {
            appVariant = context.getString(R.string.auditor_app_variant_release);
        } else if (verified.appVariant == AUDITOR_APP_VARIANT_PLAY) {
            appVariant = context.getString(R.string.auditor_app_variant_play);
        } else {
            appVariant = context.getString(R.string.auditor_app_variant_debug);
        }
        osEnforced.append(context.getString(R.string.auditor_app_variant, appVariant));

        osEnforced.append(context.getString(R.string.user_profile_secure,
                toYesNoString(context, userProfileSecure)));
        osEnforced.append(context.getString(R.string.enrolled_biometrics,
                toYesNoString(context, enrolledBiometrics)));
        osEnforced.append(context.getString(R.string.accessibility,
                toYesNoString(context, accessibility)));

        final String deviceAdminState;
        if (deviceAdminNonSystem) {
            deviceAdminState = context.getString(R.string.device_admin_non_system);
        } else if (deviceAdmin) {
            deviceAdminState = context.getString(R.string.device_admin_system);
        } else {
            deviceAdminState = context.getString(R.string.no);
        }
        osEnforced.append(context.getString(R.string.device_admin, deviceAdminState));

        osEnforced.append(context.getString(R.string.adb_enabled,
                toYesNoString(context, adbEnabled)));
        osEnforced.append(context.getString(R.string.add_users_when_locked,
                toYesNoString(context, addUsersWhenLocked)));
        osEnforced.append(context.getString(R.string.deny_new_usb,
                toYesNoString(context, denyNewUsb)));
        osEnforced.append(context.getString(R.string.oem_unlock_allowed,
                toYesNoString(context, oemUnlockAllowed)));
        osEnforced.append(context.getString(R.string.system_user,
                toYesNoString(context, systemUser)));

        return new VerificationResult(hasPersistentKey, teeEnforced.toString(), osEnforced.toString(),
                history.toString());
    }

    private static Certificate[] decodeChain(final byte[] dictionary, final byte[] compressedChain)
            throws DataFormatException, GeneralSecurityException {
        final byte[] chain = new byte[MAX_ENCODED_CHAIN_LENGTH];
        final Inflater inflater = new Inflater(true);
        inflater.setInput(compressedChain);
        inflater.setDictionary(dictionary);
        final int chainLength = inflater.inflate(chain);
        if (!inflater.finished()) {
            throw new GeneralSecurityException("certificate chain is too large");
        }
        inflater.end();
        Log.d(TAG, "encoded length: " + chainLength + ", compressed length: " + compressedChain.length);

        final ByteBuffer chainDeserializer = ByteBuffer.wrap(chain, 0, chainLength);
        final List<Certificate> certs = new ArrayList<>();
        while (chainDeserializer.hasRemaining()) {
            final short encodedLength = chainDeserializer.getShort();
            final byte[] encoded = new byte[encodedLength];
            chainDeserializer.get(encoded);
            certs.add(generateCertificate(new ByteArrayInputStream(encoded)));
        }
        return certs.toArray(new Certificate[0]);
    }

    private static byte[] encodeChain(final byte[] dictionary, final Certificate[] certificates)
            throws CertificateEncodingException, IOException {
        final ByteBuffer chainSerializer = ByteBuffer.allocate(MAX_ENCODED_CHAIN_LENGTH);
        for (Certificate certificate : certificates) {
            final byte[] encoded = certificate.getEncoded();
            if (encoded.length > Short.MAX_VALUE) {
                throw new RuntimeException("encoded certificate too long");
            }
            chainSerializer.putShort((short) encoded.length);
            chainSerializer.put(encoded);
        }
        chainSerializer.flip();
        final byte[] chain = new byte[chainSerializer.remaining()];
        chainSerializer.get(chain);

        if (chain.length > MAX_ENCODED_CHAIN_LENGTH) {
            throw new RuntimeException("encoded certificate chain too long");
        }

        final ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        final Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION, true);
        deflater.setDictionary(dictionary);
        final DeflaterOutputStream deflaterStream = new DeflaterOutputStream(byteStream, deflater);
        deflaterStream.write(chain);
        deflaterStream.finish();
        final byte[] compressed = byteStream.toByteArray();
        Log.d(TAG, "encoded length: " + chain.length + ", compressed length: " + compressed.length);

        return compressed;
    }

    static VerificationResult verifySerialized(final Context context, final byte[] attestationResult,
            final byte[] challengeMessage) throws DataFormatException, GeneralSecurityException, IOException {
        final ByteBuffer deserializer = ByteBuffer.wrap(attestationResult);
        final byte version = deserializer.get();
        if (version > PROTOCOL_VERSION) {
            throw new GeneralSecurityException("invalid protocol version: " + version);
        } else if (version < PROTOCOL_VERSION_MINIMUM) {
            throw new GeneralSecurityException("Auditee protocol version too old: " + version);
        }

        final short compressedChainLength = deserializer.getShort();
        final byte[] compressedChain = new byte[compressedChainLength];
        deserializer.get(compressedChain);

        final Certificate[] certificates;
        final int dictionary = R.raw.deflate_dictionary_3;
        try (final InputStream stream = context.getResources().openRawResource(dictionary)) {
            certificates = decodeChain(ByteStreams.toByteArray(stream), compressedChain);
        }

        final byte[] fingerprint = new byte[FINGERPRINT_LENGTH];
        deserializer.get(fingerprint);

        final int osEnforcedFlags = deserializer.getInt();
        if ((osEnforcedFlags & ~OS_ENFORCED_FLAGS_ALL) != 0) {
            Log.w(TAG, "unknown OS enforced flag set (flags: " + Integer.toBinaryString(osEnforcedFlags) + ")");
        }
        final boolean userProfileSecure = (osEnforcedFlags & OS_ENFORCED_FLAGS_USER_PROFILE_SECURE) != 0;
        final boolean accessibility = (osEnforcedFlags & OS_ENFORCED_FLAGS_ACCESSIBILITY) != 0;
        final boolean deviceAdmin = (osEnforcedFlags & OS_ENFORCED_FLAGS_DEVICE_ADMIN) != 0;
        final boolean deviceAdminNonSystem = (osEnforcedFlags & OS_ENFORCED_FLAGS_DEVICE_ADMIN_NON_SYSTEM) != 0;
        final boolean adbEnabled = (osEnforcedFlags & OS_ENFORCED_FLAGS_ADB_ENABLED) != 0;
        final boolean addUsersWhenLocked = (osEnforcedFlags & OS_ENFORCED_FLAGS_ADD_USERS_WHEN_LOCKED) != 0;
        final boolean enrolledBiometrics = (osEnforcedFlags & OS_ENFORCED_FLAGS_ENROLLED_BIOMETRICS) != 0;
        final boolean denyNewUsb = (osEnforcedFlags & OS_ENFORCED_FLAGS_DENY_NEW_USB) != 0;
        final boolean oemUnlockAllowed = (osEnforcedFlags & OS_ENFORCED_FLAGS_OEM_UNLOCK_ALLOWED) != 0;
        final boolean systemUser = (osEnforcedFlags & OS_ENFORCED_FLAGS_SYSTEM_USER) != 0;

        if (deviceAdminNonSystem && !deviceAdmin) {
            throw new GeneralSecurityException("invalid device administrator state");
        }

        final int signatureLength = deserializer.remaining();
        final byte[] signature = new byte[signatureLength];
        deserializer.get(signature);

        deserializer.rewind();
        deserializer.limit(deserializer.capacity() - signature.length);

        final byte[] challenge = Arrays.copyOfRange(challengeMessage, 1 + CHALLENGE_LENGTH, 1 + CHALLENGE_LENGTH * 2);
        return verify(context, fingerprint, challenge, deserializer.asReadOnlyBuffer(), signature,
                certificates, userProfileSecure, accessibility, deviceAdmin, deviceAdminNonSystem,
                adbEnabled, addUsersWhenLocked, enrolledBiometrics, denyNewUsb, oemUnlockAllowed,
                systemUser);
    }

    static class AttestationResult {
        final boolean pairing;
        final byte[] serialized;

        AttestationResult(final boolean pairing, final byte[] serialized) {
            this.pairing = pairing;
            this.serialized = serialized;
        }
    }

    @TargetApi(31)
    static void setAttestKeyAlias(final KeyGenParameterSpec.Builder builder, final String alias) {
        builder.setAttestKeyAlias(alias);
    }

    static KeyGenParameterSpec.Builder getKeyBuilder(final String alias, final int purposes,
            final boolean useStrongBox, final byte[] challenge, final boolean temporary) {
        final Date startTime = new Date(new Date().getTime() - CLOCK_SKEW_MS);
        final KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(alias, purposes)
                .setAlgorithmParameterSpec(new ECGenParameterSpec(EC_CURVE))
                .setDigests(KEY_DIGEST)
                .setAttestationChallenge(challenge)
                .setKeyValidityStart(startTime);
        if (temporary) {
            builder.setKeyValidityEnd(new Date(startTime.getTime() + EXPIRE_OFFSET_MS));
        }
        if (useStrongBox) {
            builder.setIsStrongBoxBacked(true);
        }
        return builder;
    }

    @TargetApi(31)
    static void generateAttestKey(final String alias, final byte[] challenge, final boolean useStrongBox)
            throws GeneralSecurityException, IOException {
        generateKeyPair(getKeyBuilder(alias, KeyProperties.PURPOSE_ATTEST_KEY,
                useStrongBox, challenge, false).build());
    }

    static Certificate getCertificate(final KeyStore keyStore, final String alias)
            throws GeneralSecurityException {
        final Certificate result = keyStore.getCertificate(alias);
        if (result == null) {
            throw new GeneralSecurityException("invalid hardware keystore state");
        }
        return result;
    }

    static Certificate[] getCertificateChain(final KeyStore keyStore, final String alias)
            throws GeneralSecurityException {
        final Certificate[] result = keyStore.getCertificateChain(alias);
        if (result == null) {
            throw new GeneralSecurityException("invalid hardware keystore state");
        }
        return result;
    }

    @SuppressWarnings("deprecation")
    static ApplicationInfo getApplicationInfo(final PackageManager pm, final String packageName,
            final int flags) throws PackageManager.NameNotFoundException {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return pm.getApplicationInfo(packageName, flags);
        }
        return pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(flags));
    }

    static AttestationResult generateSerialized(final Context context, final byte[] challengeMessage,
            String index, final String statePrefix) throws GeneralSecurityException, IOException {
        if (challengeMessage.length < CHALLENGE_MESSAGE_LENGTH) {
            throw new GeneralSecurityException("challenge message is too small");
        }

        final byte maxVersion = challengeMessage[0];
        if (maxVersion <= PROTOCOL_VERSION && challengeMessage.length != CHALLENGE_MESSAGE_LENGTH) {
            throw new GeneralSecurityException("challenge message is not the expected size");
        }
        if (maxVersion < PROTOCOL_VERSION_MINIMUM) {
            throw new GeneralSecurityException("Auditor protocol version too old: " + maxVersion);
        }
        final byte version = (byte) Math.min(PROTOCOL_VERSION, maxVersion);
        final byte[] challengeIndex = Arrays.copyOfRange(challengeMessage, 1, 1 + CHALLENGE_LENGTH);
        final byte[] challenge = Arrays.copyOfRange(challengeMessage, 1 + CHALLENGE_LENGTH, 1 + CHALLENGE_LENGTH * 2);

        final KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);

        if (index == null) {
            index = BaseEncoding.base16().encode(challengeIndex);
        }

        final String attestKeystoreAlias = statePrefix + KEYSTORE_ALIAS_ATTEST_PREFIX + index;
        final String persistentKeystoreAlias = statePrefix + KEYSTORE_ALIAS_PERSISTENT_PREFIX + index;

        final PackageManager pm = context.getPackageManager();

        // generate a new key for fresh attestation results unless the persistent key is
        // not yet created
        final boolean hasPersistentKey = keyStore.containsAlias(persistentKeystoreAlias);
        final String attestationKeystoreAlias;
        final boolean useStrongBox;
        @SuppressLint("InlinedApi")
        final boolean canUseAttestKey = (alwaysHasAttestKey
                || pm.hasSystemFeature(PackageManager.FEATURE_KEYSTORE_APP_ATTEST_KEY))
                && USE_ATTEST_KEY;
        boolean useAttestKey;
        if (hasPersistentKey) {
            final String freshKeyStoreAlias = statePrefix + KEYSTORE_ALIAS_FRESH;
            keyStore.deleteEntry(freshKeyStoreAlias);
            attestationKeystoreAlias = freshKeyStoreAlias;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                final PrivateKey key = (PrivateKey) keyStore.getKey(persistentKeystoreAlias, null);
                final KeyFactory factory = KeyFactory.getInstance(key.getAlgorithm(), "AndroidKeyStore");
                final KeyInfo keyinfo = factory.getKeySpec(key, KeyInfo.class);
                useStrongBox = keyinfo.getSecurityLevel() == KeyProperties.SECURITY_LEVEL_STRONGBOX;
            } else {
                final X509Certificate persistent = (X509Certificate) getCertificate(keyStore, persistentKeystoreAlias);
                final String dn = persistent.getIssuerX500Principal().getName(X500Principal.RFC1779);
                useStrongBox = dn.contains("StrongBox");
            }

            final boolean hasAttestKey = keyStore.containsAlias(attestKeystoreAlias);
            if (hasAttestKey) {
                useAttestKey = true;
            } else {
                if (canUseAttestKey) {
                    generateAttestKey(attestKeystoreAlias, challenge, useStrongBox);
                    useAttestKey = true;
                } else {
                    useAttestKey = false;
                }
            }
        } else {
            attestationKeystoreAlias = persistentKeystoreAlias;
            useStrongBox = isStrongBoxSupported && PREFER_STRONGBOX;
            useAttestKey = canUseAttestKey;

            if (useAttestKey) {
                generateAttestKey(attestKeystoreAlias, challenge, useStrongBox);
            }
        }

        try {
            final KeyGenParameterSpec.Builder builder = getKeyBuilder(attestationKeystoreAlias,
                    KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY, useStrongBox, challenge,
                    hasPersistentKey);
            if (useAttestKey) {
                setAttestKeyAlias(builder, attestKeystoreAlias);
            }
            generateKeyPair(builder.build());
        } catch (final IOException e) {
            // try without using attest key when already paired due to Pixel 6 / Pixel 6 Pro
            // / Pixel 6a upgrade bug
            if (hasPersistentKey) {
                useAttestKey = false;
                final KeyGenParameterSpec.Builder builder = getKeyBuilder(attestationKeystoreAlias,
                        KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY, useStrongBox, challenge,
                        hasPersistentKey);
                generateKeyPair(builder.build());
            } else {
                throw e;
            }
        }

        try {
            final byte[] fingerprint = getFingerprint(getCertificate(keyStore, persistentKeystoreAlias));

            final Certificate[] attestationCertificates;

            if (useAttestKey) {
                final Certificate[] attestCertificates = getCertificateChain(keyStore, attestKeystoreAlias);
                attestationCertificates = new Certificate[1 + attestCertificates.length];
                System.arraycopy(attestCertificates, 0, attestationCertificates, 1, attestCertificates.length);
                attestationCertificates[0] = getCertificate(keyStore, attestationKeystoreAlias);
            } else {
                attestationCertificates = getCertificateChain(keyStore, attestationKeystoreAlias);
            }

            // sanity check on the device being verified before sending it off to the
            // verifying device
            final Verified verified = verifyStateless(context, attestationCertificates, challenge, hasPersistentKey,
                    generateCertificate(context.getResources(), R.raw.google_root_0),
                    generateCertificate(context.getResources(), R.raw.google_root_1),
                    generateCertificate(context.getResources(), R.raw.google_root_2));

            // OS-enforced checks and information

            final DevicePolicyManager dpm = context.getSystemService(DevicePolicyManager.class);

            final List<ComponentName> activeAdmins = dpm.getActiveAdmins();
            final boolean deviceAdmin = activeAdmins != null && activeAdmins.size() > 0;
            boolean deviceAdminNonSystem = false;
            if (activeAdmins != null) {
                for (final ComponentName name : activeAdmins) {
                    try {
                        final ApplicationInfo info = getApplicationInfo(pm, name.getPackageName(), 0);
                        if ((info.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                            deviceAdminNonSystem = true;
                        }
                    } catch (final PackageManager.NameNotFoundException e) {
                        throw new GeneralSecurityException(e);
                    }
                }
            }

            final int encryptionStatus = dpm.getStorageEncryptionStatus();
            if (verified.perUserEncryption) {
                if (encryptionStatus != DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_PER_USER) {
                    throw new GeneralSecurityException("invalid encryption status");
                }
            } else {
                if (encryptionStatus != DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE &&
                        encryptionStatus != DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_DEFAULT_KEY) {
                    throw new GeneralSecurityException("invalid encryption status");
                }
            }
            final KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
            final boolean userProfileSecure = keyguard.isDeviceSecure();
            if (userProfileSecure && !keyguard.isKeyguardSecure()) {
                throw new GeneralSecurityException("keyguard state inconsistent");
            }
            final BiometricManager biometricManager = BiometricManager.from(context);
            final boolean enrolledBiometrics = biometricManager.canAuthenticate(BIOMETRIC_WEAK) == BIOMETRIC_SUCCESS;

            final AccessibilityManager am = context.getSystemService(AccessibilityManager.class);
            final boolean accessibility = am.isEnabled();

            final boolean adbEnabled = Settings.Global.getInt(context.getContentResolver(),
                    Settings.Global.ADB_ENABLED, 0) != 0;
            final boolean addUsersWhenLocked = Settings.Global.getInt(context.getContentResolver(),
                    ADD_USERS_WHEN_LOCKED, 0) != 0;

            final String denyNewUsbValue = SystemProperties.get("persist.security.deny_new_usb", "disabled");
            final boolean denyNewUsb = !denyNewUsbValue.equals("disabled");

            final String oemUnlockAllowedValue = SystemProperties.get("sys.oem_unlock_allowed", "0");
            final boolean oemUnlockAllowed = oemUnlockAllowedValue.equals("1");

            final UserManager userManager = context.getSystemService(UserManager.class);
            final boolean systemUser = userManager.isSystemUser();

            // Serialization

            final ByteBuffer serializer = ByteBuffer.allocate(MAX_MESSAGE_SIZE);

            serializer.put(version);

            final byte[] compressed;
            final int dictionary = R.raw.deflate_dictionary_3;
            try (final InputStream stream = context.getResources().openRawResource(dictionary)) {
                compressed = encodeChain(ByteStreams.toByteArray(stream), attestationCertificates);
            }

            if (compressed.length > Short.MAX_VALUE) {
                throw new RuntimeException("compressed chain too long");
            }

            serializer.putShort((short) compressed.length);
            serializer.put(compressed);

            if (fingerprint.length != FINGERPRINT_LENGTH) {
                throw new RuntimeException("fingerprint length mismatch");
            }
            serializer.put(fingerprint);

            int osEnforcedFlags = OS_ENFORCED_FLAGS_NONE;
            if (userProfileSecure) {
                osEnforcedFlags |= OS_ENFORCED_FLAGS_USER_PROFILE_SECURE;
            }
            if (accessibility) {
                osEnforcedFlags |= OS_ENFORCED_FLAGS_ACCESSIBILITY;
            }
            if (deviceAdmin) {
                osEnforcedFlags |= OS_ENFORCED_FLAGS_DEVICE_ADMIN;
            }
            if (deviceAdminNonSystem) {
                osEnforcedFlags |= OS_ENFORCED_FLAGS_DEVICE_ADMIN_NON_SYSTEM;
            }
            if (adbEnabled) {
                osEnforcedFlags |= OS_ENFORCED_FLAGS_ADB_ENABLED;
            }
            if (addUsersWhenLocked) {
                osEnforcedFlags |= OS_ENFORCED_FLAGS_ADD_USERS_WHEN_LOCKED;
            }
            if (enrolledBiometrics) {
                osEnforcedFlags |= OS_ENFORCED_FLAGS_ENROLLED_BIOMETRICS;
            }
            if (denyNewUsb) {
                osEnforcedFlags |= OS_ENFORCED_FLAGS_DENY_NEW_USB;
            }
            if (oemUnlockAllowed) {
                osEnforcedFlags |= OS_ENFORCED_FLAGS_OEM_UNLOCK_ALLOWED;
            }
            if (systemUser) {
                osEnforcedFlags |= OS_ENFORCED_FLAGS_SYSTEM_USER;
            }
            serializer.putInt(osEnforcedFlags);

            final ByteBuffer message = serializer.duplicate();
            message.flip();

            final Signature sig = Signature.getInstance(SIGNATURE_ALGORITHM);
            sig.initSign((PrivateKey) keyStore.getKey(persistentKeystoreAlias, null));
            sig.update(message);
            final byte[] signature = sig.sign();

            serializer.put(signature);

            serializer.flip();
            final byte[] serialized = new byte[serializer.remaining()];
            serializer.get(serialized);

            return new AttestationResult(!hasPersistentKey, serialized);
        } catch (final GeneralSecurityException | IOException e) {
            if (!hasPersistentKey) {
                keyStore.deleteEntry(persistentKeystoreAlias);
            }
            throw e;
        }
    }

    static void generateKeyPair(final KeyGenParameterSpec spec)
            throws NoSuchAlgorithmException, NoSuchProviderException,
            InvalidAlgorithmParameterException, IOException {
        // Handle RuntimeExceptions caused by a broken keystore. A common issue involves
        // users
        // unlocking the device and wiping the encrypted TEE attestation keys from the
        // persist
        // partition. Additionally, some non-CTS compliant devices or operating systems
        // have a
        // non-existent or broken implementation. No one has reported these uncaught
        // exceptions,
        // presumably because they know their device or OS is broken, but the crash
        // reports are
        // being spammed to the Google Play error collection and causing it to think the
        // app is
        // unreliable.
        try {
            final KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC,
                    "AndroidKeyStore");
            keyPairGenerator.initialize(spec);
            keyPairGenerator.generateKeyPair();
        } catch (final ProviderException e) {
            throw new IOException(e);
        }
    }

    static void deleteKey(final KeyStore keyStore, final String alias) throws GeneralSecurityException {
        Log.d(TAG, "deleting key " + alias);
        keyStore.deleteEntry(alias);
    }

    static void clearAuditee() throws GeneralSecurityException, IOException {
        final KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);

        final Enumeration<String> aliases = keyStore.aliases();
        while (aliases.hasMoreElements()) {
            final String alias = aliases.nextElement();
            if (alias.startsWith(KEYSTORE_ALIAS_ATTEST_PREFIX) || alias.startsWith(KEYSTORE_ALIAS_PERSISTENT_PREFIX)) {
                deleteKey(keyStore, alias);
            }
        }
    }

    static void clearAuditee(final String statePrefix, final String index)
            throws GeneralSecurityException, IOException {
        final KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);

        deleteKey(keyStore, statePrefix + KEYSTORE_ALIAS_ATTEST_PREFIX + index);
        deleteKey(keyStore, statePrefix + KEYSTORE_ALIAS_PERSISTENT_PREFIX + index);
    }

    static void clearAuditor(final Context context) {
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit().remove(KEY_CHALLENGE_INDEX).apply();

        final File dir = new File(context.getFilesDir().getParent() + "/shared_prefs/");
        for (final String file : dir.list()) {
            if (file.startsWith(PREFERENCES_DEVICE_PREFIX)) {
                final String name = file.replace(".xml", "");
                Log.d(TAG, "delete SharedPreferences " + name);
                context.deleteSharedPreferences(name);
            }
        }
    }
}
