package com.example.galaxy5gmanager;
import android.os.ParcelFileDescriptor;
// Screenshots travel as a file descriptor, never as large Binder byte arrays.
interface IGalaxyPageReader {
    String recognize(in ParcelFileDescriptor screenshot, int screenWidth, int screenHeight);
    oneway void onProgress(String line);
}