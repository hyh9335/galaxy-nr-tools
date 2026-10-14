package com.example.galaxy5gmanager;
// Callback lives in the regular app process; the model is bundled offline.
interface IGalaxyPageReader {
    String recognize(in byte[] jpeg, int screenWidth, int screenHeight);
}
