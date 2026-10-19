package com.example.tiktoktowhatsappstickers.core.shizuku;

import android.os.ParcelFileDescriptor;

// Runs inside Shizuku's privileged (adb-shell) process so it can read other apps' Android/data folders.
interface IFileService {
    // Transaction id 16777114 is reserved by Shizuku for tearing the service down.
    void destroy() = 16777114;

    boolean exists(String path) = 1;

    // Returns "size|path" entries for plausible sticker files under root, [skip, skip+take).
    String[] listPage(String root, int skip, int take) = 2;

    ParcelFileDescriptor open(String path) = 3;
}
