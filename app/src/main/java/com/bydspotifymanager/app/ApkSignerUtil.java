package com.bydspotifymanager.app;

import com.android.apksig.ApkSigner;

import java.io.File;
import java.util.Collections;

final class ApkSignerUtil {
    static void sign(File unsignedApk, File signedApk) throws Exception {
        SigningKeyStore.KeyMaterial km = SigningKeyStore.getOrCreate();
        ApkSigner.SignerConfig signer = new ApkSigner.SignerConfig.Builder(
                "BYD Multi Patcher",
                km.privateKey,
                Collections.singletonList(km.certificate)).build();
        new ApkSigner.Builder(Collections.singletonList(signer))
                .setInputApk(unsignedApk)
                .setOutputApk(signedApk)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .setMinSdkVersion(24)
                .build()
                .sign();
    }
}
