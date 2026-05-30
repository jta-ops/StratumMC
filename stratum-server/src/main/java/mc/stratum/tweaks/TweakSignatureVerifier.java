package mc.stratum.tweaks;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.SignatureException;
import java.util.Base64;
import java.util.logging.Logger;

/**
 * Verifies the Ed25519 signature of a tweak JAR.
 *
 * <p>The detached signature is stored alongside the JAR as a {@code .sig} file
 * containing the base64-encoded Ed25519 signature of the raw JAR bytes.
 */
public final class TweakSignatureVerifier {

    private static final Logger LOGGER = Logger.getLogger("TweakSignatureVerifier");
    private static final String ALGORITHM = "Ed25519";

    private TweakSignatureVerifier() {}

    /**
     * Verifies that {@code jarFile} was signed with the private key corresponding to
     * {@code publicKey}, with the signature stored in {@code sigFile}.
     *
     * @param jarFile   the JAR to verify
     * @param sigFile   file containing the base64-encoded Ed25519 signature
     * @param publicKey the Ed25519 public key to verify against
     * @return {@code true} if the signature is valid, {@code false} otherwise
     */
    public static boolean verify(final File jarFile, final File sigFile, final PublicKey publicKey) {
        if (!jarFile.exists()) {
            LOGGER.warning("[Stratum] Signature check failed: JAR not found: " + jarFile);
            return false;
        }
        if (!sigFile.exists()) {
            LOGGER.warning("[Stratum] Signature check failed: sig file not found: " + sigFile);
            return false;
        }

        try {
            final byte[] jarBytes = Files.readAllBytes(jarFile.toPath());
            final String sigBase64 = Files.readString(sigFile.toPath()).trim();
            final byte[] sigBytes = Base64.getDecoder().decode(sigBase64);

            final java.security.Signature sig = java.security.Signature.getInstance(ALGORITHM);
            sig.initVerify(publicKey);
            sig.update(jarBytes);
            return sig.verify(sigBytes);

        } catch (IOException ex) {
            LOGGER.severe("[Stratum] IO error during signature verification for " + jarFile.getName()
                    + ": " + ex.getMessage());
        } catch (NoSuchAlgorithmException ex) {
            LOGGER.severe("[Stratum] Ed25519 algorithm not available in this JVM: " + ex.getMessage());
        } catch (InvalidKeyException ex) {
            LOGGER.severe("[Stratum] Invalid public key during verification: " + ex.getMessage());
        } catch (SignatureException ex) {
            // Signature is structurally malformed (not just wrong)
            LOGGER.warning("[Stratum] Malformed signature for " + jarFile.getName()
                    + ": " + ex.getMessage());
        } catch (IllegalArgumentException ex) {
            // Base64 decode failure
            LOGGER.warning("[Stratum] Could not decode base64 signature for " + jarFile.getName()
                    + ": " + ex.getMessage());
        }

        return false;
    }
}
