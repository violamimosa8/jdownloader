package jd;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.crypto.Cipher;

import org.appwork.utils.Application;
import org.appwork.utils.IO;

public class UJCECheck {

    private static Boolean isRestrictedCryptography() {
        try {
            final int strength = Cipher.getMaxAllowedKeyLength("AES");
            if (strength > 128) {
                return false;
            } else {
                return true;
            }

        } catch (Throwable e) {
            return null;
        }
    }

    private static boolean isOracleJVM() {
        return "Java(TM) SE Runtime Environment".equals(System.getProperty("java.runtime.name"));
    }

    private static final AtomicBoolean checked = new AtomicBoolean(false);

    public static final void check() {
        if (checked.compareAndSet(false, true)) {
            if (Boolean.TRUE.equals(isRestrictedCryptography()) && isOracleJVM()) {
                final String javaHome = System.getProperty("java.home", null);
                final File securityFolder = new File(javaHome, "lib" + File.separatorChar + "security");
                if (securityFolder.exists() && securityFolder.isDirectory()) {
                    final long javaVersion = Application.getJavaVersion();
                    final File uJCE;
                    if (javaVersion >= Application.JAVA18) {
                        uJCE = Application.getResource("security/ujce8");
                    } else if (javaVersion >= Application.JAVA17) {
                        uJCE = Application.getResource("security/ujce7");
                    } else if (javaVersion >= Application.JAVA16) {
                        uJCE = Application.getResource("security/ujce6");
                    } else {
                        return;
                    }
                    if (uJCE.exists() && uJCE.isDirectory()) {
                        final Thread thread = new Thread("UnlimitedJCEInstallation") {
                            public void run() {
                                try {
                                    IO.copyFolderRecursive(uJCE, securityFolder, true);
                                } catch (final Throwable e) {
                                    e.printStackTrace();
                                }
                            };
                        };
                        thread.start();

                    }
                }
            }
        }
    }

    /**
     * http://stackoverflow.com/questions/1179672/how-to-avoid-installing-unlimited-strength-jce-policy-files-when-deploying-an
     */
    // private static void removeCryptographyRestrictions() {
    // if (!isRestrictedCryptography()) {
    // System.out.println("Cryptography restrictions removal not needed");
    // return;
    // }
    // try {
    // /*
    // * Do the following, but with reflection to bypass access checks:
    // *
    // * JceSecurity.isRestricted = false; JceSecurity.defaultPolicy.perms.clear();
    // * JceSecurity.defaultPolicy.add(CryptoAllPermission.INSTANCE);
    // */
    // final Class<?> jceSecurity = Class.forName("javax.crypto.JceSecurity");
    // final Class<?> cryptoPermissions = Class.forName("javax.crypto.CryptoPermissions");
    // final Class<?> cryptoAllPermission = Class.forName("javax.crypto.CryptoAllPermission");
    //
    // final Field isRestrictedField = jceSecurity.getDeclaredField("isRestricted");
    // isRestrictedField.setAccessible(true);
    // isRestrictedField.set(null, false);
    //
    // final Field defaultPolicyField = jceSecurity.getDeclaredField("defaultPolicy");
    // defaultPolicyField.setAccessible(true);
    // final PermissionCollection defaultPolicy = (PermissionCollection) defaultPolicyField.get(null);
    //
    // final Field perms = cryptoPermissions.getDeclaredField("perms");
    // perms.setAccessible(true);
    // ((Map<?, ?>) perms.get(defaultPolicy)).clear();
    //
    // final Field instance = cryptoAllPermission.getDeclaredField("INSTANCE");
    // instance.setAccessible(true);
    // defaultPolicy.add((Permission) instance.get(null));
    //
    // System.out.println("Successfully removed cryptography restrictions");
    // } catch (final Throwable e) {
    // System.out.println("Failed to remove cryptography restrictions:" + e);
    // }
    // }
}
