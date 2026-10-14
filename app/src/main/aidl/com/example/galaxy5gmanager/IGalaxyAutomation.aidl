package com.example.galaxy5gmanager;
import com.example.galaxy5gmanager.IGalaxyPageReader;
interface IGalaxyAutomation {
    int shellUid() = 1;
    boolean startRun(String script, String mode, String profile, String confirmation) = 2;
    boolean isRunning() = 3;
    String getReport() = 4;
    void cancel() = 5;
    void setPageReader(IGalaxyPageReader reader) = 6;
    void destroy() = 16777114;
}